package skeleton.attachables;

import skeleton.Bone;
import skeleton.Tip;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Pins every bone meeting at one physical point in place, as a group: a leaf
// (one bone, nothing jointed to it) and a branching point (e.g. the hip, where
// torso/thighL/thighR all meet) are the same mechanism, just with a
// participant list of size 1 vs 3+. For each participant: forces its tip's
// mass to infinity (invMass = 0, restored by release()) and locks that bone's
// own absolute angle (see Bone.angle()) at whatever it already was when the
// anchor was created, via a PD controller realized as a lever-arm force at the
// bone's own far tip -- the same technique Motor uses, just against a fixed
// world reference instead of another moving bone. Any Motor already driving a
// joint INTERNAL to this group (connecting two of the anchor's own
// participants) is disabled while the anchor is active and restored by
// release(), since the participants' own absolute-angle locks already fully
// determine that joint's relative angle -- a live Motor would just fight it.
//
// Body is responsible for figuring out which tips belong together (a graph
// query over its own Joints) and which Motors are internal to that group;
// this class only ever acts on the resolved list it's handed. Each
// participant's owning Bone (for .angle()/.angularVelocity()) and far tip are
// reached via the Tip itself (Tip.bone, Tip.farTip()) rather than being
// carried alongside as separate parallel arrays.
public class Anchor extends Attachable {
    private final Tip[] tips;
    private final Tip[] farTips;
    private final double[] originalInvMass;
    private final double[] targetAngleDeg;

    private final Motor[] internalMotors;
    private final boolean[] motorWasEnabled;

    // scratch, valid only between freezeFarTips() and unfreezeFarTips().
    private double[] farOriginalInvMass;

    public boolean enabled = true;

    // when false, this anchor only fixes position: no bone angle is locked,
    // and any internal Motor is left alone (its own joint keeps working
    // normally) rather than disabled. Set by Body for a shift-click anchor.
    public boolean angleEnabled;

    public double stiffness;  // kp, applied per participant
    public double damping;    // kd, applied per participant
    public double maxTorque;  // clamp, applied per participant

    // -------------------------------------------------------------------------
    // tips[i] is one participant. internalMotors are the Motors (if any) on
    // joints connecting two participants of this same group -- disabled for
    // the anchor's lifetime, but only if angleEnabled (see its own comment
    // for why).
    public Anchor(String name, Tip[] tips, Motor[] internalMotors,
            double stiffness, double damping, double maxTorque, boolean angleEnabled) {
        super("anchor", name);
        this.tips = tips;
        this.stiffness = stiffness;
        this.damping = damping;
        this.maxTorque = maxTorque;
        this.angleEnabled = angleEnabled;

        int n = tips.length;
        farTips = new Tip[n];
        originalInvMass = new double[n];
        targetAngleDeg = new double[n];
        for (int i = 0; i < n; i++) {
            Tip tip = tips[i];
            farTips[i] = tip.farTip();
            originalInvMass[i] = tip.invMass;
            targetAngleDeg[i] = Math.toDegrees(tip.bone.angle());
            tip.invMass = 0.0;
            tip.addAttachable(this);
        }

        this.internalMotors = angleEnabled ? internalMotors : new Motor[0];
        motorWasEnabled = new boolean[this.internalMotors.length];
        for (int i = 0; i < this.internalMotors.length; i++) {
            motorWasEnabled[i] = this.internalMotors[i].enabled;
            this.internalMotors[i].enabled = false;
        }
    }

    // -------------------------------------------------------------------------
    // undoes the pin: gives every participant tip back its original mass, and
    // re-enables whatever internal motors this anchor disabled.
    public void release() {
        releasePosition();
        for (int i = 0; i < tips.length; i++) {
            tips[i].ats.remove(this);
        }
        for (int i = 0; i < internalMotors.length; i++) {
            internalMotors[i].enabled = motorWasEnabled[i];
        }
    }

    // -------------------------------------------------------------------------
    // undoes just the position pin (every participant tip gets its original
    // mass back), leaving everything else about this anchor intact: it stays
    // in Body.anchors, so apply() keeps running each substep and goes on
    // holding this bone's angle rigid via real torque -- but the tip is now a
    // genuine, movable point mass again, free to fly under gravity/velocity
    // rather than being pinned in place. Pair with kick() to launch it.
    public void releasePosition() {
        for (int i = 0; i < tips.length; i++) {
            tips[i].invMass = originalInvMass[i];
        }
    }

    // -------------------------------------------------------------------------
    // adds the same velocity to every participant tip, so they keep moving
    // together (still coincident) -- e.g. a one-time launch impulse after
    // releasePosition(). Only has an effect on tips with nonzero invMass
    // (releasePosition() first, or this silently does nothing).
    public void kick(TupleD velocityDelta) {
        for (Tip tip : tips) {
            tip.velocity = tip.velocity.add(velocityDelta);
        }
    }

    // -------------------------------------------------------------------------
    public void apply(double dt) {
        if (!enabled || !angleEnabled) {
            return;
        }
        for (int i = 0; i < tips.length; i++) {
            Bone bone = tips[i].bone;
            double error = normalizeAngle(Math.toRadians(targetAngleDeg[i]) - bone.angle());
            double torque = stiffness * error - damping * bone.angularVelocity();
            torque = Math.max(-maxTorque, Math.min(maxTorque, torque));
            applyTorqueAtFarTip(tips[i], farTips[i], torque, dt);
        }
    }

    // -------------------------------------------------------------------------
    // re-captures each participant's target at its CURRENT angle -- e.g. after
    // a drag's position-only solve changed a bone's angle out from under this
    // anchor's originally frozen target, so normal simulation doesn't snap it
    // back the instant forces (this anchor's own, or anything else's) resume.
    public void refreezeAngles() {
        for (int i = 0; i < tips.length; i++) {
            targetAngleDeg[i] = Math.toDegrees(tips[i].bone.angle());
        }
    }

    // -------------------------------------------------------------------------
    // a defensive copy of this anchor's participant tips -- e.g. so Body can
    // run a graph search (shortest skeletal path to another anchor) without
    // reaching into this class's own internals.
    public Tip[] tipsSnapshot() {
        return tips.clone();
    }

    // -------------------------------------------------------------------------
    // relocates every participant tip together, e.g. for a right-drag --
    // keeps them coincident with each other and with the new point, since
    // that coincidence is exactly what makes them one group.
    public void moveTo(TupleD newPosition) {
        for (Tip tip : tips) {
            tip.position = newPosition;
        }
    }

    // -------------------------------------------------------------------------
    // re-derives each participant's far tip directly from this anchor's own
    // locked target angle. Meant to run right after moveTo(), before
    // freezeFarTips()/a position-only solve: it's what actually repositions
    // the far tip to match the angle that solve is about to be locked out of
    // touching (moveTo only moved the near tip; without this, freezing the
    // far tip in place would freeze it at its stale pre-drag position,
    // stretching the bone). A no-op when angleEnabled is false, since there's
    // no locked angle to preserve.
    public void holdAngles() {
        if (!angleEnabled) {
            return;
        }
        for (int i = 0; i < tips.length; i++) {
            Bone bone = tips[i].bone;
            bone.angleDeg = targetAngleDeg[i];
            bone.recomputeTip(tips[i].tipIdx);
        }
    }

    // -------------------------------------------------------------------------
    // temporarily makes every participant's far tip immovable too (both ends
    // of each of this anchor's own segments fixed, not just the near one),
    // for the duration of a position-only solve (e.g. during a right-drag).
    // That solve only understands length/coincidence, not angle -- so a far
    // tip that's merely free (the normal state) can still get dragged off
    // holdAngles()'s just-set position by a Joint.enforceCoincidence() with
    // whatever's attached beyond it (e.g. an arm bending back from an elbow
    // anchor into the shoulder), even though holdAngles() got it right just
    // before the solve ran. Freezing it here makes the solve treat this
    // anchor's own segments as fully rigid, so only the REST of the chain
    // (e.g. a knee between this anchor and another) is left to settle. Undo
    // with unfreezeFarTips() right after the solve, or normal dragging/
    // physics would stay locked out of ever moving these tips again. A no-op
    // when angleEnabled is false (nothing was set to hold in the first place).
    public void freezeFarTips() {
        if (!angleEnabled) {
            return;
        }
        farOriginalInvMass = new double[farTips.length];
        for (int i = 0; i < farTips.length; i++) {
            farOriginalInvMass[i] = farTips[i].invMass;
            farTips[i].invMass = 0.0;
        }
    }

    // -------------------------------------------------------------------------
    public void unfreezeFarTips() {
        if (farOriginalInvMass == null) {
            return;
        }
        for (int i = 0; i < farTips.length; i++) {
            farTips[i].invMass = farOriginalInvMass[i];
        }
        farOriginalInvMass = null;
    }

    // -------------------------------------------------------------------------
    // true if `tip` is one of this anchor's own participants -- e.g. so Body
    // can skip tips that already belong to an existing anchor.
    public boolean covers(Tip tip) {
        for (Tip t : tips) {
            if (t == tip) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // a representative point for this anchor -- e.g. for proximity checks and
    // drawing a marker. All participants are coincident by construction (that
    // coincidence is exactly what made them one group), so any one tip's
    // position stands in for the whole anchor.
    public TupleD position() {
        return tips[0].position;
    }

    // -------------------------------------------------------------------------
    // same lever-arm conversion Motor.applyTorqueAtFarTip uses: realizes a
    // torque about `pivot` as a single perpendicular force at `farTip`.
    private static void applyTorqueAtFarTip(Tip pivot, Tip farTip, double torque, double dt) {
        TupleD lever = farTip.position.sub(pivot.position);
        double leverLength = lever.length();
        if (leverLength == 0.0) {
            return;
        }
        TupleD u = lever.times(1.0 / leverLength);
        TupleD perpendicular = new TupleD(-u.second, u.first);
        TupleD force = perpendicular.times(torque / leverLength);
        farTip.applyForce(force, dt);
        // equal-and-opposite reaction at the pivot -- a genuine torque couple,
        // net force zero. A no-op while the pivot is still position-locked
        // (invMass=0, so applyForce already does nothing there), but once
        // releasePosition() makes it a real movable point again, this is what
        // stops the angle-lock from quietly injecting net momentum into the
        // whole body instead of just correcting its rotation.
        pivot.applyForce(force.times(-1.0), dt);
    }

    // -------------------------------------------------------------------------
    private static double normalizeAngle(double angle) {
        while (angle < -Math.PI) {
            angle += 2.0 * Math.PI;
        }
        while (angle > Math.PI) {
            angle -= 2.0 * Math.PI;
        }
        return angle;
    }
}
