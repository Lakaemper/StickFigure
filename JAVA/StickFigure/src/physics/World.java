package physics;

import java.util.Map;
import skeleton.Body;
import skeleton.Bone;
import skeleton.PhysicalUpgrades;
import skeleton.Tip;
import skeleton.attachables.Anchor;
import skeleton.attachables.FrictionPad;
import skeleton.attachables.Joint;
import skeleton.attachables.Motor;
import utils.TupleD;

// -----------------------------------------------------------------------------
// Steps a Body's physics. Deliberately everything here bottoms out at Tips --
// gravity and motor torque are forces added to a tip's velocity, bone rigidity
// and joint coincidence are PBD position corrections between two tips, ground
// contact/friction correct a tip's position directly. Bones themselves never
// carry force, velocity, or mass.
public class World {

    public Body body;
    public TupleD gravity = new TupleD(0.0, -9.81);
    public double dt = 1.0 / 60.0;
    public int substeps = 8;
    public int solverIterations = 20; // strong leg motors + firm ground contact need
                                       // more Gauss-Seidel passes to converge per
                                       // substep, or the residual shows up as a large
                                       // spurious velocity once corrected -- see the
                                       // standing tuning notes for how this was found

    // -------------------------------------------------------------------------
    public World(Body body) {
        this.body = body;
    }

    // -------------------------------------------------------------------------
    public void step() {
        double subDt = dt / substeps;
        for (int s = 0; s < substeps; s++) {
            substep(subDt);
        }
    }

    // -------------------------------------------------------------------------
    // pure position-based re-solve: no gravity, no Motor/Anchor forces, no
    // velocity change -- just Bone/Joint geometry settling given wherever the
    // tips currently are. For posing the body kinematically (e.g. while
    // dragging an Anchor around), so the rest of the chain follows the drag
    // geometrically without fighting whatever forces would normally be active.
    public void resolvePositions() {
        for (int i = 0; i < solverIterations; i++) {
            for (Bone b : body.bone) {
                b.enforceLength();
            }
            for (Joint j : body.joints) {
                j.enforceCoincidence();
            }
        }
    }

    // -------------------------------------------------------------------------
    private void substep(double subDt) {
        // 1. forces: gravity is an acceleration (mass-independent), motors are
        // genuine forces realized via Motor.apply()
        for (Bone b : body.bone) {
            for (Tip tip : b.tips) {
                if (tip.invMass > 0.0) {
                    tip.velocity = tip.velocity.add(gravity.times(subDt));
                }
            }
        }
        for (PhysicalUpgrades u : body.physicalUpgrades) {
            u.process();
        }
        for (Motor m : body.motors) {
            m.apply(subDt);
        }
        for (Anchor a : body.anchors) {
            a.apply(subDt);
        }

        // 2. integrate
        for (Bone b : body.bone) {
            for (Tip tip : b.tips) {
                tip.prevPosition = tip.position;
                if (tip.invMass > 0.0) {
                    tip.position = tip.position.add(tip.velocity.times(subDt));
                }
            }
        }

        // 3. solve constraints, several iterations (Gauss-Seidel style)
        for (int i = 0; i < solverIterations; i++) {
            for (Bone b : body.bone) {
                b.enforceLength();
            }
            for (Joint j : body.joints) {
                j.enforceCoincidence();
            }
            for (Map.Entry<Tip, FrictionPad> e : body.frictionPads.entrySet()) {
                e.getValue().enforceContact(e.getKey());
            }
        }

        // 4. re-derive velocity from what the constraint solve actually did, so
        // corrections aren't silently lost/reintroduced as energy next substep
        for (Bone b : body.bone) {
            for (Tip tip : b.tips) {
                if (tip.invMass > 0.0) {
                    tip.velocity = tip.position.sub(tip.prevPosition).times(1.0 / subDt);
                }
            }
        }
    }
}
