package animation;

import java.util.HashMap;
import java.util.Map;
import skeleton.Body;
import skeleton.Bone;
import skeleton.Tip;
import skeleton.attachables.Motor;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Plays an AnimationClip on a Body kinematically -- like PoseMorpher, it
// replaces World.step() while active, so the skeleton is always exactly
// rigid. Each step: sample the clip's bone angles, then rebuild positions
// pivoting from the clip's current STANCE foot, held exactly where it was
// planted. That planted foot is what moves the figure forward (no stored
// root motion, and no foot sliding): when the weight switches, the new
// stance foot gets planted wherever it just touched down, at the old stance
// foot's height, so the figure walks level.
// start() fades in from whatever pose the body is currently in over
// BLEND_IN_SECONDS (the clip keeps running underneath, so the figure is
// already walking as it blends), rather than snapping to the clip's pose.
public class ClipPlayer {
    private static final double STEP_DT = 1.0 / 60.0; // matches World's default step cadence
    private static final double BLEND_IN_SECONDS = 0.25;

    private AnimationClip clip;
    private double time;
    private double elapsed;
    private Map<String, Double> startAnglesDeg;
    private String stanceName;
    private TupleD stancePosition;

    // -------------------------------------------------------------------------
    public boolean isActive() {
        return clip != null;
    }

    // -------------------------------------------------------------------------
    // begins playing `clip` from startTime (seconds into it), blending in
    // from body's current pose. The clip's stance foot at startTime stays
    // exactly where it is right now.
    public void start(AnimationClip clip, Body body, double startTime) {
        this.clip = clip;
        this.time = startTime;
        this.elapsed = 0.0;
        startAnglesDeg = new HashMap<>();
        for (Bone b : body.bone) {
            startAnglesDeg.put(b.name, Math.toDegrees(b.angle()));
        }
        stanceName = clip.stanceAt(startTime);
        stancePosition = body.tipByName.get(stanceName).position;
    }

    // -------------------------------------------------------------------------
    public void stop() {
        clip = null;
    }

    // -------------------------------------------------------------------------
    // the foot currently carrying the weight -- e.g. the natural fixpoint for
    // a morph out of the walk.
    public Tip stanceTip(Body body) {
        return body.tipByName.get(stanceName);
    }

    // -------------------------------------------------------------------------
    // returns true if the weight switched to the other foot this step --
    // e.g. so the caller can check that the new stance foot actually landed
    // on something.
    public boolean step(Body body) {
        if (clip == null) {
            return false;
        }
        time += STEP_DT;
        elapsed += STEP_DT;

        // weight switch: plant the new stance foot where the previous frame
        // left it (it just touched down), at the old stance foot's height.
        String newStance = clip.stanceAt(time);
        boolean switched = !newStance.equals(stanceName);
        if (switched) {
            TupleD touchdown = body.tipByName.get(newStance).position;
            stancePosition = new TupleD(touchdown.first, stancePosition.second);
            stanceName = newStance;
        }

        double w = Math.min(1.0, elapsed / BLEND_IN_SECONDS);
        w = w * w * (3.0 - 2.0 * w); // smoothstep: eases in and out of the blend
        Map<String, Double> clipAngles = clip.anglesAt(time);
        for (Bone b : body.bone) {
            Double target = clipAngles.get(b.name);
            if (target == null) {
                continue;
            }
            double from = startAnglesDeg.getOrDefault(b.name, target);
            b.angleDeg = from + AnimationClip.shortDelta(from, target) * w;
        }

        Tip stance = body.tipByName.get(stanceName);
        stance.position = stancePosition;
        body.propagatePositions(stance);

        // same as PoseMorpher: motor targets follow the geometry and nothing
        // keeps any velocity, so physics resumes cleanly whenever it takes over.
        for (Motor m : body.motors) {
            m.targetAngleDeg = Math.toDegrees(Motor.relativeAngleRad(m.joint));
        }
        for (Bone b : body.bone) {
            b.tips[0].velocity = new TupleD(0, 0);
            b.tips[1].velocity = new TupleD(0, 0);
        }
        return switched;
    }
}
