package animation;

import java.util.HashMap;
import java.util.Map;
import skeleton.Body;
import skeleton.Bone;
import skeleton.Tip;
import skeleton.attachables.Motor;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Purely kinematic pose-to-pose morph: interpolates the fixpoint tip's own
// position, and every bone's own absolute angle independently (each takes
// its own short way around, see morphStep), between wherever currentBody
// currently is and a target pose, then rebuilds tip positions from those
// angles via Body.propagatePositions() each step -- each motor's target is
// then derived from the resulting geometry, not interpolated itself.
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
    private Tip rootTip;

    private double elapsed;
    private boolean started;
    private TupleD startRootPosition;
    private Map<String, Double> startBoneAngleDeg;

    // -------------------------------------------------------------------------
    // true while a morph is in progress -- e.g. so the caller's own step loop
    // knows to call morphStep instead of running physics this tick.
    public boolean isActive() {
        return targetPose != null && elapsed < morphTime;
    }

    // -------------------------------------------------------------------------
    // begins a new morph toward targetPose's current pose, reaching
    // targetRootPosition over morphTime seconds. rootTip names the fixpoint
    // everything else re-poses around -- that tip (belonging to whatever Body
    // morphStep will later be called with) is the one that actually moves to
    // targetRootPosition; every other bone's own angle is interpolated
    // independently (see morphStep) and positions then follow via
    // Body.propagatePositions(rootTip) pivoting from there, exactly like
    // bone[0]/tips[0] already does for the ordinary build. The actual
    // starting point is captured from rootTip's own current state on the next
    // morphStep call, not here -- so setTarget can be called before that pose
    // is settled.
    public void setTarget(Body targetPose, double morphTime, TupleD targetRootPosition, Tip rootTip) {
        this.targetPose = targetPose;
        this.morphTime = morphTime;
        this.targetRootPosition = targetRootPosition;
        this.rootTip = rootTip;
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

        rootTip.position = startRootPosition.add(targetRootPosition.sub(startRootPosition).times(t));

        // each bone's own absolute angle is interpolated directly and
        // independently, so it always takes ITS OWN short way -- rather than
        // deriving non-root bones from the fixpoint's angle plus a chain of
        // independently-lerped RELATIVE joint angles. That chained approach
        // looks right per joint (every individual relative-angle lerp is
        // itself under 180 degrees) but doesn't compose: for a bone several
        // joints from the fixpoint (e.g. the leg on the far side from a
        // foot fixpoint), the sum of several "each individually shortest"
        // terms can still net out to a near-360-degree sweep.
        for (Bone b : currentBody.bone) {
            Double startDeg = startBoneAngleDeg.get(b.name);
            Bone targetBone = findBoneByName(targetPose, b.name);
            if (startDeg == null || targetBone == null) {
                continue;
            }
            b.angleDeg = lerpAngleDeg(startDeg, Math.toDegrees(targetBone.angle()), t);
        }

        // positions only, from the angles just set above -- NOT
        // recomputeGeometry, which would re-derive non-root bones' angles
        // from their motor's target and undo the per-bone lerp above.
        currentBody.propagatePositions(rootTip);

        // each motor's target is derived FROM the geometry just built,
        // rather than interpolated separately, so physics sees exactly zero
        // error the instant it resumes -- by construction, every tick, not
        // just (approximately) at t=1.
        for (Motor m : currentBody.motors) {
            m.targetAngleDeg = Math.toDegrees(Motor.relativeAngleRad(m.joint));
        }

        // purely kinematic -- no velocity should carry over into whatever
        // (physics or another morph) runs next.
        for (Bone b : currentBody.bone) {
            b.tips[0].velocity = new TupleD(0, 0);
            b.tips[1].velocity = new TupleD(0, 0);
        }
    }

    // -------------------------------------------------------------------------
    private void captureStart(Body currentBody) {
        startRootPosition = rootTip.position;
        startBoneAngleDeg = new HashMap<>();
        for (Bone b : currentBody.bone) {
            startBoneAngleDeg.put(b.name, Math.toDegrees(b.angle()));
        }
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
