/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package skeleton;

import java.util.LinkedList;
import skeleton.attachables.Attachable;
import utils.TupleD;

/**
 *
 * @author rlaka
 */
public class Tip {
    public String name = "";
    public TupleD position;
    public LinkedList<Attachable> ats = new LinkedList();

    // physics state -- unused until an animation.World actually steps a Body
    public TupleD velocity = new TupleD(0, 0);
    public double invMass = 0.0; // 0 = fixed/immovable
    public TupleD prevPosition;  // scratch: this substep's position before integration

    // which Bone this tip belongs to, and which of that bone's two slots (0 or
    // 1) it is -- set once by Bone's own constructor. Lets any code that only
    // has a Tip still reach bone-level state (length, angleDeg, angle(), the
    // sibling/far tip) without also being handed the Bone and index separately.
    public Bone bone;
    public int tipIdx;

    // -------------------------------------------------------------------------
    public void addAttachable(Attachable atbl){
        ats.add(atbl);
    }

    // -------------------------------------------------------------------------
    // the other tip of this same bone.
    public Tip farTip() {
        return bone.tips[1 - tipIdx];
    }

    // -------------------------------------------------------------------------
    public void applyForce(TupleD force, double dt) {
        if (invMass == 0.0) {
            return;
        }
        velocity = velocity.add(force.times(invMass * dt));
    }
}
