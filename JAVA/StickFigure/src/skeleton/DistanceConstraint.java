package skeleton;

import utils.TupleD;

// -----------------------------------------------------------------------------
// Shared PBD-style distance constraint: pulls two tips toward being exactly
// targetDistance apart, split between them by inverse mass (a fixed tip, invMass
// 0, contributes none of its own movement to the correction). Bone.enforceLength()
// and Joint.enforceCoincidence() are really the same primitive -- targetDistance
// = the bone's own length for rigidity, 0 for a joint holding two tips coincident.
public class DistanceConstraint {

    public static void enforce(Tip t0, Tip t1, double targetDistance) {
        TupleD delta = t1.position.sub(t0.position);
        double dist = delta.length();
        double invMassSum = t0.invMass + t1.invMass;
        if (invMassSum == 0.0 || dist == 0.0) {
            return;
        }

        TupleD axis = delta.times(1.0 / dist);
        double c = dist - targetDistance;
        double lagrange = c / invMassSum;

        t0.position = t0.position.add(axis.times(lagrange * t0.invMass));
        t1.position = t1.position.sub(axis.times(lagrange * t1.invMass));
    }
}
