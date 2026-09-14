from __future__ import annotations
from dataclasses import dataclass
import math


@dataclass
class Vec2:
    x: float = 0.0
    y: float = 0.0

    def __add__(self, other: Vec2) -> Vec2:
        return Vec2(self.x + other.x, self.y + other.y)

    def __sub__(self, other: Vec2) -> Vec2:
        return Vec2(self.x - other.x, self.y - other.y)

    def __mul__(self, scalar: float) -> Vec2:
        return Vec2(self.x * scalar, self.y * scalar)

    __rmul__ = __mul__

    def length(self) -> float:
        return math.hypot(self.x, self.y)

    def rotated(self, angle: float) -> Vec2:
        c, s = math.cos(angle), math.sin(angle)
        return Vec2(self.x * c - self.y * s, self.x * s + self.y * c)
