/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package main;

import java.io.File;
import java.io.IOException;
import physics.World;
import skeleton.Body;
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
        viewer.onStep = () -> {
            world.step();
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

        // StorePose/LoadPose: raw tip-position snapshots, independent of the
        // JSON skeleton config -- see Body.storePose/loadPose.
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
