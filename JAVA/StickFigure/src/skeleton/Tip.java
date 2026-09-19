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
    public TupleD position;
    public LinkedList<Attachable> ats = new LinkedList();

    // physics state -- unused until an animation.World actually steps a Body
    public TupleD velocity = new TupleD(0, 0);
    public double invMass = 0.0; // 0 = fixed/immovable
    public TupleD prevPosition;  // scratch: this substep's position before integration

    // -------------------------------------------------------------------------
    public void addAttachable(Attachable atbl){
        ats.add(atbl);
    }

    // -------------------------------------------------------------------------
    public void applyForce(TupleD force, double dt) {
        if (invMass == 0.0) {
            return;
        }
        velocity = velocity.add(force.times(invMass * dt));
    }
}
