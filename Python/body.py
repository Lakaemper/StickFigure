from __future__ import annotations
from dataclasses import dataclass
from vec2 import Vec2


@dataclass
class Body:
    pos: Vec2
    angle: float = 0.0

    vel: Vec2 = None
    ang_vel: float = 0.0
    prev_pos: Vec2 = None   # for Verlet-style velocity derivation in a PBD solver
    prev_angle: float = None

    inv_mass: float = 1.0      # 0 = infinite mass (pinned body)
    inv_inertia: float = 1.0

    half_length: float = 0.5  # segment extends from -half_length to +half_length along local x
    radius: float = 0.05      # capsule thickness, for collision

    def __post_init__(self):
        if self.vel is None:
            self.vel = Vec2()
        if self.prev_pos is None:
            self.prev_pos = self.pos
        if self.prev_angle is None:
            self.prev_angle = self.angle

    def world_point(self, local: Vec2) -> Vec2:
        return self.pos + local.rotated(self.angle)
