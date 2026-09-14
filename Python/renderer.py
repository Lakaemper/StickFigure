import tkinter as tk
from vec2 import Vec2
from world import World, snapshot_body_states, restore_body_states, apply_rest_pose_from_targets
from solver import step
from balance import HipBalanceController

WIDTH, HEIGHT = 800, 600
PIXELS_PER_METER = 150
ORIGIN_X = WIDTH // 2
ORIGIN_Y = HEIGHT - 60

LIFT_HEIGHT = 0.3   # meters the guided anchor rises while space is held
STEP_RATE = 0.5     # meters/second the sideways target recedes while space is held
LIFT_SPEED = 0.8    # meters/second the anchor itself travels toward its target


def _to_screen(x: float, y: float) -> tuple[float, float]:
    return ORIGIN_X + x * PIXELS_PER_METER, ORIGIN_Y - y * PIXELS_PER_METER


class Renderer:
    def __init__(self, world: World, head_index: int | None = None, fps: int = 60,
                 lift_anchor_index: int | None = None,
                 balance_controller: HipBalanceController | None = None,
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

        self.root = tk.Toplevel(master) if master is not None else tk.Tk()
        self.root.title("Stick Figure")

        self.play_pause_button = tk.Button(self.root, text="Pause", width=8, command=self.toggle_paused)
        self.play_pause_button.pack(anchor="w", padx=4, pady=4)

        self.canvas = tk.Canvas(self.root, width=WIDTH, height=HEIGHT, bg="white")
        self.canvas.pack()

        self.root.bind("<KeyPress-space>", self._on_space_press)
        self.root.bind("<KeyRelease-space>", self._on_space_release)
        self.root.focus_set()

    def toggle_paused(self):
        self.paused = not self.paused
        self.play_pause_button.config(text="Play" if self.paused else "Pause")

    def reset_pose(self):
        """A warm start: puts the root body back where it started, then rebuilds
        everyone else's pose from the CURRENT target_angle of every joint (rather than
        replaying the frozen build-time geometry), so there's zero motor error the
        instant the simulation resumes."""
        restore_body_states([self.world.bodies[self.root_index]], [self.initial_states[self.root_index]])
        apply_rest_pose_from_targets(self.world, self.root_index)

        if self.lift_anchor_index is not None:
            self._anchor_rest_pos = self.world.bodies[self.lift_anchor_index].pos
        self._side_offset = 0.0

        self._draw()

    def _on_space_press(self, event):
        self.space_held = True

    def _on_space_release(self, event):
        self.space_held = False

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

    def _tick(self):
        if not self.paused:
            self._update_lift_anchor()
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
