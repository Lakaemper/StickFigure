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
        if (penetration <= 0.0) {
            return;
        }

        double dx = tip.position.first - tip.prevPosition.first;
        double frictionLimit = frictionCoefficient * penetration;
        double allowedDx = Math.abs(dx) <= frictionLimit ? 0.0 : Math.copySign(frictionLimit, dx);

        tip.position = new TupleD(tip.prevPosition.first + allowedDx, 0.0);
    }
}
