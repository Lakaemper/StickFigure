package skeleton.attachables;

import skeleton.Bone;
import skeleton.DistanceConstraint;
import skeleton.Tip;

// -----------------------------------------------------------------------------
public class Joint extends Attachable{
    public Bone[] bones = new Bone[2];
    public int[] tipIdx = new int[2];


    // -------------------------------------------------------------------------
    public Joint(String name, Bone b0, int tipIdx0, Bone b1, int tipIdx1){
        super("joint", name);
        bones[0] = b0;
        bones[1] = b1;
        tipIdx[0] = tipIdx0;
        tipIdx[1] = tipIdx1;
    }

    // -------------------------------------------------------------------------
    // PBD coincidence: pulls the two tips this joint connects back together
    // (target distance 0) -- same primitive as Bone.enforceLength(), just with
    // a different target distance.
    public void enforceCoincidence() {
        Tip t0 = bones[0].tips[tipIdx[0]];
        Tip t1 = bones[1].tips[tipIdx[1]];
        DistanceConstraint.enforce(t0, t1, 0.0);
    }
}
