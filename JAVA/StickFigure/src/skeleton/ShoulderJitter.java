package skeleton;

import skeleton.attachables.Motor;

// -----------------------------------------------------------------------------
// Purely cosmetic, same mechanism as HandJitter but on the shoulders instead of
// the elbows -- kept as a separate upgrade (rather than layered onto the same
// elbow motors HandJitter already drives) since each PhysicalUpgrade currently
// overwrites a motor's targetAngleDeg outright; two upgrades sharing a motor
// would silently cancel each other rather than combine.
public class ShoulderJitter extends PhysicalUpgrades {

    private final Motor[] motors;
    private final double[] baseTargetAngleDeg;
    private final double[] phase;

    public double amplitudeDeg;
    public double phaseStepPerCall = 0.006; // radians of phase advance per process() call

    // -------------------------------------------------------------------------
    // amplitudeDeg is authored per-pose (Body reads it from the "PhysicalUpgrades"
    // JSON entry) -- a standing figure and one held rigid mid-flight want quite
    // different amounts of idle sway.
    public ShoulderJitter(Motor[] motors, double amplitudeDeg) {
        this.motors = motors;
        this.amplitudeDeg = amplitudeDeg;
        baseTargetAngleDeg = new double[motors.length];
        phase = new double[motors.length];
        for (int i = 0; i < motors.length; i++) {
            baseTargetAngleDeg[i] = motors[i].targetAngleDeg;
            phase[i] = i * (Math.PI / 2.0); // stagger starting phase per motor
        }
    }

    // -------------------------------------------------------------------------
    @Override
    public void process() {
        for (int i = 0; i < motors.length; i++) {
            phase[i] += phaseStepPerCall;
            motors[i].targetAngleDeg = baseTargetAngleDeg[i] + amplitudeDeg * Math.sin(phase[i]);
        }
    }
}
