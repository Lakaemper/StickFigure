package skeleton;

import skeleton.attachables.Motor;

// -----------------------------------------------------------------------------
// Purely cosmetic (not a control/balance strategy like HipBalancer): nudges the
// elbow motors' target angles with a small, slow sinusoidal wiggle so the hands
// aren't perfectly frozen at rest -- a bit of idle sway, the way a real
// standing person's hands are never quite still. Each motor gets its own phase
// offset so multiple joints don't move in obvious lockstep.
public class HandJitter extends PhysicalUpgrades {

    private final Motor[] motors;
    private final double[] baseTargetAngleDeg;
    private final double[] phase;

    public double amplitudeDeg = 4.0;
    public double phaseStepPerCall = 0.006; // radians of phase advance per process() call

    // -------------------------------------------------------------------------
    public HandJitter(Motor[] motors) {
        this.motors = motors;
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
