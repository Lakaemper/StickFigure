package animation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import skeleton.Body;
import skeleton.Bone;
import skeleton.Tip;
import skeleton.attachables.Motor;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Plays an AnimationClip on a Body kinematically, following the terrain --
// like PoseMorpher, it replaces World.step() while active, so the skeleton
// is always exactly rigid.
//
// The clip itself is authored for flat ground. Each step:
//  1. FLAT pose: the clip's (blended) bone angles, laid out from the stance
//     foot on a virtual flat floor -- gives where the hip and the swing foot
//     WOULD be relative to the planted foot on flat ground.
//  2. terrain: the planted foot (or both, in double support) stays exactly
//     where it touched down -- that is what moves the figure forward without
//     sliding. The swing foot keeps the flat pose's horizontal path and its
//     lift above the ground, but on top of whatever ground is under it
//     (World.groundHeight, probed a little ahead so it rises before a step
//     edge instead of clipping it). The hip rides at the flat pose's height
//     above the average of the two feet's ground levels, lowered if needed so
//     every planted leg can still reach its foot.
//  3. the legs are then solved by two-bone IK from that hip to those feet
//     (knees bent to the same side the clip bends them); torso, head and arms
//     keep the clip's own angles.
// step() reports when this can't go on: the swing foot touched down with no
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
    private static final double REACH = 0.995; // max leg extension, fraction of thigh+shin
    private static final double OFFSET_DECAY_SECONDS = 0.1; // see hipOffsetX / Leg.swingOffsetX
    private static final double HIP_RISE_SMOOTHING_SECONDS = 0.04;

    public enum Status { WALKING, STEPPED_INTO_AIR, BLOCKED }

    // one leg: thigh tips[0] at the hip, shin tips[1] the foot.
    private static class Leg {
        final String thighName;
        final String shinName;
        Bone thigh;
        Bone shin;
        boolean planted;
        TupleD plantedPosition;
        double groundY; // ground level under this foot (smoothed while swinging)
        boolean groundFound;
        double swingOffsetX; // see step(): decays to 0 over the swing
        TupleD previousPosition;

        Leg(String thighName, String shinName) {
            this.thighName = thighName;
            this.shinName = shinName;
        }

        Tip foot() {
            return shin.tips[1];
        }
    }

    private final World world;
    private final Leg[] legs;

    private AnimationClip clip;
    private double time;
    private double elapsed;
    private Map<String, Double> startAnglesDeg;
    private Leg stance;
    private double hipOffsetX; // keeps the hip continuous if a weight switch would jump it
    private TupleD previousHip;
    private Leg previousStance;
    private TupleD hipVelocity = new TupleD(0, 0);

    // -------------------------------------------------------------------------
    // legBoneNames: {thigh, shin} per leg, e.g. {{"thighL","shinL"},{"thighR","shinR"}}.
    public ClipPlayer(World world, String[][] legBoneNames) {
        this.world = world;
        legs = new Leg[legBoneNames.length];
        for (int i = 0; i < legs.length; i++) {
            legs[i] = new Leg(legBoneNames[i][0], legBoneNames[i][1]);
        }
    }

    // -------------------------------------------------------------------------
    public boolean isActive() {
        return clip != null;
    }

    // -------------------------------------------------------------------------
    // begins playing `clip` from startTime (seconds into it), blending in
    // from body's current pose. Both feet stay planted where they are until
    // the clip lifts them.
    public void start(AnimationClip clip, Body body, double startTime) {
        this.clip = clip;
        this.time = startTime;
        this.elapsed = 0.0;
        startAnglesDeg = new HashMap<>();
        for (Bone b : body.bone) {
            startAnglesDeg.put(b.name, Math.toDegrees(b.angle()));
        }
        for (Leg leg : legs) {
            leg.thigh = findBone(body, leg.thighName);
            leg.shin = findBone(body, leg.shinName);
            leg.planted = true;
            leg.plantedPosition = leg.foot().position;
            leg.groundY = leg.plantedPosition.second;
            leg.groundFound = true;
            leg.swingOffsetX = 0.0;
            leg.previousPosition = leg.plantedPosition;
        }
        stance = legFor(clip.stanceAt(startTime));
        hipOffsetX = 0.0;
        previousHip = null;
        hipVelocity = new TupleD(0, 0);
        previousStance = stance;
    }

    // -------------------------------------------------------------------------
    public void stop() {
        clip = null;
    }

    // -------------------------------------------------------------------------
    // how fast the walk is carrying the body right now -- e.g. for handing
    // over to physics mid-stride with the momentum a real step would have.
    public TupleD hipVelocity() {
        return hipVelocity;
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
        TupleD hipFlat = stance.thigh.tips[0].position;
        Map<Leg, TupleD> footFlat = new HashMap<>();
        Map<Leg, Double> bendSign = new HashMap<>();
        for (Leg leg : legs) {
            TupleD foot = leg.foot().position;
            TupleD knee = leg.thigh.tips[1].position;
            footFlat.put(leg, foot);
            TupleD toFoot = foot.sub(hipFlat);
            TupleD toKnee = knee.sub(hipFlat);
            bendSign.put(leg, Math.signum(toFoot.first * toKnee.second - toFoot.second * toKnee.first));
        }

        // 2. terrain: hip x from the planted stance foot, feet on the ground
        double hipX = stance.plantedPosition.first + hipFlat.first + hipOffsetX;
        if (previousHip != null && stance != previousStance) {
            // weight just switched: keep the hip where it was, and let the
            // difference fade out (0 in steady walking; only a start from a
            // pose the clip doesn't quite match leaves one)
            hipOffsetX += previousHip.first - hipX;
            hipX = previousHip.first;
        }
        hipOffsetX *= Math.exp(-STEP_DT / OFFSET_DECAY_SECONDS);
        previousStance = stance;

        Status status = Status.WALKING;
        Map<Leg, TupleD> footTarget = new HashMap<>();
        for (Leg leg : legs) {
            double flatLift = footFlat.get(leg).second; // height above the flat floor
            double flatX = hipX + footFlat.get(leg).first - hipFlat.first;
            if (leg.planted && leg != stance && flatLift > LIFT_EPSILON) {
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

        double otherGround = stance.groundY;
        for (Leg leg : legs) {
            if (leg != stance) {
                otherGround = leg.planted ? leg.plantedPosition.second : leg.groundY;
            }
        }
        double hipY = 0.5 * (stance.plantedPosition.second + otherGround) + hipFlat.second;
        // low enough for every leg to reach its foot -- the swing foot too, so
        // the hip is already down when it touches down on lower ground
        for (Leg leg : legs) {
            TupleD target = footTarget.get(leg);
            double reach = REACH * (leg.thigh.length + leg.shin.length);
            double dx = target.first - hipX;
            if (Math.abs(dx) < reach) {
                hipY = Math.min(hipY, target.second + Math.sqrt(reach * reach - dx * dx));
            }
        }
        // rising is eased (e.g. when a wide starting stance stops holding the
        // hip down the moment its rear foot lifts); lowering never is -- the
        // feet must stay reachable
        if (previousHip != null && hipY > previousHip.second) {
            hipY = previousHip.second + (hipY - previousHip.second) * (1.0 - Math.exp(-STEP_DT / HIP_RISE_SMOOTHING_SECONDS));
        }
        TupleD hip = new TupleD(hipX, hipY);

        // 3. legs by IK, then everything else from the hip
        for (Leg leg : legs) {
            solveLeg(leg, hip, footTarget.get(leg), bendSign.get(leg));
        }
        Tip hipTip = stance.thigh.tips[0];
        hipTip.position = hip;
        body.propagatePositions(hipTip);
        for (Leg leg : legs) {
            leg.previousPosition = leg.foot().position;
        }
        double hipDx = previousHip == null ? 0.0 : hipX - previousHip.first;
        hipVelocity = previousHip == null ? new TupleD(0, 0) : hip.sub(previousHip).times(1.0 / STEP_DT);
        previousHip = hip;
        double walkDir = clip.direction != 0.0 ? Math.signum(clip.direction) : Math.signum(hipDx);
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
    // two-bone IK: sets the thigh/shin angles so the foot reaches `target`
    // from `hip` (or points straight at it, fully extended, if out of reach),
    // knee on the side given by bendSign (+1 counter-clockwise of hip->foot).
    private static void solveLeg(Leg leg, TupleD hip, TupleD target, double bendSign) {
        double a = leg.thigh.length;
        double b = leg.shin.length;
        TupleD d = target.sub(hip);
        double dist = Math.max(1e-9, Math.min(Math.hypot(d.first, d.second), a + b - 1e-9));
        double toFoot = Math.atan2(d.second, d.first);
        double cosA = (a * a + dist * dist - b * b) / (2.0 * a * dist);
        double hipAngle = Math.acos(Math.max(-1.0, Math.min(1.0, cosA)));
        double thighAngle = toFoot + (bendSign >= 0.0 ? hipAngle : -hipAngle);
        TupleD knee = hip.add(new TupleD(a * Math.cos(thighAngle), a * Math.sin(thighAngle)));
        TupleD foot = hip.add(new TupleD(dist * Math.cos(toFoot), dist * Math.sin(toFoot)));
        leg.thigh.angleDeg = Math.toDegrees(thighAngle);
        leg.shin.angleDeg = Math.toDegrees(Math.atan2(foot.second - knee.second, foot.first - knee.first));
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
        Set<Bone> legBones = new HashSet<>();
        for (Leg leg : legs) {
            legBones.add(leg.thigh);
            legBones.add(leg.shin);
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
    private Leg legFor(String footTipName) {
        for (Leg leg : legs) {
            if (leg.foot().name.equals(footTipName)) {
                return leg;
            }
        }
        throw new IllegalArgumentException("no leg ends in foot tip " + footTipName);
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
