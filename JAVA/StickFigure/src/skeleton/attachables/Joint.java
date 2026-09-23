package skeleton.attachables;

import skeleton.DistanceConstraint;
import skeleton.Tip;

// -----------------------------------------------------------------------------
public class Joint extends Attachable{
    public Tip[] tips = new Tip[2];

    // -------------------------------------------------------------------------
    public Joint(String name, Tip t0, Tip t1){
        super("joint", name);
        tips[0] = t0;
        tips[1] = t1;
    }

    // -------------------------------------------------------------------------
    // PBD coincidence: pulls the two tips this joint connects back together
    // (target distance 0) -- same primitive as Bone.enforceLength(), just with
    // a different target distance.
    public void enforceCoincidence() {
        DistanceConstraint.enforce(tips[0], tips[1], 0.0);
    }
}
