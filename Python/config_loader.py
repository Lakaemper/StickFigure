import json
import math
from vec2 import Vec2
from body import Body
from joint import Joint
from world import World


def _apply_motor(joint, motor_config: dict):
    joint.motor_enabled = motor_config["enabled"]
    joint.target_angle = math.radians(motor_config["target_angle_deg"])
    joint.motor_stiffness = motor_config["stiffness"]
    joint.motor_damping = motor_config["damping"]
    joint.max_motor_torque = motor_config["max_torque"]


def load_stick_figure(world: World, config_path: str = "config.json") -> dict[str, int]:
    """Builds bodies/joints/pins into `world` straight from a JSON config.

    This is a data-driven alternative to skeleton.build_stick_figure(): no procedural
    layout math, just the exact pos/angle/mass/anchors/motor gains the file specifies.
    Returns body name -> index (plus "foot_anchor_<side>" for pinned feet).
    """
    with open(config_path) as f:
        config = json.load(f)

    ids: dict[str, int] = {}
    for name, spec in config["bodies"].items():
        pos = Vec2(spec["pos"][0], spec["pos"][1])
        angle = math.radians(spec["angle_deg"])
        half_length = spec["half_length"]
        mass = spec["mass"]

        inv_mass = 1.0 / mass
        inv_inertia = 1.0 / (mass * (2.0 * half_length) ** 2 / 12.0)
        body = Body(pos=pos, angle=angle, inv_mass=inv_mass, inv_inertia=inv_inertia, half_length=half_length)
        ids[name] = world.add_body(body)

    for spec in config["joints"]:
        anchor_a = Vec2(spec["anchor_a"][0], spec["anchor_a"][1])
        anchor_b = Vec2(spec["anchor_b"][0], spec["anchor_b"][1])
        min_angle = math.radians(spec["min_angle_deg"]) if spec["min_angle_deg"] is not None else -math.inf
        max_angle = math.radians(spec["max_angle_deg"]) if spec["max_angle_deg"] is not None else math.inf

        joint = Joint(ids[spec["body_a"]], ids[spec["body_b"]], anchor_a, anchor_b, min_angle, max_angle)
        _apply_motor(joint, spec["motor"])
        world.add_joint(joint)

    for spec in config["pins"]:
        anchor = Vec2(spec["anchor"][0], spec["anchor"][1])
        world_position = Vec2(spec["world_position"][0], spec["world_position"][1])
        motor = spec["motor"]

        anchor_index = world.pin_point(
            ids[spec["body"]], anchor, world_position,
            motor_enabled=motor["enabled"],
            target_angle=math.radians(motor["target_angle_deg"]),
            motor_stiffness=motor["stiffness"],
            motor_damping=motor["damping"],
            max_motor_torque=motor["max_torque"])
        ids[f"foot_anchor_{spec['body'].rsplit('_', 1)[-1]}"] = anchor_index

    return ids


def _motor_dict(joint) -> dict:
    return {
        "enabled": joint.motor_enabled,
        "target_angle_deg": round(math.degrees(joint.target_angle), 3),
        "stiffness": joint.motor_stiffness,
        "damping": joint.motor_damping,
        "max_torque": joint.max_motor_torque,
    }


def save_stick_figure(world: World, ids: dict[str, int], config_path: str = "config.json"):
    """Writes the world's current bodies/joints/pins back out, in load_stick_figure's format."""
    body_name_by_index = {idx: name for name, idx in ids.items() if not name.startswith("foot_anchor_")}

    bodies = {}
    for idx, body in enumerate(world.bodies):
        name = body_name_by_index.get(idx)
        if name is None:
            continue
        bodies[name] = {
            "pos": [round(body.pos.x, 6), round(body.pos.y, 6)],
            "angle_deg": round(math.degrees(body.angle), 3),
            "mass": round(1.0 / body.inv_mass, 6),
            "half_length": body.half_length,
        }

    joints = []
    pins = []
    for joint in world.joints:
        body_a = world.bodies[joint.body_a]
        is_pin = body_a.inv_mass == 0.0 and body_a.inv_inertia == 0.0

        if is_pin:
            pins.append({
                "body": body_name_by_index[joint.body_b],
                "anchor": [round(joint.local_anchor_b.x, 6), round(joint.local_anchor_b.y, 6)],
                "world_position": [round(body_a.pos.x, 6), round(body_a.pos.y, 6)],
                "motor": _motor_dict(joint),
            })
        else:
            joints.append({
                "body_a": body_name_by_index[joint.body_a],
                "anchor_a": [round(joint.local_anchor_a.x, 6), round(joint.local_anchor_a.y, 6)],
                "body_b": body_name_by_index[joint.body_b],
                "anchor_b": [round(joint.local_anchor_b.x, 6), round(joint.local_anchor_b.y, 6)],
                "min_angle_deg": round(math.degrees(joint.min_angle), 3) if math.isfinite(joint.min_angle) else None,
                "max_angle_deg": round(math.degrees(joint.max_angle), 3) if math.isfinite(joint.max_angle) else None,
                "motor": _motor_dict(joint),
            })

    config = {"bodies": bodies, "joints": joints, "pins": pins}
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)
