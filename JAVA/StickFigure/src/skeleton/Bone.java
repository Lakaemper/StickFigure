package skeleton;

import main.Main;
import skeleton.attachables.Attachable;
import utils.TupleD;

public class Bone {
    public String name = "";
    public Tip[] tips = new Tip[2];
    public double length;
    public double angleDeg;
    public double mass; // the TRUE structural mass -- unlike tips[*].invMass, an
                         // Anchor never touches this when it temporarily zeroes a
                         // tip's invMass, so it's always safe to read back out
                         // (e.g. for Body.storePose) regardless of what's anchored
                         // right now.

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
        tips[0].bone = this;
        tips[0].tipIdx = 0;

        double angleRad = Math.toRadians(angleDeg);
        TupleD offset = new TupleD(length * Math.cos(angleRad), length * Math.sin(angleRad));
        tips[1] = new Tip();
        tips[1].position = position.add(offset);
        tips[1].bone = this;
        tips[1].tipIdx = 1;

        tips[0].ats.add(new Attachable());
        tips[1].ats.add(new Attachable());
    }

    // -------------------------------------------------------------------------
    // an ellipse along the tip0->tip1 axis: major radius half the tip-to-tip
    // distance (so the ellipse's ends land exactly on the tips), minor radius
    // a third of that. Uses the tips' current positions/angle() rather than
    // length/angleDeg, which are only the build-time values and go stale the
    // moment physics starts moving the tips.
    public void draw() {
        TupleD center = tips[0].position.add(tips[1].position).times(0.5);
        double majorRadius = tips[1].position.sub(tips[0].position).length() / 2.0;
        double minorRadius = majorRadius / 3.0;
        Main.viewer.drawEllipse(center, majorRadius, minorRadius, angle());
    }

    // -------------------------------------------------------------------------
    // splits `mass` evenly onto this bone's two tips, as inverse mass (0 = fixed).
    // mass <= 0 fixes both tips instead of dividing by zero.
    public void setMass(double mass) {
        this.mass = mass;
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
