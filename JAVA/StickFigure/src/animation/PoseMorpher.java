package animation;

import java.util.HashMap;
import java.util.Map;
import skeleton.Body;
import skeleton.Bone;
import skeleton.attachables.Motor;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Purely kinematic pose-to-pose morph: interpolates a chosen fixpoint bone's
// own angle/position and every motor's target angle between wherever
// currentBody currently is and a target pose, then rebuilds the whole
// skeleton from those interpolated values via recomputeGeometry() each step.
// Deliberately not physics-driven -- while morphing, this replaces
// World.step() rather than running alongside it, so the skeleton is always
// exactly rigid and geometrically valid at every intermediate frame, with no
// lag/overshoot/PhysicalUpgrade interference the way interpolating motor
// targets under real dynamics would have.
public class PoseMorpher {
    private static final double STEP_DT = 1.0 / 60.0; // matches World's default step cadence

    private Body targetPose;
    private double morphTime;
    private TupleD targetRootPosition;
    private Bone rootBone;
    private int rootTipIdx;

    private double elapsed;
    private boolean started;
    private double startRootAngleDeg;
    private TupleD startRootPosition;
    private Map<String, Double> startMotorTargetDeg;

    // -------------------------------------------------------------------------
    // true while a morph is in progress -- e.g. so the caller's own step loop
    // knows to call morphStep instead of running physics this tick.
    public boolean isActive() {
        return targetPose != null && elapsed < morphTime;
    }

    // -------------------------------------------------------------------------
    // begins a new morph toward targetPose's current pose, reaching
    // targetRootPosition over morphTime seconds. rootBone/rootTipIdx name the
    // fixpoint everything else re-poses around -- that tip (belonging to
    // whatever Body morphStep will later be called with) is the one that
    // actually moves to targetRootPosition; every other bone follows via
    // Body.recomputeGeometry(rootBone, rootTipIdx) pivoting from there,
    // exactly like bone[0]/tips[0] already does for the ordinary build. The
    // actual starting point is captured from rootBone's own current state on
    // the next morphStep call, not here -- so setTarget can be called before
    // that pose is settled.
    public void setTarget(Body targetPose, double morphTime, TupleD targetRootPosition,
            Bone rootBone, int rootTipIdx) {
        this.targetPose = targetPose;
        this.morphTime = morphTime;
        this.targetRootPosition = targetRootPosition;
        this.rootBone = rootBone;
        this.rootTipIdx = rootTipIdx;
        this.elapsed = 0.0;
        this.started = false;
    }

    // -------------------------------------------------------------------------
    // advances the morph by one step and re-poses currentBody accordingly. A
    // no-op if setTarget hasn't been called. The first call after setTarget
    // captures currentBody's own current angles as the interpolation's
    // starting point (its ACTUAL current angles, not any motor's possibly
    // not-yet-reached target -- so morphing never opens with a snap).
    public void morphStep(Body currentBody) {
        if (targetPose == null) {
            return;
        }
        if (!started) {
            captureStart(currentBody);
            started = true;
        }

        elapsed += STEP_DT;
        double t = morphTime > 0.0 ? Math.min(1.0, elapsed / morphTime) : 1.0;

        Bone targetRootBone = findBoneByName(targetPose, rootBone.name);
        rootBone.angleDeg = lerpAngleDeg(startRootAngleDeg, targetRootBone.angleDeg, t);
        rootBone.tips[rootTipIdx].position =
                startRootPosition.add(targetRootPosition.sub(startRootPosition).times(t));

        for (Motor targetMotor : targetPose.motors) {
            Double startDeg = startMotorTargetDeg.get(targetMotor.name);
            Motor currentMotor = findMotorByName(currentBody, targetMotor.name);
            if (startDeg == null || currentMotor == null) {
                continue;
            }
            currentMotor.targetAngleDeg = lerpAngleDeg(startDeg, targetMotor.targetAngleDeg, t);
        }

        currentBody.recomputeGeometry(rootBone, rootTipIdx);

        // purely kinematic -- no velocity should carry over into whatever
        // (physics or another morph) runs next.
        for (Bone b : currentBody.bone) {
            b.tips[0].velocity = new TupleD(0, 0);
            b.tips[1].velocity = new TupleD(0, 0);
        }
    }

    // -------------------------------------------------------------------------
    private void captureStart(Body currentBody) {
        startRootAngleDeg = Math.toDegrees(rootBone.angle());
        startRootPosition = rootBone.tips[rootTipIdx].position;
        startMotorTargetDeg = new HashMap<>();
        for (Motor m : currentBody.motors) {
            startMotorTargetDeg.put(m.name, Math.toDegrees(Motor.relativeAngleRad(m.joint)));
        }
    }

    // -------------------------------------------------------------------------
    private static Motor findMotorByName(Body body, String name) {
        for (Motor m : body.motors) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    private static Bone findBoneByName(Body body, String name) {
        for (Bone b : body.bone) {
            if (b.name.equals(name)) {
                return b;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // interpolates from fromDeg toward toDeg the short way around (e.g. 170 ->
    // -170 moves +20 degrees through 180, not -340 degrees the long way).
    private static double lerpAngleDeg(double fromDeg, double toDeg, double t) {
        double delta = normalizeAngleDeg(toDeg - fromDeg);
        return fromDeg + delta * t;
    }

    // -------------------------------------------------------------------------
    private static double normalizeAngleDeg(double deg) {
        double d = deg % 360.0;
        if (d < -180.0) {
            d += 360.0;
        } else if (d > 180.0) {
            d -= 360.0;
        }
        return d;
    }
}
