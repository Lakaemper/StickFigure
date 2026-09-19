/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package main;

import animation.PoseMorpher;
import animation.World;
import java.io.File;
import java.io.IOException;
import skeleton.Body;
import skeleton.Bone;
import skeleton.Tip;
import skeleton.attachables.Anchor;
import utils.TupleD;
import viewer.Viewer;

/**
 *
 * @author rlaka
 */
public class Main {

    public static Viewer viewer;

    // once LoadPose has been used, Reset restores THIS pose instead of the
    // default build pose -- null means "no pose loaded yet, use the default".
    private static File currentPoseFile;

    private static final double DRAG_KICK_SCALE = 6.0; // velocity gained per unit dragged

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        viewer = new Viewer();

        Body body = new Body();
        resetBody(body);

        World world = new World(body);
        PoseMorpher poseMorpher = new PoseMorpher();
        viewer.onStep = () -> {
            if (poseMorpher.isActive()) {
                poseMorpher.morphStep(body);
                if (!poseMorpher.isActive()) {
                    // this tick just reached the target pose -- stop rather
                    // than falling straight through into physics next tick.
                    viewer.stopPlaying();
                }
            } else {
                world.step();
            }
            viewer.clear();
            body.draw();
        };
        viewer.onReset = () -> resetBody(body);

        // drag: grab whichever tip is closest to where the mouse went down, then
        // once the mouse comes back up, give that same tip a velocity kick in the
        // drag direction, scaled by how far it was dragged -- a "flick", not a
        // continuously-applied spring while dragging.
        Tip[] grabbedTip = new Tip[1];
        viewer.onDragStart = worldPos -> grabbedTip[0] = body.closestTip(worldPos);
        viewer.onDragEnd = (start, end) -> {
            if (grabbedTip[0] != null) {
                TupleD dragVector = end.sub(start);
                grabbedTip[0].velocity = grabbedTip[0].velocity.add(dragVector.times(DRAG_KICK_SCALE));
                grabbedTip[0] = null;
            }
        };

        // click (as opposed to a drag): toggle an Anchor at whatever's near
        // the click -- see Body.toggleAnchor for the remove-vs-add order.
        // Shift-click creates a position-only anchor (no angle lock).
        viewer.onClick = (worldPos, shiftHeld) -> body.toggleAnchor(worldPos, shiftHeld);

        // right-drag: pick up whichever anchor is near where the mouse went
        // down, then drag it (and whatever it's pinning) around live --
        // clamped against any OTHER active anchor's reach (Body.clampAnchorDrag),
        // then a position-only solve lets the rest of the chain (e.g. a bent
        // knee between a hip anchor and a foot anchor) follow geometrically
        // without fighting Motor/Anchor forces, which stay off during this.
        // Every anchor's angle target is re-frozen afterward so nothing snaps
        // back the instant normal simulation (Play/Step) resumes.
        Anchor[] grabbedAnchor = new Anchor[1];
        viewer.onRightDragStart = worldPos -> grabbedAnchor[0] = body.grabAnchorNear(worldPos);
        viewer.onRightDrag = worldPos -> {
            if (grabbedAnchor[0] != null) {
                TupleD clamped = body.clampAnchorDrag(grabbedAnchor[0], worldPos);
                grabbedAnchor[0].moveTo(clamped);
                world.resolvePositions();
                for (Anchor a : body.anchors) {
                    a.refreezeAngles();
                }
                viewer.clear();
                body.draw();
            }
        };

        // StorePose/LoadPose: full skeleton-config snapshots (Bones/Attachables/
        // PhysicalUpgrades, same schema as Skeletons.json) -- see Body.storePose/loadPose.
        viewer.onStorePose = file -> {
            try {
                body.storePose(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        viewer.onLoadPose = file -> {
            try {
                body.loadPose(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            currentPoseFile = file;
            viewer.clear();
            body.draw();
        };

        // Load Target: parses a pose file into a separate, static reference
        // Body -- never stepped, just read by Morph for its angles/targets.
        Body[] targetPose = new Body[1];
        viewer.onLoadTarget = file -> {
            Body t = new Body();
            try {
                t.loadPose(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            targetPose[0] = t;
        };

        // Morph: arms the morpher toward the loaded target, keeping the
        // fixpoint exactly where it currently is (reposturing in place, not
        // relocating). onStep (above) then drives it forward each tick while
        // active. Fixpoint is shinL/tip1 (footL) for now -- looked up by name
        // each time rather than cached, since Reset/LoadPose rebuild
        // body.bone[] with fresh objects.
        viewer.onMorph = () -> {
            if (targetPose[0] != null) {
                Bone fixpointBone = findBoneByName(body, "shinL");
                int fixpointTipIdx = 1;
                poseMorpher.setTarget(targetPose[0], viewer.getMorphTimeSeconds(),
                        fixpointBone.tips[fixpointTipIdx].position, fixpointBone, fixpointTipIdx);
            }
        };
    }

    // -------------------------------------------------------------------------
    private static Bone findBoneByName(Body body, String name) {
        for (Bone b : body.bone) {
            if (b.name.equals(name)) {
                return b;
            }
        }
        return null;
    }

    // rebuilds `body` fresh from the JSON (fresh Tips means velocity/prevPosition
    // reset too, not just position), then puts it back into either the loaded
    // pose (if LoadPose has been used) or the default starting pose -- shared
    // by startup and the Reset button so they can never drift apart.
    private static void resetBody(Body body) {
        try {
            body.buildFromConfig("src/assets/Skeletons.json");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (currentPoseFile != null) {
            try {
                body.loadPose(currentPoseFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            // reposition hip to [0,1]
            body.bone[0].tips[0].position = new TupleD(0, 1.2);
            body.recomputeGeometry();
        }

        viewer.clear();
        viewer.drawFloor();
        body.draw();
    }

}
