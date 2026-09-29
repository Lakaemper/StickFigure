package editor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import skeleton.Body;
import skeleton.Bone;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Generates every Horse_* asset procedurally: the skeleton, its poses
// (standing, charging, flying -- each facing left, plus the mirrored _R
// version) and a walk-left clip. Side view, facing -x.
//
// Skeleton: spine from croup (tip0, rear) to withers (tip1, front); neck and
// head from the withers, tail from the croup; four two-segment legs -- hind
// legs from the croup, the hock bending BACKWARD like a real horse's; forelegs
// from the withers, the knee bending forward. bone[0] (the root every tool
// positions the body by) is a hind leg whose tip0 sits at the croup, and the
// far-side (R) legs come first, so they draw behind the body.
//
// Poses are described by where the roots and hooves go; legs are solved by
// two-bone IK, the rest are plain angles. Each pose is written through
// Body.storePose, so motor targets always match the geometry exactly.
//
// Walk: four-beat lateral-sequence walk (left hind, left fore, right hind,
// right fore, a quarter cycle apart), each hoof on the ground for
// STANCE_FRACTION of the cycle -- planted, it slides backward relative to its
// root at exactly the walking speed; swinging, it eases forward to its next
// footfall. Each end of the body rides as high as its planted legs reach, so
// the spine pitches and bobs a little with the footfalls, and the head nods.
// Run: java editor.HorseGenerator  (writes all Horse_* files into src/assets)
public class HorseGenerator {
    private static final String DIR = "src/assets/";

    // proportions in world units (the human figure is ~1.6 tall)
    private static final double SPINE = 1.1;
    private static final double NECK = 0.6;
    private static final double HEAD = 0.45;
    private static final double TAIL = 0.5;
    private static final double UPPER_LEG = 0.5;
    private static final double LOWER_LEG = 0.45;
    private static final double LEG = UPPER_LEG + LOWER_LEG;

    private static final String[] SIDES = {"R", "L"}; // far side first: drawn behind the body

    // walk
    private static final double CYCLE_SECONDS = 0.85;
    private static final int KEYS = 40; // footfalls (0, 10, 20, 30) and lift-offs land exactly on keys
    private static final double STRIDE = 0.9; // distance per cycle -> ~1.06 units/s
    private static final double STANCE_FRACTION = 0.6;
    private static final double FORE_LIFT = 0.10;
    private static final double HIND_LIFT = 0.08;
    private static final double STANCE_REACH = 0.95; // planted leg's max extension, fraction of LEG
    private static final double SWING_REACH = 0.99;
    // footfall phase per leg (lateral sequence), in cycles
    private static final Map<String, Double> PHASE = Map.of(
            "HindL", 0.0, "ForeL", 0.25, "HindR", 0.5, "ForeR", 0.75);

    public static void main(String[] args) throws IOException {
        String skeletonPath = DIR + "Horse_Initial.json";
        writeRawSkeleton(skeletonPath);
        Body horse = new Body();
        horse.buildFromConfig(skeletonPath);

        Map<String, Double> standing = standingPose();
        setPose(horse, standing, new TupleD(0.0, rootHeightOf(standing)));
        horse.storePose(new File(skeletonPath));
        writePose(horse, standing, rootHeightOf(standing), "Standing", List.of());

        Map<String, Double> charging = chargingPose();
        // hooves pinned while gathering, like the human charging pose's feet
        writePose(horse, charging, rootHeightOf(charging), "Charging",
                List.of("lowerHindLegL_hoof", "lowerHindLegR_hoof", "lowerForeLegL_hoof", "lowerForeLegR_hoof"));

        writePose(horse, flyingPose(), 1.2, "Flying", List.of());

        writeWalk(DIR + "Horse_Anim_Walk_L.json");
        System.out.println("wrote Horse_Initial, Horse_Pose_{Standing,Charging,Flying}_{L,R}, Horse_Anim_Walk_L");
    }

    // -------------------------------------------------------------------------
    // standing square: withers a little higher than the croup, hind hooves
    // slightly behind the croup, fore hooves right under the withers.
    private static Map<String, Double> standingPose() {
        Map<String, Double> a = new LinkedHashMap<>();
        double hind = Math.sqrt(sq(0.95 * LEG) - sq(0.08));
        double fore = Math.sqrt(sq(0.975 * LEG) - sq(0.02));
        double pitch = Math.asin((fore - hind) / SPINE);
        TupleD croup = new TupleD(0.0, hind);
        TupleD withers = croup.add(new TupleD(-SPINE * Math.cos(pitch), SPINE * Math.sin(pitch)));
        a.put("spine", 180.0 - Math.toDegrees(pitch));
        for (String s : SIDES) {
            putLeg(a, "HindLeg" + s, croup, new TupleD(croup.first + 0.08, 0.0), false);
            putLeg(a, "ForeLeg" + s, withers, new TupleD(withers.first - 0.02, 0.0), true);
        }
        a.put("neck", 125.0);
        a.put("head", 225.0);
        a.put("tail", -65.0);
        return a;
    }

    // -------------------------------------------------------------------------
    // gathered for a jump: haunches lowered with the hind hooves stepped in
    // under the belly, front end up and leaning back over the forelegs, neck
    // raised, head tucked.
    private static Map<String, Double> chargingPose() {
        Map<String, Double> a = new LinkedHashMap<>();
        double croupHeight = 0.6;
        // hooves 0.85 apart (vs ~1.2 standing): fore hoof 0.1 behind the
        // withers, hind hoof 0.1 ahead of the croup, so SPINE*cos(pitch) - 0.2 = 0.85
        double pitch = Math.acos(1.05 / SPINE);
        TupleD croup = new TupleD(0.0, croupHeight);
        TupleD withers = croup.add(new TupleD(-SPINE * Math.cos(pitch), SPINE * Math.sin(pitch)));
        a.put("spine", 180.0 - Math.toDegrees(pitch));
        for (String s : SIDES) {
            putLeg(a, "HindLeg" + s, croup, new TupleD(croup.first - 0.1, 0.0), false);
            putLeg(a, "ForeLeg" + s, withers, new TupleD(withers.first + 0.1, 0.0), true);
        }
        a.put("neck", 105.0);
        a.put("head", 250.0);
        a.put("tail", -40.0);
        return a;
    }

    // -------------------------------------------------------------------------
    // over a jump: forelegs folded tight under the chest, hind legs trailing,
    // neck stretched forward, tail streaming back.
    private static Map<String, Double> flyingPose() {
        Map<String, Double> a = new LinkedHashMap<>();
        a.put("spine", 180.0 - 8.0);
        for (String s : SIDES) {
            a.put("upperForeLeg" + s, 200.0);
            a.put("lowerForeLeg" + s, -55.0);
            a.put("upperHindLeg" + s, -25.0);
            a.put("lowerHindLeg" + s, -60.0);
        }
        a.put("neck", 155.0);
        a.put("head", 205.0);
        a.put("tail", -20.0);
        return a;
    }

    // -------------------------------------------------------------------------
    // the croup height at which the pose's lowest hoof touches y=0.
    private static double rootHeightOf(Map<String, Double> pose) throws IOException {
        Body probe = new Body();
        probe.buildFromConfig(DIR + "Horse_Initial.json");
        setPose(probe, pose, new TupleD(0.0, 0.0));
        double lowest = Double.POSITIVE_INFINITY;
        for (Bone b : probe.bone) {
            lowest = Math.min(lowest, Math.min(b.tips[0].position.second, b.tips[1].position.second));
        }
        return -lowest;
    }

    // -------------------------------------------------------------------------
    // Horse_Pose_<name>_L (as given) and _R (mirrored around the croup), with
    // position-only anchors on the given tips.
    private static void writePose(Body horse, Map<String, Double> pose, double croupHeight, String name,
            List<String> anchorTips) throws IOException {
        for (String side : new String[]{"L", "R"}) {
            Map<String, Double> p = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : pose.entrySet()) {
                p.put(e.getKey(), side.equals("L") ? e.getValue() : 180.0 - e.getValue());
            }
            horse.buildFromConfig(DIR + "Horse_Initial.json");
            setPose(horse, p, new TupleD(0.0, croupHeight));
            for (String tip : anchorTips) {
                horse.addAnchor(tip, false);
            }
            horse.storePose(new File(DIR + "Horse_Pose_" + name + "_" + side + ".json"));
        }
    }

    // -------------------------------------------------------------------------
    private static void setPose(Body body, Map<String, Double> anglesDeg, TupleD croup) {
        for (Bone b : body.bone) {
            Double a = anglesDeg.get(b.name);
            if (a != null) {
                b.angleDeg = a;
            }
        }
        body.bone[0].tips[0].position = croup;
        body.propagatePositions(body.bone[0].tips[0]);
    }

    // -------------------------------------------------------------------------
    // two-bone IK for leg "HindLegL" etc. (bones upper<leg>/lower<leg>), root
    // to hoof, into `a`. kneeForward: foreleg knee bends toward -x (the way
    // the horse faces), hind hock toward +x.
    private static void putLeg(Map<String, Double> a, String leg, TupleD root, TupleD hoof, boolean kneeForward) {
        double[] angles = legAngles(root, hoof, kneeForward);
        a.put("upper" + leg, angles[0]);
        a.put("lower" + leg, angles[1]);
    }

    // -------------------------------------------------------------------------
    private static double[] legAngles(TupleD root, TupleD hoof, boolean kneeForward) {
        double fx = hoof.first - root.first;
        double fy = hoof.second - root.second;
        double d = Math.min(Math.hypot(fx, fy), LEG - 1e-9);
        double toHoof = Math.atan2(fy, fx);
        double cosA = (sq(UPPER_LEG) + d * d - sq(LOWER_LEG)) / (2.0 * UPPER_LEG * d);
        double bend = Math.acos(Math.max(-1.0, Math.min(1.0, cosA)));
        // clockwise from the root->hoof line puts the joint toward -x (forward)
        double upper = toHoof + (kneeForward ? -bend : bend);
        double kx = UPPER_LEG * Math.cos(upper);
        double ky = UPPER_LEG * Math.sin(upper);
        double lower = Math.atan2(d * Math.sin(toHoof) - ky, d * Math.cos(toHoof) - kx);
        return new double[]{Math.toDegrees(upper), Math.toDegrees(lower)};
    }

    // -------------------------------------------------------------------------
    // the walk-left clip; see the class comment for the gait.
    private static void writeWalk(String path) throws IOException {
        double halfStance = STRIDE * STANCE_FRACTION / 2.0; // a planted hoof travels 2x this vs. its root
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"Type\": \"Animation\",\n  \"Name\": \"Horse_Walk_L\",\n  \"Direction\": -1,\n");
        sb.append("  \"Legs\": [[\"upperHindLegL\", \"lowerHindLegL\"], [\"upperForeLegL\", \"lowerForeLegL\"], "
                + "[\"upperHindLegR\", \"lowerHindLegR\"], [\"upperForeLegR\", \"lowerForeLegR\"]],\n");
        sb.append(String.format(Locale.ROOT, "  \"Duration\": %.4f,\n  \"Loop\": true,\n  \"Keys\": [\n", CYCLE_SECONDS));
        for (int k = 0; k < KEYS; k++) {
            double phase = (double) k / KEYS;
            Map<String, double[]> hoof = new LinkedHashMap<>(); // {dx from its root, height}
            for (String leg : PHASE.keySet()) {
                double p = ((phase - PHASE.get(leg)) % 1.0 + 1.0) % 1.0;
                hoof.put(leg, hoofRelativeToRoot(p, halfStance, leg.startsWith("Fore") ? FORE_LIFT : HIND_LIFT));
            }
            double croupHeight = Math.min(rootReach(hoof.get("HindL"), phaseOf("HindL", phase)),
                    rootReach(hoof.get("HindR"), phaseOf("HindR", phase)));
            double withersHeight = Math.min(rootReach(hoof.get("ForeL"), phaseOf("ForeL", phase)),
                    rootReach(hoof.get("ForeR"), phaseOf("ForeR", phase)));
            double pitch = Math.asin((withersHeight - croupHeight) / SPINE);
            TupleD croup = new TupleD(0.0, croupHeight);
            TupleD withers = croup.add(new TupleD(-SPINE * Math.cos(pitch), SPINE * Math.sin(pitch)));

            Map<String, Double> a = new LinkedHashMap<>();
            a.put("spine", 180.0 - Math.toDegrees(pitch));
            for (String leg : new String[]{"HindR", "ForeR", "HindL", "ForeL"}) {
                boolean fore = leg.startsWith("Fore");
                TupleD root = fore ? withers : croup;
                double[] h = hoof.get(leg);
                double[] angles = legAngles(root, new TupleD(root.first + h[0], h[1]), fore);
                String side = leg.substring(leg.length() - 1);
                String bone = (fore ? "ForeLeg" : "HindLeg") + side;
                a.put("upper" + bone, angles[0]);
                a.put("lower" + bone, angles[1]);
            }
            // head nods down with each forefoot's footfall, twice a cycle;
            // the tail sways once
            double nod = Math.cos(2.0 * Math.PI * 2.0 * (phase - PHASE.get("ForeL")));
            a.put("neck", 125.0 - 4.0 * nod);
            a.put("head", 225.0 - 3.0 * nod);
            a.put("tail", -65.0 + 5.0 * Math.sin(2.0 * Math.PI * phase));

            // hooves on the ground from this key on (each lifts at STANCE_FRACTION)
            List<String> contacts = new ArrayList<>();
            for (String leg : new String[]{"HindL", "ForeL", "HindR", "ForeR"}) {
                if (phaseOf(leg, phase) < STANCE_FRACTION - 1e-9) {
                    contacts.add("\"" + hoofTip(leg) + "\"");
                }
            }
            sb.append(String.format(Locale.ROOT, "    {\"T\": %.4f, \"Stance\": \"%s\", \"Contacts\": [%s], \"Angles\": {",
                    phase * CYCLE_SECONDS, hoofTip(mostRecentFootfall(phase)), String.join(", ", contacts)));
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, Double> e : a.entrySet()) {
                entries.add(String.format(Locale.ROOT, "\"%s\": %.3f", e.getKey(), e.getValue()));
            }
            sb.append(String.join(", ", entries)).append("}}").append(k < KEYS - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(Path.of(path), sb.toString());
    }

    // -------------------------------------------------------------------------
    // "HindL" -> "lowerHindLegL_hoof"
    private static String hoofTip(String leg) {
        return "lower" + leg.substring(0, 4) + "Leg" + leg.substring(4) + "_hoof";
    }

    // -------------------------------------------------------------------------
    private static double phaseOf(String leg, double phase) {
        return ((phase - PHASE.get(leg)) % 1.0 + 1.0) % 1.0;
    }

    // -------------------------------------------------------------------------
    // the leg whose hoof touched down most recently -- the stance reference.
    private static String mostRecentFootfall(double phase) {
        String best = null;
        double bestAge = Double.MAX_VALUE;
        for (String leg : PHASE.keySet()) {
            double age = phaseOf(leg, phase);
            if (age < bestAge - 1e-9) {
                bestAge = age;
                best = leg;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // {dx, y}: hoof x relative to its root (negative = in front, the walking
    // direction), and its height above the ground. Same gait path as
    // WalkCycleGenerator: planted, it moves back at walking speed; swinging,
    // it eases to a stop IN THE WORLD at lift-off and touchdown.
    private static double[] hoofRelativeToRoot(double p, double halfStance, double lift) {
        if (p < STANCE_FRACTION) {
            double s = p / STANCE_FRACTION;
            return new double[]{-halfStance + 2.0 * halfStance * s, 0.0};
        }
        double s = (p - STANCE_FRACTION) / (1.0 - STANCE_FRACTION);
        double eased = 0.5 - 0.5 * Math.cos(Math.PI * s);
        double rootTravel = STRIDE * (1.0 - STANCE_FRACTION) * s;
        return new double[]{halfStance - STRIDE * eased + rootTravel, lift * Math.sin(Math.PI * s)};
    }

    // -------------------------------------------------------------------------
    // the highest a root may be for this leg to still reach its hoof.
    private static double rootReach(double[] hoof, double p) {
        double reach = LEG * (p < STANCE_FRACTION ? STANCE_REACH : SWING_REACH);
        return hoof[1] + Math.sqrt(reach * reach - hoof[0] * hoof[0]);
    }

    // -------------------------------------------------------------------------
    // the structural skeleton (bones, joints, motors, friction pads) -- its
    // angles and motor targets are placeholders; main() immediately poses it
    // and rewrites the file through Body.storePose.
    private static void writeRawSkeleton(String path) throws IOException {
        List<String> bones = new ArrayList<>();
        List<String> attachables = new ArrayList<>();
        List<String[]> boneTips = new ArrayList<>(); // {name, tip0, tip1}
        for (String s : SIDES) {
            if (s.equals("L")) {
                // body parts between the far and the near legs, for draw order
                addBone(bones, boneTips, "tail", "root", "tip", TAIL, 0.5);
                addBone(bones, boneTips, "spine", "croup", "withers", SPINE, 12.0);
                addBone(bones, boneTips, "neck", "base", "poll", NECK, 2.5);
                addBone(bones, boneTips, "head", "poll", "muzzle", HEAD, 2.5);
            }
            addBone(bones, boneTips, "upperHindLeg" + s, "hip", "hock", UPPER_LEG, 2.0);
            addBone(bones, boneTips, "lowerHindLeg" + s, "hock", "hoof", LOWER_LEG, 1.0);
            addBone(bones, boneTips, "upperForeLeg" + s, "shoulder", "knee", UPPER_LEG, 2.0);
            addBone(bones, boneTips, "lowerForeLeg" + s, "knee", "hoof", LOWER_LEG, 1.0);
        }
        joint(attachables, "neck", "spine", 1, "neck", 0, 300, 40, 100);
        joint(attachables, "poll", "neck", 1, "head", 0, 100, 15, 40);
        joint(attachables, "tail", "spine", 0, "tail", 0, 30, 5, 10);
        for (String s : SIDES) {
            joint(attachables, "hip" + s, "spine", 0, "upperHindLeg" + s, 0, 300, 50, 150);
            joint(attachables, "hock" + s, "upperHindLeg" + s, 1, "lowerHindLeg" + s, 0, 300, 40, 155);
            joint(attachables, "shoulder" + s, "spine", 1, "upperForeLeg" + s, 0, 300, 50, 150);
            joint(attachables, "knee" + s, "upperForeLeg" + s, 1, "lowerForeLeg" + s, 0, 300, 40, 155);
        }
        for (String[] b : boneTips) {
            for (int i = 0; i < 2; i++) {
                attachables.add(String.format(Locale.ROOT,
                        "      {\"name\": \"%s_%s\", \"Type\": \"frictionPad\", \"Bone\": \"%s\", \"TipIdx\": %d, \"FrictionCoefficient\": 0.8}",
                        b[0], b[1 + i], b[0], i));
            }
        }
        String json = "{\n  \"Body\": {\n    \"Name\": \"Horse\",\n    \"Bones\": [\n" + String.join(",\n", bones)
                + "\n    ],\n    \"Attachables\": [\n" + String.join(",\n", attachables)
                + "\n    ],\n    \"PhysicalUpgrades\": []\n  }\n}\n";
        Files.writeString(Path.of(path), json);
    }

    // -------------------------------------------------------------------------
    // keeps the hind legs ahead of everything else in the list (bone[0] must
    // be the hind leg rooted at the croup), otherwise in call order.
    private static void addBone(List<String> bones, List<String[]> boneTips, String name, String tip0, String tip1,
            double length, double mass) {
        String entry = String.format(Locale.ROOT,
                "      {\"Name\": \"%s\", \"Tip0\": \"%s\", \"Tip1\": \"%s\", \"Length\": %.3f, \"Angle\": 0, \"Mass\": %.2f}",
                name, tip0, tip1, length, mass);
        if (bones.isEmpty() && !name.startsWith("upperHindLeg")) {
            throw new IllegalStateException("bone[0] must be a hind leg (the croup-rooted root)");
        }
        bones.add(entry);
        boneTips.add(new String[]{name, tip0, tip1});
    }

    // -------------------------------------------------------------------------
    private static void joint(List<String> attachables, String name, String bone0, int tip0, String bone1, int tip1,
            double stiffness, double damping, double maxTorque) {
        attachables.add(String.format(Locale.ROOT,
                "      {\"name\": \"%s\", \"Type\": \"joint\", \"Bone0\": \"%s\", \"TipIdx0\": %d, \"Bone1\": \"%s\", \"TipIdx1\": %d}",
                name, bone0, tip0, bone1, tip1));
        attachables.add(String.format(Locale.ROOT,
                "      {\"name\": \"%s\", \"Type\": \"motor\", \"Joint\": \"%s\", \"TargetAngle\": 0, \"Stiffness\": %.1f, "
                + "\"Damping\": %.1f, \"MaxTorque\": %.1f, \"Enabled\": true, \"Balanced\": true}",
                name, name, stiffness, damping, maxTorque));
    }

    // -------------------------------------------------------------------------
    private static double sq(double v) {
        return v * v;
    }
}
