from world import World


class HipBalanceController:
    """Keeps the torso upright by nudging the hip joints' target angle (a "hip strategy").

    This sits on top of the hips' own PD motors: it doesn't apply torque directly, it
    shifts what angle those motors are trying to hold, based on how far the torso has
    tilted from upright. Both hips get the same correction (this only corrects
    forward/backward sway, not left/right).
    """

    def __init__(self, world: World, torso_index: int, hip_joint_indices: list[int], upright_angle: float,
                 stiffness: float = 2.0, damping: float = 0.4, max_correction: float = 0.6):
        self.torso_index = torso_index
        self.hip_joint_indices = hip_joint_indices
        self.upright_angle = upright_angle
        self.stiffness = stiffness
        self.damping = damping
        self.max_correction = max_correction

        self.base_target_angles = [world.joints[i].target_angle for i in hip_joint_indices]

    def apply(self, world: World):
        torso = world.bodies[self.torso_index]
        error = self.upright_angle - torso.angle

        correction = self.stiffness * error - self.damping * torso.ang_vel
        correction = max(-self.max_correction, min(self.max_correction, correction))

        for joint_index, base_angle in zip(self.hip_joint_indices, self.base_target_angles):
            world.joints[joint_index].target_angle = base_angle + correction
