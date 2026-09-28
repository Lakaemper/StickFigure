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
import java.util.List;
import java.util.Random;
import skeleton.Body;
import skeleton.Bone;
import skeleton.Tip;
import skeleton.attachables.Anchor;
import skeleton.attachables.Motor;
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
    private static final String JUMP_CHARGE_POSE_PATH = "src/assets/Charging.json";
    private static final String JUMP_FLYING_POSE_PATH = "src/assets/a_pose_flying.json";
    private static final double JUMP_START_X = 4.5; // near the viewer's right edge; this
                                                      // charging pose leans left from the
                                                      // hip, so the hip itself is the body's
                                                      // rightmost point
    private static final double JUMP_CHARGE_SECONDS = 1.0;
    private static final double JUMP_MORPH_SECONDS = 0.5;
    private static final double JUMP_LAUNCH_ANGLE_DEG = 45.0;
    private static final double JUMP_LAUNCH_SPEED = 6.7; // m/s, to the left -- needs
            // enough range to reach the center-left wall obstacle while still airborne
    private static final double JUMP_RAGDOLL_DELAY_SECONDS = 2.0; // how long after the
            // FIRST post-launch collision the figure keeps hanging on its landed
            // anchors before they're all released, letting it fall/ragdoll freely
    private static final double JUMP_RECOVER_DELAY_SECONDS = 1.0; // how long after the
            // NEXT collision (the fall after the anchor release above) the figure
            // keeps ragdolling before morphing back to Initial.json's standing pose
    private static final String JUMP_STAND_POSE_PATH = "src/assets/Initial.json";
    private static final int RAGDOLL_RELEASE_SUPPRESSION_FRAMES = 12; // ~0.2s: briefly
            // keep auto-landing suppressed right after releasing the anchors, so the
            // same still-touching tip doesn't get re-pinned before it's actually had
            // a chance to fall away -- otherwise the "next collision" below would
            // fire immediately, on the very same spot, instead of after a real fall.

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        viewer = new Viewer();

        Body body = new Body();
        World world = new World(body);
        world.obstacles.addAll(buildRockField());
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

        // post-launch ragdoll/recover sequence, four phases in order:
        //  1. awaitingCollision: true from the moment of launch until the figure
        //     first lands on something (body.anchors going from empty to non-empty,
        //     via World.pinLandedTips). At that instant every motor except the neck
        //     is disabled -- once part of the body is pinned (e.g. a hand+elbow
        //     grabbing the wall), a motor whose far tip is now pinned can no longer
        //     move that side, so its whole reaction torque lands one-sided on
        //     whatever's still hanging free, swinging it like a pendulum instead of
        //     holding a pose that no longer matches reality. Then collisionTimerRunning
        //     counts up for JUMP_RAGDOLL_DELAY_SECONDS.
        //  2. at that timer's end, every anchor is released (body.launch with a zero
        //     kick) and auto-landing is suppressed for RAGDOLL_RELEASE_SUPPRESSION_FRAMES
        //     (landingSuppressionFramesLeft) so the same still-touching tip doesn't
        //     just get re-pinned next frame -- it needs to actually fall away first.
        //  3. once that brief suppression ends, awaitingSecondCollision waits for the
        //     figure to land again (a genuine second collision, after really falling),
        //     then secondCollisionTimerRunning counts up for JUMP_RECOVER_DELAY_SECONDS.
        //  4. at THAT timer's end, a morph back to Initial.json's standing pose starts,
        //     left foot (shinL/tip1) held as the fixpoint -- jumpPendingStandRecovery
        //     marks that motors should be re-enabled once that morph completes.
        boolean[] awaitingCollision = {false};
        boolean[] collisionTimerRunning = {false};
        double[] secondsSinceCollision = {0.0};
        int[] landingSuppressionFramesLeft = {0};
        boolean[] awaitingSecondCollision = {false};
        boolean[] secondCollisionTimerRunning = {false};
        double[] secondsSinceSecondCollision = {0.0};
        boolean[] jumpPendingStandRecovery = {false};

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
                    if (jumpPendingStandRecovery[0]) {
                        // reached the standing pose -- motors can safely hold
                        // it again now that nothing is still half-pinned.
                        jumpPendingStandRecovery[0] = false;
                        for (Motor m : body.motors) {
                            m.enabled = true;
                        }
                    }
                    if (jumpPendingLaunch[0]) {
                        // kick: drop every anchor (including the ones just
                        // reattached above) and launch, then keep playing --
                        // unlike a manual Morph, JUMP doesn't pause here.
                        jumpPendingLaunch[0] = false;
                        double rad = Math.toRadians(JUMP_LAUNCH_ANGLE_DEG);
                        body.launch(new TupleD(-JUMP_LAUNCH_SPEED * Math.cos(rad), JUMP_LAUNCH_SPEED * Math.sin(rad)));
                        awaitingCollision[0] = true;
                        collisionTimerRunning[0] = false;
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
                if (awaitingCollision[0] && !body.anchors.isEmpty()) {
                    // first post-launch collision: World.pinLandedTips() just
                    // anchored something -- disable every motor (see the state
                    // declaration above for why) EXCEPT the neck, which is left
                    // on so the head keeps actively holding/orienting itself
                    // instead of also going limp, and start the
                    // recover-to-standing countdown.
                    awaitingCollision[0] = false;
                    collisionTimerRunning[0] = true;
                    secondsSinceCollision[0] = 0.0;
                    for (Motor m : body.motors) {
                        if (!"neck".equals(m.name)) {
                            m.enabled = false;
                        }
                    }
                } else if (collisionTimerRunning[0]) {
                    secondsSinceCollision[0] += 1.0 / 60.0;
                    if (secondsSinceCollision[0] >= JUMP_RAGDOLL_DELAY_SECONDS) {
                        collisionTimerRunning[0] = false;
                        world.suppressAutoLanding = true;
                        body.launch(new TupleD(0.0, 0.0)); // drops every anchor, zero kick
                        landingSuppressionFramesLeft[0] = RAGDOLL_RELEASE_SUPPRESSION_FRAMES;
                    }
                } else if (landingSuppressionFramesLeft[0] > 0) {
                    landingSuppressionFramesLeft[0]--;
                    if (landingSuppressionFramesLeft[0] == 0) {
                        world.suppressAutoLanding = false;
                        awaitingSecondCollision[0] = true;
                    }
                } else if (awaitingSecondCollision[0] && !body.anchors.isEmpty()) {
                    // the next real collision, after actually falling away from
                    // the release point -- start the recover-to-standing countdown.
                    awaitingSecondCollision[0] = false;
                    secondCollisionTimerRunning[0] = true;
                    secondsSinceSecondCollision[0] = 0.0;
                } else if (secondCollisionTimerRunning[0]) {
                    secondsSinceSecondCollision[0] += 1.0 / 60.0;
                    if (secondsSinceSecondCollision[0] >= JUMP_RECOVER_DELAY_SECONDS) {
                        secondCollisionTimerRunning[0] = false;
                        jumpPendingStandRecovery[0] = true;
                        Body standing = new Body();
                        try {
                            standing.buildFromConfig(JUMP_STAND_POSE_PATH);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        targetPose[0] = standing;
                        Tip fixpointTip = findBoneByName(body, "shinL").tips[1]; // left foot
                        poseMorpher.setTarget(standing, JUMP_MORPH_SECONDS, fixpointTip.position, fixpointTip);
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
            awaitingCollision[0] = false;
            collisionTimerRunning[0] = false;
            landingSuppressionFramesLeft[0] = 0;
            awaitingSecondCollision[0] = false;
            secondCollisionTimerRunning[0] = false;
            jumpPendingStandRecovery[0] = false;
            world.suppressAutoLanding = false;

            viewer.clear();
            drawObstacles(world);
            body.draw();
            viewer.startPlaying();
        };
    }

    // -------------------------------------------------------------------------
    // A Bugaboo-style rock field, replacing the single wall: a scattered climb
    // of platforms rising up and to the left, meant to be reached by a sequence
    // of jumps (angle/speed to be aimed per jump -- not built here, see the
    // outlook this was requested against). The first rock sits almost exactly
    // on the CURRENT scripted jump's own ballistic arc (JUMP_LAUNCH_SPEED at
    // JUMP_LAUNCH_ANGLE_DEG from JUMP_START_X lands on it essentially as-is);
    // each rock after that is progressively higher and further left than a
    // single unmodified jump reaches, so climbing further requires a
    // differently-aimed jump launched from the rock just reached -- the
    // "finding the right jump" challenge the outlook describes, once that
    // per-jump aiming actually exists. The last one is the highest platform,
    // i.e. the goal.
    private static List<Polygon> buildRockField() {
        List<Polygon> rocks = new ArrayList<>();
        rocks.add(buildRock(1.7, 1.3, 0.55, 10.0, 1));
        rocks.add(buildRock(-0.5, 1.9, 0.55, -15.0, 2));
        rocks.add(buildRock(-2.0, 2.5, 0.6, 12.0, 3));
        rocks.add(buildRock(-3.5, 3.1, 0.55, -10.0, 4));
        rocks.add(buildRock(-4.8, 3.8, 0.7, 0.0, 5)); // highest platform: the goal
        return rocks;
    }

    // -------------------------------------------------------------------------
    // one irregular rock/boulder: a jittered nonagon around (centerX, centerY),
    // each vertex's own radius randomized between 70% and 100% of `radius` (but
    // seeded, so the shape is reproducible run to run), then rotated by tiltDeg
    // for visual variety -- purely cosmetic irregularity, doesn't affect
    // collision beyond the resulting polygon's own actual shape.
    private static Polygon buildRock(double centerX, double centerY, double radius, double tiltDeg, long seed) {
        int n = 9;
        Random rnd = new Random(seed);
        TupleD center = new TupleD(centerX, centerY);
        double tiltRad = Math.toRadians(tiltDeg);
        TupleD[] vertices = new TupleD[n];
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            double r = radius * (0.7 + 0.3 * rnd.nextDouble());
            TupleD local = new TupleD(r * Math.cos(angle), r * Math.sin(angle));
            vertices[i] = local.rotate(tiltRad, new TupleD(0, 0)).add(center);
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
        world.suppressAutoLanding = false;
        try {
            body.buildFromConfig("src/assets/Initial.json");
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
