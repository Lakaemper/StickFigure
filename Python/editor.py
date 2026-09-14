import copy
import math
import tkinter as tk
from vec2 import Vec2
from world import World, snapshot_body_states, restore_body_states, apply_rest_pose_from_targets
from renderer import Renderer
from config_loader import load_stick_figure, save_stick_figure

JOINT_FIELD_NAMES = (
    "min_angle", "max_angle", "target_angle",
    "motor_stiffness", "motor_damping", "max_motor_torque", "motor_enabled",
)

SCALE = 1.5  # editor-only zoom of the figure; window size and main.py's renderer are untouched

WIDTH = 800
HEIGHT = 600
PIXELS_PER_METER = int(150 * SCALE)
ORIGIN_X = WIDTH // 2
ORIGIN_Y = HEIGHT - 60

JOINT_MARKER_RADIUS_PX = round(6 * SCALE)
JOINT_PICK_RADIUS_PX = round(12 * SCALE)
FAN_RADIUS_PX = round(18 * SCALE)  # how far apart to spread joints sharing an anchor point
LABEL_FONT = ("Segoe UI", round(8 * SCALE))
LIMB_LINE_WIDTH = round(4 * SCALE)


def _to_screen(x: float, y: float) -> tuple[float, float]:
    return ORIGIN_X + x * PIXELS_PER_METER, ORIGIN_Y - y * PIXELS_PER_METER

FIELDS = [
    ("min_angle_deg", "Min angle (deg)"),
    ("max_angle_deg", "Max angle (deg)"),
    ("target_angle_deg", "Target angle (deg)"),
    ("stiffness", "Stiffness"),
    ("damping", "Damping"),
    ("max_torque", "Max torque"),
]


class Editor:
    """Shows the figure with clickable joints: edit angle limits/motor gains, save the
    result back to config.json, reset the pose, or Play it in a separate live renderer
    window (a Renderer Toplevel, running on a snapshot of the current, possibly-unsaved
    edits — this editor's own view never simulates, it's just for picking and editing).

    Joints that share the same anchor point (e.g. both shoulders sit at the top of the
    torso) are fanned out into a small circle so every one of them stays visible and
    individually clickable, with a thin line back to their true anchor point.
    """

    def __init__(self, config_path: str = "config.json"):
        self.config_path = config_path
        self.selected_joint = None
        self.renderer: Renderer | None = None

        self.root = tk.Tk()
        self.root.title("Stick Figure Editor")

        self._load_world()

        toolbar = tk.Frame(self.root)
        toolbar.pack(side="top", fill="x")
        tk.Button(toolbar, text="Apply", width=8, command=self._apply_fields).pack(
            side="left", padx=4, pady=4)
        tk.Button(toolbar, text="Play/Pause", width=10, command=self._toggle_renderer_play).pack(
            side="left", padx=4, pady=4)
        tk.Button(toolbar, text="Reset", width=8, command=self._reset).pack(side="left", padx=4, pady=4)
        tk.Button(toolbar, text="Save to config.json", command=self._save).pack(side="left", padx=4, pady=4)

        body = tk.Frame(self.root)
        body.pack(side="top", fill="both", expand=True)

        self.canvas = tk.Canvas(body, width=WIDTH, height=HEIGHT, bg="white")
        self.canvas.pack(side="left")
        self.canvas.bind("<Button-1>", self._on_canvas_click)

        self.panel = tk.Frame(body, width=220, padx=8, pady=8)
        self.panel.pack(side="left", fill="y")
        self._build_panel()

        self._draw()

    def _load_world(self):
        """Builds the world fresh from config.json. Only called at startup — Reset
        does NOT call this, so it doesn't touch whatever you've edited since."""
        self.world = World()
        self.ids = load_stick_figure(self.world, self.config_path)
        self.body_name_by_index = {idx: name for name, idx in self.ids.items()
                                    if not name.startswith("foot_anchor_")}
        self.initial_body_states = snapshot_body_states(self.world.bodies)
        self.selected_joint = None

    def _build_panel(self):
        self.selected_label = tk.Label(self.panel, text="No joint selected", font=("Segoe UI", 10, "bold"))
        self.selected_label.pack(anchor="w", pady=(0, 8))

        self.fields: dict[str, tk.Entry] = {}
        for key, label in FIELDS:
            row = tk.Frame(self.panel)
            row.pack(fill="x", pady=2)
            tk.Label(row, text=label, width=16, anchor="w").pack(side="left")
            entry = tk.Entry(row, width=10)
            entry.pack(side="left")
            self.fields[key] = entry

        self.motor_enabled_var = tk.BooleanVar(value=False)
        tk.Checkbutton(self.panel, text="Motor enabled", variable=self.motor_enabled_var).pack(anchor="w", pady=6)

        self.status_label = tk.Label(self.panel, text="", fg="green")
        self.status_label.pack(anchor="w", pady=(8, 0))

    # -- joint picking / editing --------------------------------------------------

    def _joint_anchor_screen_pos(self, joint) -> tuple[float, float]:
        """The joint's true position in screen space (before fanning out)."""
        a = self.world.bodies[joint.body_a]
        world_point = a.world_point(joint.local_anchor_a)
        return _to_screen(world_point.x, world_point.y)

    def _joint_display_positions(self) -> list[tuple[float, float]]:
        """One display position per joint: fanned out into a small circle for any
        group of joints that share the same true anchor point."""
        anchors = [self._joint_anchor_screen_pos(joint) for joint in self.world.joints]

        groups: dict[tuple[int, int], list[int]] = {}
        for i, (x, y) in enumerate(anchors):
            groups.setdefault((round(x), round(y)), []).append(i)

        positions = list(anchors)
        for (cx, cy), indices in groups.items():
            if len(indices) == 1:
                continue
            for k, joint_index in enumerate(indices):
                theta = 2.0 * math.pi * k / len(indices)
                positions[joint_index] = (cx + FAN_RADIUS_PX * math.cos(theta),
                                           cy + FAN_RADIUS_PX * math.sin(theta))
        return positions

    def _on_canvas_click(self, event):
        positions = self._joint_display_positions()

        best_index, best_dist = None, JOINT_PICK_RADIUS_PX
        for i, (sx, sy) in enumerate(positions):
            dist = math.hypot(sx - event.x, sy - event.y)
            if dist < best_dist:
                best_dist = dist
                best_index = i

        if best_index is not None:
            self._select_joint(best_index)

    def _select_joint(self, joint_index: int):
        self.selected_joint = joint_index
        joint = self.world.joints[joint_index]
        name_a = self.body_name_by_index.get(joint.body_a, "?")
        name_b = self.body_name_by_index.get(joint.body_b, "?")
        self.selected_label.config(text=f"{name_a} <-> {name_b}")
        self.status_label.config(text="")

        def set_entry(key: str, value: float):
            entry = self.fields[key]
            entry.delete(0, tk.END)
            entry.insert(0, f"{value:.2f}")

        set_entry("min_angle_deg", math.degrees(joint.min_angle) if math.isfinite(joint.min_angle) else 180.0)
        set_entry("max_angle_deg", math.degrees(joint.max_angle) if math.isfinite(joint.max_angle) else 180.0)
        set_entry("target_angle_deg", math.degrees(joint.target_angle))
        set_entry("stiffness", joint.motor_stiffness)
        set_entry("damping", joint.motor_damping)
        set_entry("max_torque", joint.max_motor_torque)
        self.motor_enabled_var.set(joint.motor_enabled)

    def _apply_fields(self):
        """Commits the selected joint's edited fields, recomputes this editor's own
        pose to match (so its picture doesn't go stale), then pushes every joint's
        current values into the live renderer (if one is open) and resets its pose."""
        if self.selected_joint is not None:
            joint = self.world.joints[self.selected_joint]
            try:
                joint.min_angle = math.radians(float(self.fields["min_angle_deg"].get()))
                joint.max_angle = math.radians(float(self.fields["max_angle_deg"].get()))
                joint.target_angle = math.radians(float(self.fields["target_angle_deg"].get()))
                joint.motor_stiffness = float(self.fields["stiffness"].get())
                joint.motor_damping = float(self.fields["damping"].get())
                joint.max_motor_torque = float(self.fields["max_torque"].get())
                joint.motor_enabled = self.motor_enabled_var.get()
            except ValueError:
                self.status_label.config(text="Invalid number", fg="red")
                return

        apply_rest_pose_from_targets(self.world, self.ids["torso"])
        self._sync_renderer_joints()
        self.status_label.config(text="Applied", fg="green")
        self._draw()

    # -- toolbar actions ------------------------------------------------------------

    def _renderer_is_open(self) -> bool:
        return self.renderer is not None and self.renderer.root.winfo_exists()

    def _sync_renderer_joints(self):
        """Copies every joint's current values into the open renderer's world, then
        resets its pose — this is what "Apply" means when a renderer is showing."""
        if not self._renderer_is_open():
            return

        for src, dst in zip(self.world.joints, self.renderer.world.joints):
            for name in JOINT_FIELD_NAMES:
                setattr(dst, name, getattr(src, name))

        self.renderer.reset_pose()

    def _toggle_renderer_play(self):
        """Opens a live Renderer window (playing) if none is open yet, otherwise just
        toggles play/pause on the one that's already there."""
        if self._renderer_is_open():
            self.renderer.toggle_paused()
            self.renderer.root.lift()
            self.renderer.root.focus_set()
            return

        world_copy = copy.deepcopy(self.world)
        self.renderer = Renderer(
            world_copy, head_index=self.ids.get("head"),
            lift_anchor_index=self.ids.get("foot_anchor_r"),
            master=self.root, root_index=self.ids["torso"])
        self.renderer.run()

    def _reset(self):
        """A warm start: puts the torso back at its initial position/angle, then
        rebuilds everyone else from the CURRENT target_angle of every joint (not the
        frozen build-time geometry) — here and in the live renderer if one is open.
        Does NOT reload config.json or touch world.joints, so any edits you've applied
        (angle limits, motor gains, ...) are kept exactly as they are.
        """
        root_index = self.ids["torso"]
        restore_body_states([self.world.bodies[root_index]], [self.initial_body_states[root_index]])
        apply_rest_pose_from_targets(self.world, root_index)
        self._sync_renderer_joints()
        self.status_label.config(text="Reset to initial position", fg="blue")
        self._draw()

    def _save(self):
        save_stick_figure(self.world, self.ids, self.config_path)
        self.status_label.config(text="Saved to config.json", fg="green")

    # -- drawing ------------------------------------------------------------------

    def _draw(self):
        self.canvas.delete("all")
        self.canvas.create_line(0, ORIGIN_Y, WIDTH, ORIGIN_Y, fill="gray")

        head_index = self.ids.get("head")
        for i, body in enumerate(self.world.bodies):
            if body.half_length == 0.0:
                continue  # a pin's static anchor point, nothing to draw as a limb

            if i == head_index:
                cx, cy = _to_screen(body.pos.x, body.pos.y)
                r = body.half_length * PIXELS_PER_METER
                self.canvas.create_oval(cx - r, cy - r, cx + r, cy + r, outline="black", width=round(2 * SCALE))
            else:
                a = body.world_point(Vec2(-body.half_length, 0.0))
                b = body.world_point(Vec2(body.half_length, 0.0))
                x0, y0 = _to_screen(a.x, a.y)
                x1, y1 = _to_screen(b.x, b.y)
                self.canvas.create_line(x0, y0, x1, y1, fill="black", width=LIMB_LINE_WIDTH, capstyle=tk.ROUND)

            name = self.body_name_by_index.get(i)
            if name:
                lx, ly = _to_screen(body.pos.x, body.pos.y)
                self.canvas.create_text(lx + 8, ly, text=name, anchor="w", fill="blue", font=LABEL_FONT)

        anchors = [self._joint_anchor_screen_pos(joint) for joint in self.world.joints]
        positions = self._joint_display_positions()

        for i in range(len(self.world.joints)):
            sx, sy = positions[i]
            ax, ay = anchors[i]
            if (sx, sy) != (ax, ay):
                self.canvas.create_line(ax, ay, sx, sy, fill="black")

            outline = "red" if i == self.selected_joint else "green"
            r = JOINT_MARKER_RADIUS_PX
            self.canvas.create_oval(sx - r, sy - r, sx + r, sy + r, outline=outline,
                                     width=round(2 * SCALE))

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    Editor().run()
