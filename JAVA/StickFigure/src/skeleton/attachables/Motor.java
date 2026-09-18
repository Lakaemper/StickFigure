package skeleton.attachables;

import skeleton.Bone;
import skeleton.Tip;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Drives the relative angle of an existing Joint (bones[1] relative to bones[0])
// via a PD controller: torque = stiffness*error - damping*relAngVel, clamped to
// +/-maxTorque. A bone's "angle", for this purpose, is measured from the joint
// outward to its far tip -- like the motor has two arms, each inserted into one
// bone at the joint -- rather than a bone-intrinsic tip0->tip1 direction, since
// which tip is the joint side differs joint to joint for the same bone (e.g.
// torso's tip0 is the joint side for the hips, but its tip1 is the joint side
// for the shoulders/neck). Angular velocity is derived the same way, from the
// two tips' velocities, since bones carry no angle/angular-velocity state of
// their own. The resulting torque is realized as an equal-and-opposite force
// pair at each bone's far tip (a lever-arm couple) -- there is no other way to
// apply "torque" when nothing but tips has mass.
public class Motor extends Attachable {
    public Joint joint;

    public boolean enabled = true;
    public double targetAngleDeg;
    public double stiffness;  // kp
    public double damping;    // kd
    public double maxTorque;

    // -------------------------------------------------------------------------
    public Motor(String name, Joint joint, double targetAngleDeg, double stiffness, double damping, double maxTorque){
        super("motor", name);
        this.joint = joint;
        this.targetAngleDeg = targetAngleDeg;
        this.stiffness = stiffness;
        this.damping = damping;
        this.maxTorque = maxTorque;
    }

    // -------------------------------------------------------------------------
    public void apply(double dt) {
        if (!enabled) {
            return;
        }

        Bone bone0 = joint.bones[0];
        Bone bone1 = joint.bones[1];
        int farTipIdx0 = 1 - joint.tipIdx[0];
        int farTipIdx1 = 1 - joint.tipIdx[1];

        Tip jointTip0 = bone0.tips[joint.tipIdx[0]];
        Tip farTip0 = bone0.tips[farTipIdx0];
        Tip jointTip1 = bone1.tips[joint.tipIdx[1]];
        Tip farTip1 = bone1.tips[farTipIdx1];

        double relAngle = relativeAngleRad(joint);
        double relAngVel = angularVelocityOf(jointTip1, farTip1) - angularVelocityOf(jointTip0, farTip0);

        double error = normalizeAngle(Math.toRadians(targetAngleDeg) - relAngle);
        double torque = stiffness * error - damping * relAngVel;
        torque = Math.max(-maxTorque, Math.min(maxTorque, torque));

        applyTorqueAtFarTip(jointTip1, farTip1, torque, dt);
        applyTorqueAtFarTip(jointTip0, farTip0, -torque, dt);
    }

    // -------------------------------------------------------------------------
    // Realizes `torque` about jointTip as a single force at farTip, perpendicular
    // to the (jointTip -> farTip) lever arm, magnitude torque/leverLength -- the
    // standard way to turn a torque into a point force with no rotational body.
    private static void applyTorqueAtFarTip(Tip jointTip, Tip farTip, double torque, double dt) {
        TupleD lever = farTip.position.sub(jointTip.position);
        double leverLength = lever.length();
        if (leverLength == 0.0) {
            return;
        }
        TupleD u = lever.times(1.0 / leverLength);
        TupleD perpendicular = new TupleD(-u.second, u.first);
        TupleD force = perpendicular.times(torque / leverLength);
        farTip.applyForce(force, dt);
    }

    // -------------------------------------------------------------------------
    // this joint's current relative angle (bones[1] relative to bones[0],
    // joint-outward convention), in radians -- the same quantity apply()'s PD
    // error is measured against. Exposed for e.g. Body.storePose, which needs
    // to write a TargetAngle that reproduces the CURRENT pose, not just copy
    // whatever this motor's own in-progress target happens to be.
    public static double relativeAngleRad(Joint joint) {
        Bone bone0 = joint.bones[0];
        Bone bone1 = joint.bones[1];
        Tip jointTip0 = bone0.tips[joint.tipIdx[0]];
        Tip farTip0 = bone0.tips[1 - joint.tipIdx[0]];
        Tip jointTip1 = bone1.tips[joint.tipIdx[1]];
        Tip farTip1 = bone1.tips[1 - joint.tipIdx[1]];
        return normalizeAngle(angleOf(jointTip1, farTip1) - angleOf(jointTip0, farTip0));
    }

    // -------------------------------------------------------------------------
    // direction from the joint outward to the far tip -- NOT bone-intrinsic
    // tip0->tip1, since which tip is the joint side depends on this joint.
    private static double angleOf(Tip jointTip, Tip farTip) {
        TupleD d = farTip.position.sub(jointTip.position);
        return Math.atan2(d.second, d.first);
    }

    // -------------------------------------------------------------------------
    // rigid-rod angular velocity from the two tips' velocities: omega = (r x v) / |r|^2
    private static double angularVelocityOf(Tip jointTip, Tip farTip) {
        TupleD r = farTip.position.sub(jointTip.position);
        TupleD v = farTip.velocity.sub(jointTip.velocity);
        double rSq = r.first * r.first + r.second * r.second;
        if (rSq == 0.0) {
            return 0.0;
        }
        return (r.first * v.second - r.second * v.first) / rSq;
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
