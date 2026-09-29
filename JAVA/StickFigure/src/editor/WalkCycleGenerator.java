package editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import skeleton.Body;
import skeleton.Bone;

// -----------------------------------------------------------------------------
// Generates a walk-left cycle as an AnimationClip file (see
// animation.AnimationClip), procedurally rather than hand-posed: each foot
// follows a simple gait path relative to the hip, the legs are solved by
// two-bone IK, and the arms swing opposite to the legs.
//  - stance (first STANCE_FRACTION of each leg's cycle): the foot is planted,
//    so relative to the hip it slides backward (+x) at exactly the walking
//    speed -- which is what makes ClipPlayer's planted-foot playback slide-free.
//  - swing (the rest): the foot lifts by up to SWING_LIFT and eases forward
//    (-x) to its next touchdown.
//  - hip height: as high as the planted leg(s) reach (never quite straight,
//    see STANCE_REACH) -- that alone gives the natural bob: lowest while both
//    feet are down, highest as the body passes over the planted foot.
// The two legs are half a cycle apart. Walk-right is not generated: it is
// this clip mirrored at load time (AnimationClip.mirrored).
// Run: java editor.WalkCycleGenerator  (writes OUTPUT_PATH)
public class WalkCycleGenerator {
    private static final String SKELETON_PATH = "src/assets/Initial.json";
    private static final String OUTPUT_PATH = "src/assets/Anim_Walk_L.json";

    private static final double CYCLE_SECONDS = 0.8; // two steps
    private static final int KEYS = 32;
    private static final double STEP_LENGTH = 0.45; // world units per step -> 1.125 units/s at this cycle time
    private static final double STANCE_FRACTION = 0.6; // of each leg's cycle, foot on the ground
    private static final double SWING_LIFT = 0.08; // max foot clearance mid-swing
    private static final double STANCE_REACH = 0.96; // planted leg's max extension, fraction of full length
    private static final double SWING_REACH = 0.99; // same for the swing leg
    private static final double TORSO_LEAN_DEG = 4.0; // forward (toward -x)
    private static final double HEAD_LEAN_DEG = 2.0;
    private static final double ARM_SWING_DEG = 22.0; // upper arm, either side of hanging straight down
    private static final double ELBOW_BEND_MIN_DEG = 15.0; // arm fully back
    private static final double ELBOW_BEND_MAX_DEG = 40.0; // arm fully forward

    public static void main(String[] args) throws IOException {
        Body skeleton = new Body();
        skeleton.buildFromConfig(SKELETON_PATH);
        double thigh = boneLength(skeleton, "thighL");
        double shin = boneLength(skeleton, "shinL");
        double legLength = thigh + shin;
        // a planted foot travels 2*halfStance relative to the hip during its
        // stance -- which, at constant hip speed, is STANCE_FRACTION of one
        // full cycle = 2 steps.
        double halfStance = STEP_LENGTH * STANCE_FRACTION;

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"Type\": \"Animation\",\n  \"Name\": \"Walk_L\",\n  \"Direction\": -1,\n"); // walks toward -x
        sb.append(String.format(Locale.ROOT, "  \"Duration\": %.4f,\n  \"Loop\": true,\n  \"Keys\": [\n", CYCLE_SECONDS));
        for (int k = 0; k < KEYS; k++) {
            double phase = (double) k / KEYS;
            double pL = phase;
            double pR = (phase + 0.5) % 1.0;
            double[] footL = footRelativeToHip(pL, halfStance);
            double[] footR = footRelativeToHip(pR, halfStance);
            double hipY = Math.min(hipReach(footL, pL, legLength), hipReach(footR, pR, legLength));

            double[] legL = legAngles(footL, hipY, thigh, shin);
            double[] legR = legAngles(footR, hipY, thigh, shin);

            // arms counter-swing the opposite leg: left arm is fully forward
            // when the RIGHT leg is (its heel strike, phase 0.5), and vice versa.
            double fwdArmL = -Math.cos(2.0 * Math.PI * phase);
            double fwdArmR = Math.cos(2.0 * Math.PI * phase);
            double upperArmL = 270.0 - ARM_SWING_DEG * fwdArmL;
            double upperArmR = 270.0 - ARM_SWING_DEG * fwdArmR;
            double foreArmL = upperArmL - elbowBend(fwdArmL);
            double foreArmR = upperArmR - elbowBend(fwdArmR);

            // left foot carries the weight from its own heel strike (phase 0)
            // to the right foot's (phase 0.5)
            String stance = phase < 0.5 ? "shinL_foot" : "shinR_foot";

            sb.append(String.format(Locale.ROOT, "    {\"T\": %.4f, \"Stance\": \"%s\", \"HipHeight\": %.4f, \"Angles\": {", phase * CYCLE_SECONDS, stance, hipY));
            sb.append(String.format(Locale.ROOT,
                    "\"torso\": %.3f, \"head\": %.3f, \"upperArmL\": %.3f, \"foreArmL\": %.3f, \"upperArmR\": %.3f, \"foreArmR\": %.3f, "
                    + "\"thighL\": %.3f, \"shinL\": %.3f, \"thighR\": %.3f, \"shinR\": %.3f}}",
                    90.0 + TORSO_LEAN_DEG, 90.0 + HEAD_LEAN_DEG, upperArmL, foreArmL, upperArmR, foreArmR,
                    legL[0], legL[1], legR[0], legR[1]));
            sb.append(k < KEYS - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(Path.of(OUTPUT_PATH), sb.toString());
        System.out.println("wrote " + OUTPUT_PATH);
    }

    // -------------------------------------------------------------------------
    // {dx, y}: the foot's x relative to the hip (negative = in front, the
    // walking direction), and its height above the ground.
    private static double[] footRelativeToHip(double p, double halfStance) {
        if (p < STANCE_FRACTION) {
            double s = p / STANCE_FRACTION;
            return new double[]{-halfStance + 2.0 * halfStance * s, 0.0};
        }
        // the foot eases to a stop IN THE WORLD at lift-off and touchdown (a
        // stride = 2 steps forward), while the hip keeps moving at constant
        // speed -- relative to the hip that's the eased stride plus the hip's
        // own travel. Easing relative to the hip instead would land the foot
        // still moving at walking speed, i.e. sliding on touchdown.
        double s = (p - STANCE_FRACTION) / (1.0 - STANCE_FRACTION);
        double eased = 0.5 - 0.5 * Math.cos(Math.PI * s);
        double stride = 2.0 * STEP_LENGTH;
        double hipTravel = stride * (1.0 - STANCE_FRACTION) * s; // hip moves `stride` per full cycle
        return new double[]{halfStance - stride * eased + hipTravel, SWING_LIFT * Math.sin(Math.PI * s)};
    }

    // -------------------------------------------------------------------------
    // the highest the hip may be for this leg to still reach its foot.
    private static double hipReach(double[] foot, double p, double legLength) {
        double reach = legLength * (p < STANCE_FRACTION ? STANCE_REACH : SWING_REACH);
        return foot[1] + Math.sqrt(reach * reach - foot[0] * foot[0]);
    }

    // -------------------------------------------------------------------------
    // two-bone IK, hip at (0, hipY) and foot at (dx, y): {thigh, shin}
    // absolute angles in degrees, knee bent forward (toward -x).
    private static double[] legAngles(double[] foot, double hipY, double thigh, double shin) {
        double fx = foot[0];
        double fy = foot[1] - hipY;
        double d = Math.min(Math.hypot(fx, fy), thigh + shin - 1e-9);
        double toFoot = Math.atan2(fy, fx);
        double cosA = (thigh * thigh + d * d - shin * shin) / (2.0 * thigh * d);
        double a = Math.acos(Math.max(-1.0, Math.min(1.0, cosA)));
        double thighAngle = toFoot - a; // rotating clockwise from the hip->foot line puts the knee in front
        double kx = thigh * Math.cos(thighAngle);
        double ky = thigh * Math.sin(thighAngle);
        double shinAngle = Math.atan2(fy - ky, fx - kx);
        return new double[]{Math.toDegrees(thighAngle), Math.toDegrees(shinAngle)};
    }

    // -------------------------------------------------------------------------
    private static double elbowBend(double forward) {
        return ELBOW_BEND_MIN_DEG + (ELBOW_BEND_MAX_DEG - ELBOW_BEND_MIN_DEG) * 0.5 * (forward + 1.0);
    }

    // -------------------------------------------------------------------------
    private static double boneLength(Body body, String name) {
        for (Bone b : body.bone) {
            if (b.name.equals(name)) {
                return b.length;
            }
        }
        throw new IllegalArgumentException("no bone " + name);
    }
}
