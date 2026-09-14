from __future__ import annotations
import math
from dataclasses import dataclass, field
from vec2 import Vec2
from body import Body
from joint import Joint


@dataclass
class World:
    bodies: list[Body] = field(default_factory=list)
    joints: list[Joint] = field(default_factory=list)
    gravity: Vec2 = field(default_factory=lambda: Vec2(0.0, -9.81))
    dt: float = 1.0 / 60.0
    solver_iterations: int = 8

    def add_body(self, body: Body) -> int:
        self.bodies.append(body)
        return len(self.bodies) - 1

    def add_joint(self, joint: Joint) -> int:
        self.joints.append(joint)
        return len(self.joints) - 1

    def pin_point(self, body_index: int, local_anchor: Vec2, world_position: Vec2 | None = None,
                  motor_enabled: bool = False, target_angle: float | None = None,
                  motor_stiffness: float = 0.0, motor_damping: float = 0.0,
                  max_motor_torque: float = 0.0) -> int:
        """Pins `local_anchor` on the given body to a fixed world point.

        The body stays free to rotate about that point (a nail/hinge into the world,
        not a full position+orientation lock) unless a motor is enabled, in which case
        a PD controller drives the body's angle toward `target_angle` (defaults to its
        current angle, i.e. "hold upright").

        Returns the index of the anchor body (itself has inv_mass=0/inv_inertia=0, so
        it never moves on its own) — keep it if you want to script the pin's position
        later, e.g. to guide a foot up and down.
        """
        body = self.bodies[body_index]
        if world_position is None:
            world_position = body.world_point(local_anchor)

        # half_length=0.0: this body has no shape, it's just a fixed point, so it draws
        # as nothing instead of a stray line at the Body dataclass's default length.
        anchor_body = Body(pos=world_position, angle=0.0, inv_mass=0.0, inv_inertia=0.0, half_length=0.0)
        anchor_index = self.add_body(anchor_body)

        joint = Joint(anchor_index, body_index, Vec2(0.0, 0.0), local_anchor, -math.inf, math.inf)
        if motor_enabled:
            joint.motor_enabled = True
            joint.target_angle = body.angle if target_angle is None else target_angle
            joint.motor_stiffness = motor_stiffness
            joint.motor_damping = motor_damping
            joint.max_motor_torque = max_motor_torque
        self.add_joint(joint)
        return anchor_index


def snapshot_body_states(bodies: list[Body]) -> list[tuple[Vec2, float]]:
    """Captures each body's (pos, angle) so it can be restored later via restore_body_states."""
    return [(Vec2(b.pos.x, b.pos.y), b.angle) for b in bodies]


def restore_body_states(bodies: list[Body], states: list[tuple[Vec2, float]]):
    """Puts every body back at a snapshotted (pos, angle) with zero velocity."""
    for body, (pos, angle) in zip(bodies, states):
        body.pos = Vec2(pos.x, pos.y)
        body.angle = angle
        body.vel = Vec2(0.0, 0.0)
        body.ang_vel = 0.0
        body.prev_pos = body.pos
        body.prev_angle = body.angle


def _is_pin_joint(world: World, joint: Joint) -> bool:
    a = world.bodies[joint.body_a]
    return a.inv_mass == 0.0 and a.inv_inertia == 0.0


def apply_rest_pose_from_targets(world: World, root_index: int):
    """Recomputes a zero-motor-error rest pose from the CURRENT target_angle of every
    structural joint, instead of replaying a frozen build-time snapshot.

    `root_index` (e.g. the torso) is assumed already correctly placed and is left
    untouched; every other body is repositioned outward from it, walking the joint
    graph, so that each joint's actual angle exactly equals its target_angle — this is
    what makes a "warm start" not immediately snap: the motor sees zero error the
    moment it's stepped, whatever target angles are currently set.

    Pin joints (e.g. a pinned foot) are handled separately afterwards: the pin's
    static anchor is moved to wherever its real body now ends up, and the pin's own
    target_angle updated to match that body's new angle, so it doesn't fight the
    newly recomputed pose either.
    """
    structural = [j for j in world.joints if not _is_pin_joint(world, j)]
    children_of: dict[int, list[Joint]] = {}
    for j in structural:
        children_of.setdefault(j.body_a, []).append(j)

    known = {root_index}
    queue = [root_index]
    while queue:
        parent_index = queue.pop(0)
        parent = world.bodies[parent_index]
        for j in children_of.get(parent_index, []):
            if j.body_b in known:
                continue
            child = world.bodies[j.body_b]
            child.angle = parent.angle + j.target_angle
            pivot = parent.world_point(j.local_anchor_a)
            child.pos = pivot - j.local_anchor_b.rotated(child.angle)
            child.vel = Vec2(0.0, 0.0)
            child.ang_vel = 0.0
            child.prev_pos = child.pos
            child.prev_angle = child.angle
            known.add(j.body_b)
            queue.append(j.body_b)

    for j in world.joints:
        if not _is_pin_joint(world, j):
            continue
        anchor_body = world.bodies[j.body_a]
        real_body = world.bodies[j.body_b]
        anchor_body.pos = real_body.world_point(j.local_anchor_b)
        j.target_angle = real_body.angle
