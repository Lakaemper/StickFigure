package animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import skeleton.Body;
import skeleton.Bone;
import skeleton.Tip;
import skeleton.attachables.Joint;
import skeleton.attachables.Motor;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Plays an AnimationClip on a Body kinematically, following the terrain --
// like PoseMorpher, it replaces World.step() while active, so the skeleton
// is always exactly rigid. Works for any number of legs (the clip's "Legs"):
// legs whose roots are jointed to the same body point form a GROUP -- both
// of a biped's legs hang from its hip; a quadruped has two groups, hind legs
// at the croup and forelegs at the withers.
//
// The clip itself is authored for flat ground. Each step:
//  1. FLAT pose: the clip's (blended) bone angles, laid out from the stance
//     foot on a virtual flat floor -- gives where every leg root and every
//     swing foot WOULD be relative to the planted foot on flat ground.
//  2. terrain: every planted foot stays exactly where it touched down -- the
//     stance foot's is what moves the figure forward without sliding. A
//     swing foot keeps the flat pose's horizontal path and its lift above
//     the ground, but on top of whatever ground is under it
//     (World.groundHeight, probed a little ahead so it rises before a step
//     edge instead of clipping it). Each group's root rides at the flat
//     pose's height above the average ground level of its own feet, lowered
//     if needed so each of its legs can still reach its foot. With two
//     groups, the upper body (everything but the legs) pitches so both roots
//     get their own height -- a horse's front end rises first stepping up.
//  3. the legs are then solved by two-bone IK from their roots to their feet
//     (knees bent to the same side the clip bends them); the upper body keeps
//     the clip's own angles (plus that pitch).
// step() reports when this can't go on: a swing foot touched down with no
// ground in reach (STEPPED_INTO_AIR -- a drop bigger than MAX_STEP_DOWN), or
// the way ahead is blocked (BLOCKED -- ground rising more than MAX_STEP_UP
// within WALL_LOOKAHEAD, i.e. steeper than ~27 degrees, or the upper body
// about to walk into a rock).
// start() fades in from whatever pose the body is currently in over
// BLEND_IN_SECONDS (the clip keeps running underneath, so the figure is
// already walking as it blends), rather than snapping to the clip's pose.
public class ClipPlayer {
    private static final double STEP_DT = 1.0 / 60.0; // matches World's default step cadence
    private static final double BLEND_IN_SECONDS = 0.25;

    public static final double MAX_STEP_UP = 0.3;
    public static final double MAX_STEP_DOWN = 0.3;
    private static final double WALL_LOOKAHEAD = 0.6; // with MAX_STEP_UP: steepest walkable slope
    private static final double BODY_LOOKAHEAD = 0.6; // upper body vs. rocks ahead -- also keeps
            // the standing pose a walk ends in (feet ~0.5 either side of the
            // hip once physics settles it) clear of a rock it stopped in front of
    private static final double FOOT_GROUND_LOOKAHEAD = 0.15; // swing foot rises before a step edge
    private static final double FOOT_GROUND_SMOOTHING_SECONDS = 0.03;
    private static final double LIFT_EPSILON = 1e-3; // flat-pose foot height counting as "lifted"
    private static final double REACH = 0.995; // max leg extension, fraction of upper+lower
    private static final double OFFSET_DECAY_SECONDS = 0.1; // see offsetX / Leg.swingOffsetX
    private static final double ROOT_RISE_SMOOTHING_SECONDS = 0.04;
    private static final double MAX_PITCH_DEG = 30.0; // two-group upper-body tilt, either way

    public enum Status { WALKING, STEPPED_INTO_AIR, BLOCKED }

    // one leg: upper tips[0] at its root (hip/shoulder), lower tips[1] the foot.
    private static class Leg {
        final Bone upper;
        final Bone lower;
        Group group;
        boolean planted;
        TupleD plantedPosition;
        double groundY; // ground level under this foot (smoothed while swinging)
        boolean groundFound;
        double swingOffsetX; // see step(): decays to 0 over the swing
        TupleD previousPosition;

        Leg(Bone upper, Bone lower) {
            this.upper = upper;
            this.lower = lower;
        }

        Tip root() {
            return upper.tips[0];
        }

        Tip foot() {
            return lower.tips[1];
        }
    }

    // legs hanging from the same body point; the per-step fields are scratch.
    private static class Group {
        final List<Leg> legs = new ArrayList<>();
        TupleD previousRoot; // where the root ended up last step
        TupleD rootFlat;
        TupleD root;
        double height;
    }

    private final World world;
    private final List<Leg> legs = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final Set<Bone> legBones = new HashSet<>();

    private AnimationClip clip;
    private double time;
    private double elapsed;
    private Map<String, Double> startAnglesDeg;
    private Leg stance;
    private Leg previousStance;
    private double offsetX; // keeps the stance root continuous if a weight switch would jump it
    private TupleD previousBodyRoot;
    private TupleD rootVelocity = new TupleD(0, 0);

    // -------------------------------------------------------------------------
    public ClipPlayer(World world) {
        this.world = world;
    }

    // -------------------------------------------------------------------------
    public boolean isActive() {
        return clip != null;
    }

    // -------------------------------------------------------------------------
    // a good point to start `clip` from, given how body stands right now: the
    // footfall whose foot is currently the furthest ahead in the walking
    // direction -- starting on another would pin a REAR foot as if it had
    // just stepped out in front and drag the whole body back while blending in.
    public static double startTimeFor(AnimationClip clip, Body body) {
        double dir = clip.direction != 0.0 ? Math.signum(clip.direction) : -1.0;
        double best = 0.0;
        double bestAhead = Double.NEGATIVE_INFINITY;
        for (AnimationClip.Key k : clip.footfalls()) {
            Tip foot = body.tipByName.get(k.stance);
            if (foot != null && dir * foot.position.first > bestAhead + 1e-9) {
                bestAhead = dir * foot.position.first;
                best = k.t;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // begins playing `clip` from startTime (seconds into it), blending in
    // from body's current pose. Every foot stays planted where it is until
    // the clip lifts it.
    public void start(AnimationClip clip, Body body, double startTime) {
        this.clip = clip;
        this.time = startTime;
        this.elapsed = 0.0;
        startAnglesDeg = new HashMap<>();
        for (Bone b : body.bone) {
            startAnglesDeg.put(b.name, Math.toDegrees(b.angle()));
        }
        legs.clear();
        groups.clear();
        legBones.clear();
        Map<Tip, Group> groupByAttachment = new HashMap<>();
        for (String[] names : clip.legs) {
            Leg leg = new Leg(findBone(body, names[0]), findBone(body, names[1]));
            leg.planted = true;
            // seated on the ground under it -- as far down as the leg itself
            // reaches, or a step up (e.g. a figure that just turned around on
            // uneven ground, its feet now mirrored onto the wrong levels) -- or
            // left where it is if there's none
            TupleD foot = leg.foot().position;
            double ground = world.groundHeight(foot.first, foot.second + MAX_STEP_UP,
                    foot.second - (leg.upper.length + leg.lower.length));
            leg.plantedPosition = Double.isNaN(ground) ? foot : new TupleD(foot.first, ground);
            leg.groundY = leg.plantedPosition.second;
            leg.groundFound = true;
            leg.swingOffsetX = 0.0;
            leg.previousPosition = leg.plantedPosition;
            legs.add(leg);
            legBones.add(leg.upper);
            legBones.add(leg.lower);
        }
        for (Leg leg : legs) {
            Tip attachment = attachmentOf(body, leg.root());
            Group g = groupByAttachment.get(attachment);
            if (g == null) {
                g = new Group();
                g.previousRoot = leg.root().position;
                groups.add(g);
                groupByAttachment.put(attachment, g);
            }
            g.legs.add(leg);
            leg.group = g;
        }
        stance = legFor(clip.stanceAt(startTime));
        previousStance = stance;
        offsetX = 0.0;
        previousBodyRoot = null;
        rootVelocity = new TupleD(0, 0);
    }

    // -------------------------------------------------------------------------
    public void stop() {
        clip = null;
    }

    // -------------------------------------------------------------------------
    // how fast the walk is carrying the body right now -- e.g. for handing
    // over to physics mid-stride with the momentum a real step would have.
    public TupleD hipVelocity() {
        return rootVelocity;
    }

    // -------------------------------------------------------------------------
    // the foot currently carrying the weight -- e.g. the natural fixpoint for
    // a morph out of the walk.
    public Tip stanceTip(Body body) {
        return stance.foot();
    }

    // -------------------------------------------------------------------------
    public Status step(Body body) {
        if (clip == null) {
            return Status.WALKING;
        }
        time += STEP_DT;
        elapsed += STEP_DT;

        // weight switch: the new stance foot just touched down -- plant it
        // right there, snapped onto the ground under it (it was only tracking
        // that ground through a smoothing filter while swinging).
        Leg newStance = legFor(clip.stanceAt(time));
        if (newStance != stance) {
            if (!newStance.groundFound) {
                return Status.STEPPED_INTO_AIR;
            }
            TupleD touchdown = newStance.foot().position;
            double exact = world.groundHeight(touchdown.first, newStance.groundY + MAX_STEP_UP, newStance.groundY - MAX_STEP_DOWN);
            double y = Double.isNaN(exact) ? newStance.groundY : exact;
            newStance.planted = true;
            newStance.plantedPosition = new TupleD(touchdown.first, y);
            newStance.groundY = y;
            stance = newStance;
        }

        // 1. flat pose: blended clip angles, laid out from the stance foot
        applyBlendedClipAngles(body);
        Tip stanceFoot = stance.foot();
        stanceFoot.position = new TupleD(0.0, 0.0);
        body.propagatePositions(stanceFoot);
        for (Group g : groups) {
            g.rootFlat = g.legs.get(0).root().position;
        }
        Map<Leg, TupleD> footFlat = new HashMap<>();
        Map<Leg, Double> bendSign = new HashMap<>();
        for (Leg leg : legs) {
            TupleD foot = leg.foot().position;
            TupleD knee = leg.upper.tips[1].position;
            footFlat.put(leg, foot);
            TupleD toFoot = foot.sub(leg.group.rootFlat);
            TupleD toKnee = knee.sub(leg.group.rootFlat);
            bendSign.put(leg, Math.signum(toFoot.first * toKnee.second - toFoot.second * toKnee.first));
        }

        // 2. terrain: the stance root's x from the planted stance foot
        Group stanceGroup = stance.group;
        double rootX = stance.plantedPosition.first + stanceGroup.rootFlat.first + offsetX;
        if (stance != previousStance && stanceGroup.previousRoot != null) {
            // weight just switched: keep that root where it was, and let the
            // difference fade out (0 in steady walking; only a start from a
            // pose the clip doesn't quite match leaves one)
            offsetX += stanceGroup.previousRoot.first - rootX;
            rootX = stanceGroup.previousRoot.first;
        }
        offsetX *= Math.exp(-STEP_DT / OFFSET_DECAY_SECONDS);
        previousStance = stance;
        for (Group g : groups) {
            g.root = new TupleD(rootX + g.rootFlat.first - stanceGroup.rootFlat.first, 0.0); // x only, for now
        }

        Status status = Status.WALKING;
        Map<Leg, TupleD> footTarget = new HashMap<>();
        Set<String> contacts = clip.contactsAt(time);
        for (Leg leg : legs) {
            double flatLift = footFlat.get(leg).second; // height above the flat floor
            double flatX = leg.group.root.first + footFlat.get(leg).first - leg.group.rootFlat.first;
            // the clip says when each foot leaves the ground; without that,
            // guess from the flat pose (spline error can fake an early lift)
            boolean lifted = contacts != null ? !contacts.contains(leg.foot().name) : flatLift > LIFT_EPSILON;
            if (leg.planted && leg != stance && lifted) {
                // lift-off: from here on it follows the flat path, starting
                // from where it actually was (only differs after a blend-in)
                leg.planted = false;
                leg.swingOffsetX = leg.plantedPosition.first - flatX;
            }
            if (leg.planted) {
                footTarget.put(leg, leg.plantedPosition);
                continue;
            }
            leg.swingOffsetX *= Math.exp(-STEP_DT / OFFSET_DECAY_SECONDS);
            double x = flatX + leg.swingOffsetX;
            double dir = clip.direction != 0.0 ? Math.signum(clip.direction) : Math.signum(x - leg.previousPosition.first);

            // ground under (and just ahead of) the swing foot, within step reach
            double from = leg.groundY + MAX_STEP_UP;
            double to = leg.groundY - MAX_STEP_DOWN;
            double ground = Double.NaN;
            double lookahead = Math.min(FOOT_GROUND_LOOKAHEAD, 2.0 * flatLift);
            for (double probe : new double[]{0.0, 0.5 * lookahead, lookahead}) {
                double g = world.groundHeight(x + dir * probe, from, to);
                if (!Double.isNaN(g) && !(g <= ground)) {
                    ground = g;
                }
            }
            leg.groundFound = !Double.isNaN(ground);
            if (leg.groundFound) {
                leg.groundY += (ground - leg.groundY) * (1.0 - Math.exp(-STEP_DT / FOOT_GROUND_SMOOTHING_SECONDS));
            }
            // a wall ahead: ground rising more than a step within WALL_LOOKAHEAD
            if (dir != 0.0 && world.insideObstacle(new TupleD(x + dir * WALL_LOOKAHEAD, leg.groundY + MAX_STEP_UP))) {
                status = Status.BLOCKED;
            }
            footTarget.put(leg, new TupleD(x, leg.groundY + Math.max(0.0, flatLift)));
        }

        // each group's root height: the flat height above its feet's average
        // ground -- low enough for each of its legs to reach (the swing foot
        // too, so a root is already down when its foot lands on lower
        // ground); rising is eased (e.g. when a wide starting stance stops
        // holding a root down the moment its rear foot lifts), lowering
        // never is -- the feet must stay reachable
        for (Group g : groups) {
            double ground = 0.0;
            for (Leg leg : g.legs) {
                ground += leg.planted ? leg.plantedPosition.second : leg.groundY;
            }
            double h = ground / g.legs.size() + g.rootFlat.second;
            for (Leg leg : g.legs) {
                TupleD target = footTarget.get(leg);
                double reach = REACH * (leg.upper.length + leg.lower.length);
                double dx = target.first - g.root.first;
                if (Math.abs(dx) < reach) {
                    h = Math.min(h, target.second + Math.sqrt(reach * reach - dx * dx));
                }
            }
            if (g.previousRoot != null && h > g.previousRoot.second) {
                h = g.previousRoot.second + (h - g.previousRoot.second) * (1.0 - Math.exp(-STEP_DT / ROOT_RISE_SMOOTHING_SECONDS));
            }
            g.height = h;
        }

        // place the roots: the stance group's at its height; with exactly two
        // groups the upper body pitches about it so the other root gets its
        // own height (lowering both if the pitch limit keeps it too high);
        // any further groups just ride along rigidly.
        TupleD stanceRoot = new TupleD(rootX, stanceGroup.height);
        double pitch = 0.0;
        Group other = groups.size() == 2 ? (groups.get(0) == stanceGroup ? groups.get(1) : groups.get(0)) : null;
        if (other != null) {
            TupleD v = other.rootFlat.sub(stanceGroup.rootFlat);
            double length = v.length();
            if (length > 1e-9) {
                double s = Math.max(-1.0, Math.min(1.0, (other.height - stanceGroup.height) / length));
                double theta = Math.atan2(v.second, v.first);
                double p1 = normalizeRad(Math.asin(s) - theta);
                double p2 = normalizeRad(Math.PI - Math.asin(s) - theta);
                pitch = Math.abs(p1) < Math.abs(p2) ? p1 : p2;
                double maxPitch = Math.toRadians(MAX_PITCH_DEG);
                pitch = Math.max(-maxPitch, Math.min(maxPitch, pitch));
                TupleD rotated = rotate(v, pitch);
                double excess = stanceRoot.second + rotated.second - other.height;
                if (excess > 0.0) {
                    stanceRoot = new TupleD(stanceRoot.first, stanceRoot.second - excess);
                }
            }
        }
        for (Group g : groups) {
            g.root = stanceRoot.add(rotate(g.rootFlat.sub(stanceGroup.rootFlat), pitch));
        }
        // swing feet follow their own (now pitched) root horizontally
        for (Leg leg : legs) {
            if (!leg.planted) {
                double x = leg.group.root.first + footFlat.get(leg).first - leg.group.rootFlat.first + leg.swingOffsetX;
                footTarget.put(leg, new TupleD(x, footTarget.get(leg).second));
            }
        }

        // 3. upper body (with its pitch) and legs by IK, then everything
        // else from the stance root
        if (pitch != 0.0) {
            for (Bone b : body.bone) {
                if (!legBones.contains(b)) {
                    b.angleDeg += Math.toDegrees(pitch);
                }
            }
        }
        for (Leg leg : legs) {
            solveLeg(leg, leg.group.root, footTarget.get(leg), bendSign.get(leg));
        }
        Tip rootTip = stance.root();
        rootTip.position = stanceRoot;
        body.propagatePositions(rootTip);
        for (Group g : groups) {
            g.previousRoot = g.legs.get(0).root().position;
        }
        for (Leg leg : legs) {
            leg.previousPosition = leg.foot().position;
        }
        TupleD bodyRoot = body.bone[0].tips[0].position;
        double bodyRootDx = previousBodyRoot == null ? 0.0 : bodyRoot.first - previousBodyRoot.first;
        rootVelocity = previousBodyRoot == null ? new TupleD(0, 0) : bodyRoot.sub(previousBodyRoot).times(1.0 / STEP_DT);
        previousBodyRoot = bodyRoot;
        double walkDir = clip.direction != 0.0 ? Math.signum(clip.direction) : Math.signum(bodyRootDx);
        if (status == Status.WALKING && upperBodyWalkingIntoRock(body, walkDir)) {
            status = Status.BLOCKED;
        }

        // same as PoseMorpher: motor targets follow the geometry and nothing
        // keeps any velocity, so physics resumes cleanly whenever it takes over.
        for (Motor m : body.motors) {
            m.targetAngleDeg = Math.toDegrees(Motor.relativeAngleRad(m.joint));
        }
        for (Bone b : body.bone) {
            b.tips[0].velocity = new TupleD(0, 0);
            b.tips[1].velocity = new TupleD(0, 0);
        }
        return status;
    }

    // -------------------------------------------------------------------------
    private void applyBlendedClipAngles(Body body) {
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
    }

    // -------------------------------------------------------------------------
    // two-bone IK: sets the upper/lower angles so the foot reaches `target`
    // from `root` (or points straight at it, fully extended, if out of reach),
    // knee on the side given by bendSign (+1 counter-clockwise of root->foot).
    private static void solveLeg(Leg leg, TupleD root, TupleD target, double bendSign) {
        double a = leg.upper.length;
        double b = leg.lower.length;
        TupleD d = target.sub(root);
        double dist = Math.max(1e-9, Math.min(Math.hypot(d.first, d.second), a + b - 1e-9));
        double toFoot = Math.atan2(d.second, d.first);
        double cosA = (a * a + dist * dist - b * b) / (2.0 * a * dist);
        double rootAngle = Math.acos(Math.max(-1.0, Math.min(1.0, cosA)));
        double upperAngle = toFoot + (bendSign >= 0.0 ? rootAngle : -rootAngle);
        TupleD knee = root.add(new TupleD(a * Math.cos(upperAngle), a * Math.sin(upperAngle)));
        TupleD foot = root.add(new TupleD(dist * Math.cos(toFoot), dist * Math.sin(toFoot)));
        leg.upper.angleDeg = Math.toDegrees(upperAngle);
        leg.lower.angleDeg = Math.toDegrees(Math.atan2(foot.second - knee.second, foot.first - knee.first));
    }

    // -------------------------------------------------------------------------
    // any non-leg tip about to walk into a rock: BODY_LOOKAHEAD ahead of it
    // (in the walking direction, dir = -1/+1) is inside one, while the tip
    // itself isn't -- a tip already inside is skipped, so a figure that ended
    // up slightly penetrating a rock can still walk away from it.
    private boolean upperBodyWalkingIntoRock(Body body, double dir) {
        if (dir == 0.0) {
            return false;
        }
        for (Bone b : body.bone) {
            if (legBones.contains(b)) {
                continue;
            }
            for (Tip tip : b.tips) {
                TupleD ahead = new TupleD(tip.position.first + dir * BODY_LOOKAHEAD, tip.position.second);
                if (!world.insideObstacle(tip.position) && world.insideObstacle(ahead)) {
                    return true;
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // the body tip a leg's root is jointed to (e.g. torso_hip for a thigh) --
    // legs sharing one move as a group. Falls back to the root itself for a
    // leg jointed to nothing but other legs.
    private Tip attachmentOf(Body body, Tip legRoot) {
        for (Joint j : body.joints) {
            for (int i = 0; i < 2; i++) {
                if (j.tips[i] == legRoot && !legBones.contains(j.tips[1 - i].bone)) {
                    return j.tips[1 - i];
                }
            }
        }
        return legRoot;
    }

    // -------------------------------------------------------------------------
    private Leg legFor(String footTipName) {
        for (Leg leg : legs) {
            if (leg.foot().name.equals(footTipName)) {
                return leg;
            }
        }
        throw new IllegalArgumentException("no leg ends in foot tip " + footTipName);
    }

    // -------------------------------------------------------------------------
    private static TupleD rotate(TupleD v, double angleRad) {
        double c = Math.cos(angleRad);
        double s = Math.sin(angleRad);
        return new TupleD(v.first * c - v.second * s, v.first * s + v.second * c);
    }

    // -------------------------------------------------------------------------
    private static double normalizeRad(double a) {
        double r = a % (2.0 * Math.PI);
        if (r <= -Math.PI) {
            r += 2.0 * Math.PI;
        } else if (r > Math.PI) {
            r -= 2.0 * Math.PI;
        }
        return r;
    }

    // -------------------------------------------------------------------------
    private static Bone findBone(Body body, String name) {
        for (Bone b : body.bone) {
            if (b.name.equals(name)) {
                return b;
            }
        }
        throw new IllegalArgumentException("no bone " + name);
    }
}
