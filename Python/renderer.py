import tkinter as tk
from vec2 import Vec2
from world import World, snapshot_body_states, restore_body_states, apply_rest_pose_from_targets
from solver import step, apply_force_at_point
from balance import HipBalanceController

WIDTH, HEIGHT = 800, 600
PIXELS_PER_METER = 150
ORIGIN_X = WIDTH // 2
ORIGIN_Y = HEIGHT - 60

LIFT_HEIGHT = 0.3   # meters the guided anchor rises while space is held
STEP_RATE = 0.5     # meters/second the sideways target recedes while space is held
LIFT_SPEED = 0.8    # meters/second the anchor itself travels toward its target

DRAG_STIFFNESS = 550.0    # N per meter of drag, applied at each hip joint on left-drag
MAX_DRAG_FORCE = 10000.0  # N clamp, effectively unbounded for now

JUMP_CHARGE_TIME = 0.8    # seconds of holding Up to go from empty to full charge
JUMP_MIN_IMPULSE = 1.5    # m/s upward velocity kick on a bare tap (charge = 0)
JUMP_MAX_IMPULSE = 6.0    # m/s upward velocity kick at full charge
CROUCH_LIFT_HEIGHT = 0.15  # meters the pinned foot anchors drop at full charge, sinking the figure


def _to_screen(x: float, y: float) -> tuple[float, float]:
    return ORIGIN_X + x * PIXELS_PER_METER, ORIGIN_Y - y * PIXELS_PER_METER


def _to_world(sx: float, sy: float) -> Vec2:
    return Vec2((sx - ORIGIN_X) / PIXELS_PER_METER, (ORIGIN_Y - sy) / PIXELS_PER_METER)


class Renderer:
    def __init__(self, world: World, head_index: int | None = None, fps: int = 60,
                 lift_anchor_index: int | None = None,
                 balance_controller: HipBalanceController | None = None,
                 hip_joint_indices: list[int] | None = None,
                 foot_anchor_indices: list[int] | None = None,
                 master: tk.Misc | None = None, root_index: int = 0):
        self.world = world
        self.head_index = head_index
        self.root_index = root_index
        self.frame_delay_ms = int(1000 / fps)
        self.paused = False
        self.balance_controller = balance_controller
        self.is_toplevel = master is not None
        self.initial_states = snapshot_body_states(world.bodies)

        self.lift_anchor_index = lift_anchor_index
        self.space_held = False
        self._side_offset = 0.0  # grows while space is held, frozen (not reversed) on release
        if lift_anchor_index is not None:
            self._anchor_rest_pos = world.bodies[lift_anchor_index].pos

        self.hip_joint_indices = hip_joint_indices or []
        self.dragging = False
        self.drag_anchor_world: Vec2 | None = None
        self.drag_current_world: Vec2 | None = None

        # foot_anchor_indices names the pins' static anchor BODIES (world.pin_point's
        # return value); resolve those to the actual pin JOINTS here (each anchor body
        # is body_a of exactly one joint) so drag-release can disable them by index.
        foot_anchor_indices = foot_anchor_indices or []
        self.foot_pin_joint_indices = [i for i, j in enumerate(world.joints) if j.body_a in foot_anchor_indices]

        # crouch: while charging, the pinned anchors themselves drop, and since the
        # legs are motored stiffly enough to hold their shape they get dragged down
        # rigidly along with it, sinking the torso — cruder than a real knee-bend
        # crouch, but robust. (Scaling hip/knee target angles directly barely moved
        # anything: the knee's target quickly overshoots its own angle limit, and the
        # ankle pin's own motor fights the change back.)
        self._foot_anchor_rest_pos = {i: Vec2(world.bodies[i].pos.x, world.bodies[i].pos.y)
                                       for i in foot_anchor_indices}

        self.jump_charging = False
        self.jump_charge = 0.0  # 0..1

        self.root = tk.Toplevel(master) if master is not None else tk.Tk()
        self.root.title("Stick Figure")

        self.play_pause_button = tk.Button(self.root, text="Pause", width=8, command=self.toggle_paused)
        self.play_pause_button.pack(anchor="w", padx=4, pady=4)

        self.canvas = tk.Canvas(self.root, width=WIDTH, height=HEIGHT, bg="white")
        self.canvas.pack()

        self.canvas.bind("<ButtonPress-1>", self._on_drag_start)
        self.canvas.bind("<B1-Motion>", self._on_drag_motion)
        self.canvas.bind("<ButtonRelease-1>", self._on_drag_end)

        self.root.bind("<KeyPress-space>", self._on_space_press)
        self.root.bind("<KeyRelease-space>", self._on_space_release)
        self.root.bind("<KeyPress-Up>", self._on_jump_press)
        self.root.bind("<KeyRelease-Up>", self._on_jump_release)
        self.root.focus_set()

    def toggle_paused(self):
        self.paused = not self.paused
        self.play_pause_button.config(text="Play" if self.paused else "Pause")

    def reset_pose(self):
        """A warm start: puts the root body back where it started, then rebuilds
        everyone else's pose from the CURRENT target_angle of every joint (rather than
        replaying the frozen build-time geometry), so there's zero motor error the
        instant the simulation resumes."""
        self.jump_charging = False
        self.jump_charge = 0.0
        self._restore_foot_anchors()

        restore_body_states([self.world.bodies[self.root_index]], [self.initial_states[self.root_index]])
        apply_rest_pose_from_targets(self.world, self.root_index)

        for joint_index in self.foot_pin_joint_indices:
            self.world.joints[joint_index].enabled = True

        if self.lift_anchor_index is not None:
            self._anchor_rest_pos = self.world.bodies[self.lift_anchor_index].pos
        self._side_offset = 0.0

        self._draw()

    def _on_space_press(self, event):
        self.space_held = True

    def _on_space_release(self, event):
        self.space_held = False

    def _on_jump_press(self, event):
        if not self.jump_charging:
            self.jump_charging = True
            self.jump_charge = 0.0

    def _on_jump_release(self, event):
        """Bugaboo the Flea-style jump: the longer Up was held, the bigger the launch.
        Unpins the feet (same as a drag release) so the figure is actually free to
        leave the ground, then gives every body the same upward velocity kick — a
        cartoon-simple launch rather than a torque-driven leg extension."""
        if not self.jump_charging:
            return
        self.jump_charging = False

        impulse = JUMP_MIN_IMPULSE + (JUMP_MAX_IMPULSE - JUMP_MIN_IMPULSE) * self.jump_charge
        self.jump_charge = 0.0
        self._restore_foot_anchors()
        self._unpin_feet()

        kick = Vec2(0.0, impulse)
        for body in self.world.bodies:
            if body.inv_mass != 0.0:
                body.vel = body.vel + kick

    def _restore_foot_anchors(self):
        for anchor_index, rest_pos in self._foot_anchor_rest_pos.items():
            self.world.bodies[anchor_index].pos = Vec2(rest_pos.x, rest_pos.y)

    def _update_jump_charge(self):
        if not self.jump_charging:
            return
        self.jump_charge = min(1.0, self.jump_charge + self.world.dt / JUMP_CHARGE_TIME)
        # the legs are motored stiffly enough to hold their shape, so dropping the
        # anchor they're pinned to drags the whole figure down with it (rigidly,
        # rather than folding at the knee) -- a crude but effective crouch.
        drop = self.jump_charge * CROUCH_LIFT_HEIGHT
        for anchor_index, rest_pos in self._foot_anchor_rest_pos.items():
            self.world.bodies[anchor_index].pos = Vec2(rest_pos.x, rest_pos.y - drop)

    def _on_drag_start(self, event):
        self.dragging = True
        self.drag_anchor_world = _to_world(event.x, event.y)
        self.drag_current_world = self.drag_anchor_world

    def _on_drag_motion(self, event):
        if self.dragging:
            self.drag_current_world = _to_world(event.x, event.y)

    def _on_drag_end(self, event):
        self.dragging = False
        self._unpin_feet()

    def _unpin_feet(self):
        """Drops the foot pins (point constraint + ankle motor both go dead) so the
        feet fall free under gravity/ground-contact/momentum instead of staying
        nailed and motored to the ground — the payoff for the drag-spring pull."""
        for joint_index in self.foot_pin_joint_indices:
            self.world.joints[joint_index].enabled = False

    def _apply_hip_drag_force(self):
        """Left-drag spring: anchored at the mouse-down point, free end follows the
        cursor, so the pull grows with how far you've dragged (Hooke's law). Applied
        at each hip joint's shared anchor point, on both bodies it connects (torso and
        thigh), the same way any external force lands on a body — see
        solver.apply_force_at_point."""
        if not self.dragging or not self.hip_joint_indices:
            return

        delta = self.drag_current_world - self.drag_anchor_world
        dist = delta.length()
        if dist == 0.0:
            return

        magnitude = min(DRAG_STIFFNESS * dist, MAX_DRAG_FORCE)
        force = delta * (magnitude / dist)

        for joint_index in self.hip_joint_indices:
            joint = self.world.joints[joint_index]
            a = self.world.bodies[joint.body_a]
            b = self.world.bodies[joint.body_b]
            apply_force_at_point(a, a.world_point(joint.local_anchor_a), force, self.world.dt)
            apply_force_at_point(b, b.world_point(joint.local_anchor_b), force, self.world.dt)

    def _update_lift_anchor(self):
        if self.lift_anchor_index is None:
            return

        if self.space_held:
            self._side_offset += STEP_RATE * self.world.dt

        anchor = self.world.bodies[self.lift_anchor_index]
        target_x = self._anchor_rest_pos.x + self._side_offset
        target_y = self._anchor_rest_pos.y + (LIFT_HEIGHT if self.space_held else 0.0)
        target = Vec2(target_x, target_y)

        delta = target - anchor.pos
        dist = delta.length()
        max_step = LIFT_SPEED * self.world.dt
        if dist <= max_step or dist == 0.0:
            anchor.pos = target
        else:
            anchor.pos = anchor.pos + delta * (max_step / dist)

    def _draw(self):
        self.canvas.delete("all")
        self.canvas.create_line(0, ORIGIN_Y, WIDTH, ORIGIN_Y, fill="gray")

        for i, body in enumerate(self.world.bodies):
            a = body.world_point(Vec2(-body.half_length, 0.0))
            b = body.world_point(Vec2(body.half_length, 0.0))

            if i == self.head_index:
                cx, cy = _to_screen(body.pos.x, body.pos.y)
                r = body.half_length * PIXELS_PER_METER
                self.canvas.create_oval(cx - r, cy - r, cx + r, cy + r, outline="black", width=2)
            else:
                x0, y0 = _to_screen(a.x, a.y)
                x1, y1 = _to_screen(b.x, b.y)
                self.canvas.create_line(x0, y0, x1, y1, fill="black", width=4, capstyle=tk.ROUND)

        if self.dragging and self.drag_anchor_world is not None:
            ax, ay = _to_screen(self.drag_anchor_world.x, self.drag_anchor_world.y)
            cx, cy = _to_screen(self.drag_current_world.x, self.drag_current_world.y)
            self.canvas.create_line(ax, ay, cx, cy, fill="orange", width=2, dash=(4, 2))
            self.canvas.create_oval(ax - 4, ay - 4, ax + 4, ay + 4, fill="orange", outline="")

        if self.jump_charging:
            bar_x, bar_y, bar_w, bar_h = 20, HEIGHT - 30, 150, 14
            self.canvas.create_rectangle(bar_x, bar_y, bar_x + bar_w, bar_y + bar_h, outline="black")
            self.canvas.create_rectangle(bar_x, bar_y, bar_x + bar_w * self.jump_charge, bar_y + bar_h,
                                          fill="red", outline="")

    def _tick(self):
        if not self.paused:
            self._update_lift_anchor()
            self._update_jump_charge()
            self._apply_hip_drag_force()
            if self.balance_controller is not None:
                self.balance_controller.apply(self.world)
            step(self.world)
            self._draw()
        self.root.after(self.frame_delay_ms, self._tick)

    def run(self):
        self._tick()
        if not self.is_toplevel:
            self.root.mainloop()
        # as a Toplevel, the owner's mainloop (already running) drives the event loop
