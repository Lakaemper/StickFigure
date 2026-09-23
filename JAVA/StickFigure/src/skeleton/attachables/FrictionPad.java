package skeleton.attachables;

import skeleton.Tip;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Marks a tip as one that interacts with the ground via friction -- opt-in per
// tip, so only tips carrying one of these are meant to get ground contact.
public class FrictionPad extends Attachable {
    public double frictionCoefficient;

    // -------------------------------------------------------------------------
    public FrictionPad(String name, double frictionCoefficient){
        super("frictionPad", name);
        this.frictionCoefficient = frictionCoefficient;
    }

    // -------------------------------------------------------------------------
    // Ground at y=0. Pushes a penetrating tip back to the surface, then applies
    // Position-Based-Dynamics-style Coulomb friction: this substep's horizontal
    // drift is undone down to at most mu * penetration (penetration standing in
    // for how hard the ground pushed back) -- under that, the tip sticks; over
    // it, it slides but is slowed.
    public void enforceContact(Tip tip) {
        if (tip.invMass == 0.0) {
            return;
        }
        double penetration = -tip.position.second;
        enforceContact(tip, new TupleD(tip.position.first, 0.0), new TupleD(0.0, 1.0), penetration);
    }

    // -------------------------------------------------------------------------
    // same push-back + Coulomb friction as the flat-floor case above,
    // generalized to an arbitrary contact point and outward unit normal --
    // e.g. a Polygon obstacle's nearest boundary point/edge normal, rather
    // than always straight down onto y=0. `penetration` is how far past that
    // boundary the tip already is, along `normal` (the caller derives it,
    // since that depends on the shape -- straight subtraction for a flat
    // floor, a closest-point projection for a polygon).
    public void enforceContact(Tip tip, TupleD contactPoint, TupleD normal, double penetration) {
        if (tip.invMass == 0.0 || penetration <= 0.0) {
            return;
        }

        TupleD tangent = new TupleD(-normal.second, normal.first);
        double prevOffAlongNormal = tip.prevPosition.sub(contactPoint).dot(normal);
        TupleD projectedPrev = tip.prevPosition.sub(normal.times(prevOffAlongNormal));

        double tangentialDelta = tip.position.sub(tip.prevPosition).dot(tangent);
        double frictionLimit = frictionCoefficient * penetration;
        double allowed = Math.abs(tangentialDelta) <= frictionLimit ? 0.0 : Math.copySign(frictionLimit, tangentialDelta);

        tip.position = projectedPrev.add(tangent.times(allowed));
    }
}
