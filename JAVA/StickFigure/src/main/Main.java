/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package main;

import java.io.IOException;
import physics.World;
import skeleton.Body;
import skeleton.Tip;
import utils.TupleD;
import viewer.Viewer;

/**
 *
 * @author rlaka
 */
public class Main {

    public static Viewer viewer;

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
    }

    // rebuilds `body` fresh from the JSON (fresh Tips means velocity/prevPosition
    // reset too, not just position) and puts it back in its starting pose --
    // shared by startup and the Reset button so they can never drift apart.
    private static void resetBody(Body body) {
        try {
            body.buildFromConfig("src/assets/Skeletons.json");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // reposition hip to [0,1]
        body.bone[0].tips[0].position = new TupleD(0, 1.2);
        body.recomputeGeometry();

        viewer.clear();
        viewer.drawFloor();
        body.draw();
    }

}
