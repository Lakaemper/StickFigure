package animation;

import java.util.ArrayList;
import java.util.List;
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

    // static closed-polygon obstacles a FrictionPad tip can rest/slide against,
    // in addition to the flat floor -- see pinLandedTips's ground-plane check
    // and the per-obstacle check in substep() below.
    public List<Polygon> obstacles = new ArrayList<>();
    public double dt = 1.0 / 60.0;
    public int substeps = 8;
    // how close a tip must rest to a Polygon obstacle's boundary to count as
    // "landed" for pinLandedTips -- contains() alone misses a tip already
    // correctly resolved to sit exactly ON the boundary, not inside it.
    private static final double RESTING_EPSILON = 1e-4;

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
        pinLandedTips();
    }

    // -------------------------------------------------------------------------
    // once a FrictionPad tip has settled onto the ground OR a polygon
    // obstacle (this step's contact resolution already clamped it to the
    // surface), pin it there with a weak (position-only, no angle lock)
    // Anchor, rather than leaving it to keep relying on per-substep friction
    // alone -- e.g. so a jump's landing sticks instead of the character
    // continuing to slide or settle indefinitely. A no-op for a tip that's
    // already anchored (Body.addAnchor's own guard).
    private void pinLandedTips() {
        for (Tip tip : body.frictionPads.keySet()) {
            if (tip.position.second <= 0.0) {
                tip.position = new TupleD(tip.position.first, 0.0);
                body.addAnchor(tip.name, false);
                continue;
            }
            for (Polygon obstacle : obstacles) {
                Polygon.ClosestPoint contact = obstacle.closestBoundaryPoint(tip.position);
                // contains() alone would miss a tip already correctly resolved
                // to rest exactly ON the boundary (no longer strictly
                // "inside" once it's not penetrating) -- so also treat
                // "already touching the boundary" as landed.
                boolean touching = obstacle.contains(tip.position)
                        || contact.point.dist(tip.position) < RESTING_EPSILON;
                if (touching) {
                    tip.position = contact.point;
                    body.addAnchor(tip.name, false);
                    break;
                }
            }
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
                Tip tip = e.getKey();
                FrictionPad pad = e.getValue();
                pad.enforceContact(tip);
                for (Polygon obstacle : obstacles) {
                    if (obstacle.contains(tip.position)) {
                        Polygon.ClosestPoint contact = obstacle.closestBoundaryPoint(tip.position);
                        double penetration = contact.point.sub(tip.position).dot(contact.normal);
                        pad.enforceContact(tip, contact.point, contact.normal, penetration);
                    }
                }
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
