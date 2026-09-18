package skeleton;

import skeleton.attachables.Motor;

// -----------------------------------------------------------------------------
// A "hip strategy" balance controller: an outer PD loop on top of the hip
// motors' own PD loops. Watches the torso's tilt away from upright and its
// angular velocity, and shifts every given hip motor's target angle by the
// same correction (their own base targets stay mirrored/distinct -- only the
// correction is shared), so both thighs swing forward/backward together to
// bring the torso back toward upright, instead of the hips holding a rigid,
// unresponsive target no matter how far the body has already tipped.
public class HipBalancer extends PhysicalUpgrades {

    private final Bone torso;
    private final Motor[] hipMotors;
    private final double[] baseTargetAngleDeg;
    private final double uprightAngleDeg;

    public double stiffness = 2.0;
    public double damping = 0.4;
    public double maxCorrectionDeg = 30.0;

    // -------------------------------------------------------------------------
    public HipBalancer(Bone torso, Motor[] hipMotors, double uprightAngleDeg) {
        this.torso = torso;
        this.hipMotors = hipMotors;
        this.uprightAngleDeg = uprightAngleDeg;

        baseTargetAngleDeg = new double[hipMotors.length];
        for (int i = 0; i < hipMotors.length; i++) {
            baseTargetAngleDeg[i] = hipMotors[i].targetAngleDeg;
        }
    }

    // -------------------------------------------------------------------------
    @Override
    public void process() {
        double torsoAngleDeg = Math.toDegrees(torso.angle());
        double torsoAngVelDeg = Math.toDegrees(torso.angularVelocity());

        double errorDeg = uprightAngleDeg - torsoAngleDeg;
        double correction = stiffness * errorDeg - damping * torsoAngVelDeg;
        correction = Math.max(-maxCorrectionDeg, Math.min(maxCorrectionDeg, correction));

        for (int i = 0; i < hipMotors.length; i++) {
            hipMotors[i].targetAngleDeg = baseTargetAngleDeg[i] + correction;
        }
    }
}
