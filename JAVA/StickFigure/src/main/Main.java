/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package main;

import animation.Polygon;
import animation.PoseMorpher;
import animation.World;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

    // JUMP sequence constants -- see onJump/onStep wiring below.
    private static final String JUMP_CHARGE_POSE_PATH = "C:\\Users\\rlaka\\Documents\\Charging.json";
    private static final String JUMP_FLYING_POSE_PATH = "C:\\Users\\rlaka\\Documents\\a_pose_flying.json";
    private static final double JUMP_START_X = 4.5; // near the viewer's right edge; this
                                                      // charging pose leans left from the
                                                      // hip, so the hip itself is the body's
                                                      // rightmost point
    private static final double JUMP_CHARGE_SECONDS = 1.0;
    private static final double JUMP_MORPH_SECONDS = 0.5;
    private static final double JUMP_LAUNCH_ANGLE_DEG = 45.0;
    private static final double JUMP_LAUNCH_SPEED = 9.0; // m/s, to the left -- needs
            // enough range to reach the center-left wall obstacle while still airborne

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        viewer = new Viewer();

        Body body = new Body();
        World world = new World(body);
        world.obstacles.add(buildOverhangingWall());
        resetBody(body, world);

        PoseMorpher poseMorpher = new PoseMorpher();
        // set by onLoadTarget below; declared here so onStep can reach it once
        // a morph completes.
        Body[] targetPose = new Body[1];

        // JUMP sequence state -- see onJump below. jumpCharging counts real
        // playback seconds through the "play physics for 1s" phase;
        // jumpPendingLaunch marks that the morph currently in progress is
        // JUMP's own (to flying pose), so its completion should launch
        // straight into flight instead of pausing like a manual Morph does.
        boolean[] jumpCharging = {false};
        double[] jumpChargeElapsed = {0.0};
        boolean[] jumpPendingLaunch = {false};

        viewer.onStep = () -> {
            if (poseMorpher.isActive()) {
                poseMorpher.morphStep(body);
                if (!poseMorpher.isActive()) {
                    // this tick just reached the target pose -- replace
                    // whatever anchors AND physical upgrades were active
                    // before/during the morph with the target's own, so
                    // whatever runs next (physics, or a launch) honors
                    // exactly what the target pose intended -- e.g. a
                    // different jitter amplitude -- not a leftover mix of both.
                    if (targetPose[0] != null) {
                        for (Anchor a : new ArrayList<>(body.anchors)) {
                            body.removeAnchor(a.tipsSnapshot()[0].name);
                        }
                        for (Anchor a : targetPose[0].anchors) {
                            body.addAnchor(a.tipsSnapshot()[0].name, a.angleEnabled);
                        }
                        body.applyPhysicalUpgradesFrom(targetPose[0]);
                    }
                    if (jumpPendingLaunch[0]) {
                        // kick: drop every anchor (including the ones just
                        // reattached above) and launch, then keep playing --
                        // unlike a manual Morph, JUMP doesn't pause here.
                        jumpPendingLaunch[0] = false;
                        double rad = Math.toRadians(JUMP_LAUNCH_ANGLE_DEG);
                        body.launch(new TupleD(-JUMP_LAUNCH_SPEED * Math.cos(rad), JUMP_LAUNCH_SPEED * Math.sin(rad)));
                    } else {
                        viewer.stopPlaying();
                    }
                }
            } else {
                world.step();
                if (jumpCharging[0]) {
                    jumpChargeElapsed[0] += 1.0 / 60.0;
                    if (jumpChargeElapsed[0] >= JUMP_CHARGE_SECONDS) {
                        jumpCharging[0] = false;
                        jumpPendingLaunch[0] = true;
                        Body flying = new Body();
                        try {
                            flying.loadPose(new File(JUMP_FLYING_POSE_PATH));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        targetPose[0] = flying;
                        Tip fixpointTip = findBoneByName(body, "shinL").tips[1];
                        poseMorpher.setTarget(flying, JUMP_MORPH_SECONDS, fixpointTip.position, fixpointTip);
                    }
                }
            }
            viewer.clear();
            drawObstacles(world);
            body.draw();
        };
        viewer.onReset = () -> resetBody(body, world);

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
        // Shift-click creates a position-only anchor (no angle lock). Redraws
        // immediately so the marker appears even while paused -- otherwise
        // nothing would show until the next Play/Step tick.
        viewer.onClick = (worldPos, shiftHeld) -> {
            body.toggleAnchor(worldPos, shiftHeld);
            viewer.clear();
            drawObstacles(world);
            body.draw();
        };

        // right-drag: pick up whichever anchor is near where the mouse went
        // down, then drag it (and whatever it's pinning) around live --
        // clamped against any OTHER active anchor's reach (Body.clampAnchorDrag).
        // holdAngles() re-derives the DRAGGED anchor's own segments straight
        // from its locked angle first, since the position-only solve that
        // follows has no notion of angle (only length/coincidence) and would
        // otherwise leave any of its segments not independently pinned
        // elsewhere free to swing to a wildly different orientation from even
        // a tiny drag. That solve then only has to settle the REST of the
        // chain (e.g. a bent knee between a hip anchor and a foot anchor).
        // Every anchor's angle target is re-frozen afterward so nothing snaps
        // back the instant normal simulation (Play/Step) resumes.
        Anchor[] grabbedAnchor = new Anchor[1];
        viewer.onRightDragStart = worldPos -> grabbedAnchor[0] = body.grabAnchorNear(worldPos);
        viewer.onRightDrag = worldPos -> {
            if (grabbedAnchor[0] != null) {
                TupleD clamped = body.clampAnchorDrag(grabbedAnchor[0], worldPos);
                grabbedAnchor[0].moveTo(clamped);
                grabbedAnchor[0].holdAngles();
                grabbedAnchor[0].freezeFarTips();
                world.resolvePositions();
                grabbedAnchor[0].unfreezeFarTips();
                for (Anchor a : body.anchors) {
                    a.refreezeAngles();
                }
                viewer.clear();
                drawObstacles(world);
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
            drawObstacles(world);
            body.draw();
        };

        // Load Target: parses a pose file into a separate, static reference
        // Body -- never stepped, just read by Morph for its angles/targets.
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
                Tip fixpointTip = findBoneByName(body, "shinL").tips[1];
                poseMorpher.setTarget(targetPose[0], viewer.getMorphTimeSeconds(),
                        fixpointTip.position, fixpointTip);
            }
        };

        // Jump: loads the charging pose (with its own anchors) at the
        // viewer's right border, then hands off to onStep above -- it plays
        // physics for JUMP_CHARGE_SECONDS, morphs to the flying pose over
        // JUMP_MORPH_SECONDS, then launches. Ignored while a jump/morph is
        // already in progress.
        viewer.onJump = () -> {
            if (jumpCharging[0] || jumpPendingLaunch[0] || poseMorpher.isActive()) {
                return;
            }
            try {
                body.loadPose(new File(JUMP_CHARGE_POSE_PATH));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            currentPoseFile = new File(JUMP_CHARGE_POSE_PATH);
            body.bone[0].tips[0].position = new TupleD(JUMP_START_X, body.bone[0].tips[0].position.second);
            body.recomputeGeometry();

            jumpChargeElapsed[0] = 0.0;
            jumpCharging[0] = true;
            jumpPendingLaunch[0] = false;

            viewer.clear();
            drawObstacles(world);
            body.draw();
            viewer.startPlaying();
        };
    }

    // -------------------------------------------------------------------------
    // a tall, thin slab standing on the ground at center-left, tilted 30
    // degrees off vertical so its top overhangs toward +x -- i.e. leaning
    // out over the incoming JUMP flight path (launched from the right,
    // arcing up and to the left), so the figure runs into the underside of
    // the overhang or its tilted face rather than just its foot.
    private static Polygon buildOverhangingWall() {
        double tiltRad = Math.toRadians(-30.0); // negative = top leans toward +x
        double height = 2.5;
        double thickness = 0.3;
        TupleD base = new TupleD(-2.0, 0.0);
        TupleD[] local = {
            new TupleD(-thickness / 2, 0),
            new TupleD(thickness / 2, 0),
            new TupleD(thickness / 2, height),
            new TupleD(-thickness / 2, height)
        };
        TupleD[] vertices = new TupleD[local.length];
        for (int i = 0; i < local.length; i++) {
            vertices[i] = local[i].rotate(tiltRad, new TupleD(0, 0)).add(base);
        }
        return new Polygon(vertices);
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
    private static void resetBody(Body body, World world) {
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
        drawObstacles(world);
        body.draw();
    }

    // -------------------------------------------------------------------------
    // draws every World obstacle's own edges (Viewer only knows lines/circles/
    // ellipses, not Polygon) -- cleared by viewer.clear() just like the figure
    // itself, so every redraw site needs to call this again alongside body.draw().
    private static void drawObstacles(World world) {
        for (Polygon obstacle : world.obstacles) {
            TupleD[] v = obstacle.vertices;
            for (int i = 0; i < v.length; i++) {
                viewer.drawLine(v[i], v[(i + 1) % v.length]);
            }
        }
    }

}
