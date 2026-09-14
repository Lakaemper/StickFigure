from __future__ import annotations
from dataclasses import dataclass
from vec2 import Vec2


@dataclass
class Joint:
    body_a: int
    body_b: int
    local_anchor_a: Vec2
    local_anchor_b: Vec2

    min_angle: float = -3.14159
    max_angle: float = 3.14159

    # motor (active control)
    motor_enabled: bool = False
    target_angle: float = 0.0
    max_motor_torque: float = 0.0
    motor_stiffness: float = 0.0   # kp
    motor_damping: float = 0.0     # kd
