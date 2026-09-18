package skeleton;

import main.Main;
import skeleton.attachables.Attachable;
import utils.TupleD;

public class Bone {
    String name = "";
    public Tip[] tips = new Tip[2];
    public double length;
    public double angleDeg;

    // -------------------------------------------------------------------------
    public Bone(){
        this("", new TupleD(), 1.0, 1.0);
    }

    // -------------------------------------------------------------------------
    public Bone(String name, TupleD position, double length, double angleDeg){
        this.name = name;
        this.length = length;
        this.angleDeg = angleDeg;

        tips[0] = new Tip();
        tips[0].position = position;

        double angleRad = Math.toRadians(angleDeg);
        TupleD offset = new TupleD(length * Math.cos(angleRad), length * Math.sin(angleRad));
        tips[1] = new Tip();
        tips[1].position = position.add(offset);
        
        tips[0].ats.add(new Attachable());
        tips[1].ats.add(new Attachable());
    }

    // -------------------------------------------------------------------------
    public void draw() {
        Main.viewer.drawLine(tips[0].position, tips[1].position);
    }

    // -------------------------------------------------------------------------
    // splits `mass` evenly onto this bone's two tips, as inverse mass (0 = fixed).
    // mass <= 0 fixes both tips instead of dividing by zero.
    public void setMass(double mass) {
        double invMass = mass > 0.0 ? 2.0 / mass : 0.0;
        tips[0].invMass = invMass;
        tips[1].invMass = invMass;
    }

    // -------------------------------------------------------------------------
    // PBD rigidity: pulls the two tips back to exactly `length` apart, undoing
    // whatever drift integration/other forces introduced this substep.
    public void enforceLength() {
        DistanceConstraint.enforce(tips[0], tips[1], length);
    }

    // -------------------------------------------------------------------------
    // recomputes the OTHER tip's position from this bone's own length/angle, given
    // that tips[knownTipIdx]'s position is already correct (e.g. just set by a
    // joint linking it to an already-placed neighbor).
    public void recomputeTip(int knownTipIdx) {
        double angleRad = Math.toRadians(angleDeg);
        TupleD offset = new TupleD(length * Math.cos(angleRad), length * Math.sin(angleRad));
        if (knownTipIdx == 0) {
            tips[1].position = tips[0].position.add(offset);
        } else {
            tips[0].position = tips[1].position.sub(offset);
        }
    }

    // -------------------------------------------------------------------------
    // this bone's own absolute orientation (tip0 -> tip1), in radians. Unlike
    // Motor's joint-relative angle, this is unambiguous for a standalone bone --
    // there's no "which tip is the joint side" question when comparing against
    // a fixed world reference (e.g. "upright") instead of another bone.
    public double angle() {
        TupleD d = tips[1].position.sub(tips[0].position);
        return Math.atan2(d.second, d.first);
    }

    // -------------------------------------------------------------------------
    // rigid-rod angular velocity from this bone's own two tips: omega = (r x v) / |r|^2
    public double angularVelocity() {
        TupleD r = tips[1].position.sub(tips[0].position);
        TupleD v = tips[1].velocity.sub(tips[0].velocity);
        double rSq = r.first * r.first + r.second * r.second;
        if (rSq == 0.0) {
            return 0.0;
        }
        return (r.first * v.second - r.second * v.first) / rSq;
    }
}
