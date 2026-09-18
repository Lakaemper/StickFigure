from vec2 import Vec2
from body import Body
from world import World

AXES = (Vec2(1.0, 0.0), Vec2(0.0, 1.0))


def _cross(a: Vec2, b: Vec2) -> float:
    return a.x * b.y - a.y * b.x


def apply_force_at_point(body: Body, world_point: Vec2, force: Vec2, dt: float):
    """Applies an external linear force to `body` at `world_point`, e.g. for a
    UI-driven interaction (a mouse-drag spring) rather than something internal to
    step()'s own substep loop. Deposits both the linear kick (force * inv_mass) and
    the torque from the point being offset from the body's center, the same way
    gravity/motor torque land on bodies elsewhere in this module."""
    if body.inv_mass == 0.0:
        return
    body.vel = body.vel + force * (body.inv_mass * dt)
    r = world_point - body.pos
    torque = r.x * force.y - r.y * force.x
    body.ang_vel += torque * body.inv_inertia * dt


def _apply_motor_torques(world: World):
    """Active control: a PD controller drives each motorized joint toward its target angle."""
    dt = world.dt
    for joint in world.joints:
        if not joint.enabled or not joint.motor_enabled:
            continue
        a = world.bodies[joint.body_a]
        b = world.bodies[joint.body_b]

        rel_angle = b.angle - a.angle
        rel_ang_vel = b.ang_vel - a.ang_vel

        torque = joint.motor_stiffness * (joint.target_angle - rel_angle) - joint.motor_damping * rel_ang_vel
        torque = max(-joint.max_motor_torque, min(joint.max_motor_torque, torque))

        a.ang_vel -= torque * a.inv_inertia * dt
        b.ang_vel += torque * b.inv_inertia * dt


def _integrate(world: World):
    dt = world.dt
    for body in world.bodies:
        if body.inv_mass == 0.0:
            continue
        body.vel = body.vel + world.gravity * dt
        body.prev_pos = body.pos
        body.prev_angle = body.angle
        body.pos = body.pos + body.vel * dt
        body.angle += body.ang_vel * dt


def _solve_point_constraint(a, ra: Vec2, b, rb: Vec2):
    """Move/rotate a and b so a's anchor point coincides with b's anchor point."""
    for axis in AXES:
        pa = a.pos + ra
        pb = b.pos + rb
        c = (pb.x - pa.x) * axis.x + (pb.y - pa.y) * axis.y  # gap along this axis
        if c == 0.0:
            continue

        cross_a = _cross(ra, axis)
        cross_b = _cross(rb, axis)
        w_a = a.inv_mass + a.inv_inertia * cross_a * cross_a
        w_b = b.inv_mass + b.inv_inertia * cross_b * cross_b
        w_sum = w_a + w_b
        if w_sum == 0.0:
            continue

        lagrange = c / w_sum

        a.pos = a.pos + axis * (lagrange * a.inv_mass)
        a.angle += lagrange * a.inv_inertia * cross_a

        b.pos = b.pos - axis * (lagrange * b.inv_mass)
        b.angle -= lagrange * b.inv_inertia * cross_b


def _solve_angle_limit(joint, a, b):
    rel_angle = b.angle - a.angle
    if joint.min_angle <= rel_angle <= joint.max_angle:
        return

    target = min(max(rel_angle, joint.min_angle), joint.max_angle)
    c = rel_angle - target
    w_sum = a.inv_inertia + b.inv_inertia
    if w_sum == 0.0:
        return

    a.angle += c * a.inv_inertia / w_sum
    b.angle -= c * b.inv_inertia / w_sum


def _solve_joints(world: World):
    for joint in world.joints:
        if not joint.enabled:
            continue
        a = world.bodies[joint.body_a]
        b = world.bodies[joint.body_b]
        ra = joint.local_anchor_a.rotated(a.angle)
        rb = joint.local_anchor_b.rotated(b.angle)
        _solve_point_constraint(a, ra, b, rb)
        _solve_angle_limit(joint, a, b)


def _solve_ground(world: World, ground_y: float = 0.0):
    up = Vec2(0.0, 1.0)
    for body in world.bodies:
        for local_x in (-body.half_length, body.half_length):
            end = body.world_point(Vec2(local_x, 0.0))
            penetration = ground_y - end.y
            if penetration <= 0.0:
                continue

            r = end - body.pos
            w = body.inv_mass + body.inv_inertia * _cross(r, up) ** 2
            if w == 0.0:
                continue

            lagrange = penetration / w
            body.pos = body.pos + up * (lagrange * body.inv_mass)
            body.angle += lagrange * body.inv_inertia * _cross(r, up)


def _update_velocities(world: World):
    dt = world.dt
    for body in world.bodies:
        if body.inv_mass == 0.0:
            continue
        body.vel = (body.pos - body.prev_pos) * (1.0 / dt)
        body.ang_vel = (body.angle - body.prev_angle) / dt


def _substep(world: World):
    _apply_motor_torques(world)
    _integrate(world)

    for _ in range(world.solver_iterations):
        _solve_joints(world)
        _solve_ground(world)

    _update_velocities(world)


def step(world: World, substeps: int = 8):
    """Advances the world by world.dt, split into `substeps` smaller steps.

    Motor PD gains act like stiff springs: applied with the full frame dt they can
    overshoot and blow up (angular velocity change per step scales with kp*dt), since
    the motor torque is an explicit, once-per-step velocity kick rather than something
    the position solver's iterations can smooth out. Substepping shrinks dt for that
    calculation without slowing the simulation down, which is the standard fix.
    """
    full_dt = world.dt
    world.dt = full_dt / substeps
    try:
        for _ in range(substeps):
            _substep(world)
    finally:
        world.dt = full_dt
