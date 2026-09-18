package skeleton;

import main.Main;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Same as a regular Bone geometry-wise (two tips, length, angle) -- only how it
// draws differs: a short neck stub (tip0 to the midpoint) plus a circle for the
// head, centered 2/3 of the way to tip1 with radius length/3.
public class HeadBone extends Bone {

    // -------------------------------------------------------------------------
    public HeadBone(String name, TupleD position, double length, double angleDeg){
        super(name, position, length, angleDeg);
    }

    // -------------------------------------------------------------------------
    @Override
    public void draw() {
        TupleD tip0 = tips[0].position;
        TupleD tip1 = tips[1].position;
        TupleD delta = tip1.sub(tip0);

        TupleD half = tip0.add(delta.times(0.5));
        Main.viewer.drawLine(tip0, half);

        TupleD headCenter = tip0.add(delta.times(2.0 / 3.0));
        Main.viewer.drawCircle(headCenter, length / 2.0);
    }
}
