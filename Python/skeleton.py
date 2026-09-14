import math
from vec2 import Vec2
from body import Body
from joint import Joint
from world import World

DOWN = -math.pi / 2  # body angle where local +x (its "front" end) points straight down

MOTOR_STIFFNESS = 20.0
MOTOR_DAMPING = 4.0
MOTOR_MAX_TORQUE = 15.0

# the ankle, knee and hip carry the whole body's toppling torque while standing,
# not just one limb's weight, so they need much stronger gains than the arm/neck
# motors above, or they buckle under the load before the ankle can correct anything.
LEG_STIFFNESS = 400.0
LEG_DAMPING = 60.0
LEG_MAX_TORQUE = 120.0


def _segment(pos: Vec2, angle: float, half_length: float, mass: float) -> Body:
    inv_mass = 1.0 / mass
    inv_inertia = 1.0 / (mass * (2.0 * half_length) ** 2 / 12.0)  # thin rod about its center
    return Body(pos=pos, angle=angle, inv_mass=inv_mass, inv_inertia=inv_inertia, half_length=half_length)


def _place(attach: Vec2, angle: float, half_length: float, mass: float, attach_top: bool) -> Body:
    """Creates a body at the given angle so that its top (or bottom) end sits at `attach`."""
    local_attach = Vec2(-half_length if attach_top else half_length, 0.0)
    offset = local_attach.rotated(angle)
    return _segment(attach - offset, angle, half_length, mass)


def build_stick_figure(world: World, arm_spread_deg: float = 45.0, thigh_spread_deg: float = 30.0,
                        motors_on: bool = True, pin_feet: bool = True) -> dict[str, int]:
    """Adds a standing stick figure (feet at y=0) to `world`. Returns body name -> index.

    arm_spread_deg / thigh_spread_deg is the total angle between the left and right
    upper arm / thigh; each side leans out by half of it, mirrored. Forearms and shins
    still hang straight down from the (now offset) elbow/knee.

    pin_feet nails the bottom of each shin to the ground point it starts at (free to
    rotate about that point) so the figure can't topple over sideways while you're
    just testing joints/motors.
    """

    shin_h, thigh_h, torso_h, head_h, arm_h = 0.22, 0.22, 0.30, 0.12, 0.18

    arm_lean = math.radians(arm_spread_deg) / 2.0
    thigh_lean = math.radians(thigh_spread_deg) / 2.0

    # shin hangs straight down (lean 0), so the hip height is whatever puts the
    # feet (bottom of the shins) at y=0 given the thighs' lean shortens their drop.
    hip_y = 2.0 * thigh_h * math.cos(thigh_lean) + 2.0 * shin_h
    hip_point = Vec2(0.0, hip_y)

    torso = _place(hip_point, DOWN, torso_h, mass=8.0, attach_top=False)
    shoulder_point = torso.world_point(Vec2(-torso_h, 0.0))
    head = _place(shoulder_point, DOWN, head_h, mass=2.0, attach_top=False)

    bodies = {"torso": torso, "head": head}

    for side, sign in (("l", 1.0), ("r", -1.0)):
        thigh = _place(hip_point, DOWN + sign * thigh_lean, thigh_h, mass=3.0, attach_top=True)
        knee_point = thigh.world_point(Vec2(thigh_h, 0.0))
        shin = _place(knee_point, DOWN, shin_h, mass=2.0, attach_top=True)

        upper_arm = _place(shoulder_point, DOWN + sign * arm_lean, arm_h, mass=1.5, attach_top=True)
        elbow_point = upper_arm.world_point(Vec2(arm_h, 0.0))
        lower_arm = _place(elbow_point, DOWN, arm_h, mass=1.0, attach_top=True)

        bodies[f"thigh_{side}"] = thigh
        bodies[f"shin_{side}"] = shin
        bodies[f"upper_arm_{side}"] = upper_arm
        bodies[f"lower_arm_{side}"] = lower_arm

    ids: dict[str, int] = {name: world.add_body(body) for name, body in bodies.items()}

    def link(name_a: str, top_a: bool, name_b: str, top_b: bool, min_angle: float, max_angle: float,
             stiffness: float = MOTOR_STIFFNESS, damping: float = MOTOR_DAMPING,
             max_torque: float = MOTOR_MAX_TORQUE) -> int:
        body_a = bodies[name_a]
        body_b = bodies[name_b]
        anchor_a = Vec2(-body_a.half_length if top_a else body_a.half_length, 0.0)
        anchor_b = Vec2(-body_b.half_length if top_b else body_b.half_length, 0.0)

        rest_angle = body_b.angle - body_a.angle
        joint = Joint(ids[name_a], ids[name_b], anchor_a, anchor_b, min_angle, max_angle)
        if motors_on:
            joint.motor_enabled = True
            joint.target_angle = rest_angle
            joint.motor_stiffness = stiffness
            joint.motor_damping = damping
            joint.max_motor_torque = max_torque
        return world.add_joint(joint)

    link("torso", True, "head", False, -0.4, 0.4)

    # the leaning thigh puts the (still-vertical) shin at a slight negative angle
    # relative to it, so the knee's lower limit must allow for that rest pose.
    knee_min_angle = min(0.0, -thigh_lean - 0.05)

    def mirrored(min_angle: float, max_angle: float, side: str) -> tuple[float, float]:
        # left/right targets are mirrored (opposite sign lean), so their angle limits
        # must mirror too, or a valid target on one side can fall outside the range
        # copied straight from the other side (this bit us: the elbow's target of
        # +22.5 degrees fell outside a range that was only ever checked against -22.5).
        return (min_angle, max_angle) if side == "l" else (-max_angle, -min_angle)

    for side in ("l", "r"):
        shoulder_min, shoulder_max = mirrored(-3.0, 1.0, side)
        elbow_min, elbow_max = mirrored(-2.5, 0.1, side)
        hip_min, hip_max = mirrored(-2.3, 2.3, side)
        knee_min, knee_max = mirrored(knee_min_angle, 2.3, side)

        link("torso", True, f"upper_arm_{side}", True, shoulder_min, shoulder_max)
        link(f"upper_arm_{side}", False, f"lower_arm_{side}", True, elbow_min, elbow_max)
        ids[f"joint_hip_{side}"] = link(
            "torso", False, f"thigh_{side}", True, hip_min, hip_max,
            stiffness=LEG_STIFFNESS, damping=LEG_DAMPING, max_torque=LEG_MAX_TORQUE)
        link(f"thigh_{side}", False, f"shin_{side}", True, knee_min, knee_max,
             stiffness=LEG_STIFFNESS, damping=LEG_DAMPING, max_torque=LEG_MAX_TORQUE)

    if pin_feet:
        for side in ("l", "r"):
            ids[f"foot_anchor_{side}"] = world.pin_point(
                ids[f"shin_{side}"], Vec2(shin_h, 0.0), motor_enabled=True,
                motor_stiffness=LEG_STIFFNESS, motor_damping=LEG_DAMPING,
                max_motor_torque=LEG_MAX_TORQUE)

    return ids
