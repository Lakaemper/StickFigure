/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package main;

import animation.Polygon;
import animation.PoseMorpher;
import animation.World;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.DoubleConsumer;
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

    // JUMP sequence constants -- see onJump/onStep wiring below. _L/_R are
    // horizontally-mirrored pairs (mirrored around torso/tip0); which one
    // loads is picked by jumpDirectionSign via chargePosePathFor/
    // flyingPosePathFor below.
    private static final String JUMP_CHARGE_POSE_PATH_L = "src/assets/Pose_Charging_L.json";
    private static final String JUMP_CHARGE_POSE_PATH_R = "src/assets/Pose_Charging_R.json";
    private static final String JUMP_FLYING_POSE_PATH_L = "src/assets/Pose_Flying_L.json";
    private static final String JUMP_FLYING_POSE_PATH_R = "src/assets/Pose_Flying_R.json";
    private static final double JUMP_START_X = 4.5; // near the viewer's right edge; this
                                                      // charging pose leans left from the
                                                      // hip, so the hip itself is the body's
                                                      // rightmost point
    private static final double JUMP_CHARGE_SECONDS = 1.0; // for the JUMP button, a fixed
            // hold duration; for a keypress jump, the hold time (up to this cap) that
            // maps the charge fraction below to 0..1
    private static final double JUMP_MORPH_SECONDS = 0.25; // standing->charging and charging->flying
    private static final double JUMP_RECOVERY_MORPH_SECONDS = 0.25; // ragdoll->standing recovery
    private static final double JUMP_LAUNCH_ANGLE_DEG = 55.0; // JUMP button only (fixed timing)
    private static final double JUMP_LAUNCH_SPEED = 10.1; // JUMP button only (fixed timing) --
            // needs enough range to reach the center-left wall obstacle while still airborne

    // keypress jump aiming: the longer 'o'/'p' is held (up to JUMP_CHARGE_SECONDS,
    // after which it's cropped), the faster and steeper the launch -- linearly
    // interpolated by the charge fraction computed in onStep's jumpCharging branch.
    private static final double JUMP_LAUNCH_SPEED_MIN = 1.0;
    private static final double JUMP_LAUNCH_SPEED_MAX = 12.0;
    private static final double JUMP_LAUNCH_ANGLE_MIN_DEG = 30.0;
    private static final double JUMP_LAUNCH_ANGLE_MAX_DEG = 60.0;

    // charge-meter: a horizontal bar below the figure (not a fixed world
    // height, so it stays put under the figure even standing on a raised
    // rock) showing the current charge fraction while a keypress jump is held.
    private static final double CHARGE_METER_GAP_BELOW_FIGURE = 0.2;
    private static final double CHARGE_METER_WIDTH = 2.0;
    private static final double CHARGE_METER_HEIGHT = 0.15;
    private static final double JUMP_RAGDOLL_DELAY_SECONDS = 1.0; // how long after the
            // FIRST post-launch collision the figure keeps hanging on its landed
            // anchors before they're all released, letting it fall/ragdoll freely
    private static final double JUMP_RECOVER_DELAY_SECONDS = 0.5; // how long after the
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
        viewer.loadBackgroundImage("src/assets/jungle2.png");

        Body body = new Body();
        World world = new World(body);
        world.obstacles.add(Polygon.load("src/assets/Rock_01.json", new TupleD(-4.0, 0.0)));
        world.obstacles.add(Polygon.load("src/assets/Rock_02.json", new TupleD(6.5, 0.0)));
        // Rock_01's own flat "back" edge sits exactly at its own placement x
        // (local x=0); Rock_02's sits at its placement x + 4.1 (its local
        // x-max, ~410px at its 100px/unit scale). A mirrored Rock_01 placed
        // at Rock_01's own back-x extends further left instead of right, so
        // the two backs coincide with fronts facing apart -- back to back.
        // An unmirrored Rock_01 placed at Rock_02's back-x does the same
        // thing the other way around: its own (unmirrored) back coincides
        // with Rock_02's back, extending further right.
        world.obstacles.add(Polygon.load("src/assets/Rock_01.json", new TupleD(-4.0, 0.0), true));
        world.obstacles.add(Polygon.load("src/assets/Rock_01.json", new TupleD(6.5 + 4.1, 0.0)));
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

        // keypress-triggered jump ('o'/'p'): unlike the JUMP button (which
        // teleports to JUMP_START_X and always launches left), this starts
        // from wherever the figure currently stands -- jumpPendingChargeStart
        // marks that the in-progress morph is standing->charging IN PLACE,
        // so its completion should start the ordinary jumpCharging hold
        // instead of anything else a morph-completion could mean. Once
        // launch actually happens, jumpDirectionSign picks which way (-1
        // left, +1 right); the JUMP button sets it to -1 to keep its own
        // existing always-left behavior.
        boolean[] jumpPendingChargeStart = {false};
        double[] jumpDirectionSign = {-1.0};

        // per-jump aiming: jumpIsKeyDriven distinguishes a keypress jump
        // (release-triggered, speed/angle scaled by hold duration) from the
        // JUMP button (fixed 1s hold, fixed speed/angle) inside the SAME
        // shared jumpCharging/launch logic below. jumpKeyHeld tracks whether
        // the key that started the current charge is still down; releasing
        // it is what ends the hold. jumpLaunchSpeed/jumpLaunchAngleDeg are
        // computed once, at the moment charging ends, and used at the actual
        // launch (which only happens later, once the flying-morph completes).
        boolean[] jumpIsKeyDriven = {false};
        boolean[] jumpKeyHeld = {false};
        double[] jumpLaunchSpeed = {0.0};
        double[] jumpLaunchAngleDeg = {0.0};

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
                    boolean standRecoveryJustCompleted = jumpPendingStandRecovery[0];
                    if (standRecoveryJustCompleted) {
                        // reached the standing pose -- motors can safely hold
                        // it again now that nothing is still half-pinned.
                        jumpPendingStandRecovery[0] = false;
                        for (Motor m : body.motors) {
                            m.enabled = true;
                        }
                    }
                    boolean chargeJustStarted = jumpPendingChargeStart[0];
                    if (chargeJustStarted) {
                        // reached the charging pose (morphed in place from
                        // wherever the figure was standing) -- start the same
                        // "hold the crouch under physics" phase the JUMP
                        // button starts immediately after loading Charging.json.
                        jumpPendingChargeStart[0] = false;
                        jumpChargeElapsed[0] = 0.0;
                        jumpCharging[0] = true;
                    }
                    if (jumpPendingLaunch[0]) {
                        // kick: drop every anchor (including the ones just
                        // reattached above) and launch, then keep playing --
                        // unlike a manual Morph, JUMP doesn't pause here.
                        // jumpLaunchSpeed/jumpLaunchAngleDeg were already
                        // decided back when charging ended (fixed values for
                        // the JUMP button, hold-duration-scaled for a keypress).
                        jumpPendingLaunch[0] = false;
                        double rad = Math.toRadians(jumpLaunchAngleDeg[0]);
                        body.launch(new TupleD(jumpDirectionSign[0] * jumpLaunchSpeed[0] * Math.cos(rad), jumpLaunchSpeed[0] * Math.sin(rad)));
                        awaitingCollision[0] = true;
                        collisionTimerRunning[0] = false;
                    } else if (!chargeJustStarted && !standRecoveryJustCompleted) {
                        viewer.stopPlaying();
                    }
                }
            } else {
                world.step();
                if (jumpCharging[0]) {
                    jumpChargeElapsed[0] += 1.0 / 60.0;
                    // a keypress jump ends its hold on key release; the JUMP
                    // button (fixed timing) still ends after JUMP_CHARGE_SECONDS.
                    boolean chargeDone = jumpIsKeyDriven[0]
                            ? !jumpKeyHeld[0]
                            : jumpChargeElapsed[0] >= JUMP_CHARGE_SECONDS;
                    if (chargeDone) {
                        jumpCharging[0] = false;
                        jumpPendingLaunch[0] = true;
                        if (jumpIsKeyDriven[0]) {
                            double fraction = Math.max(0.0, Math.min(1.0, jumpChargeElapsed[0] / JUMP_CHARGE_SECONDS));
                            jumpLaunchSpeed[0] = JUMP_LAUNCH_SPEED_MIN + fraction * (JUMP_LAUNCH_SPEED_MAX - JUMP_LAUNCH_SPEED_MIN);
                            jumpLaunchAngleDeg[0] = JUMP_LAUNCH_ANGLE_MIN_DEG + fraction * (JUMP_LAUNCH_ANGLE_MAX_DEG - JUMP_LAUNCH_ANGLE_MIN_DEG);
                        } else {
                            jumpLaunchSpeed[0] = JUMP_LAUNCH_SPEED;
                            jumpLaunchAngleDeg[0] = JUMP_LAUNCH_ANGLE_DEG;
                        }
                        Body flying = new Body();
                        try {
                            flying.loadPose(new File(flyingPosePathFor(jumpDirectionSign[0])));
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
                        poseMorpher.setTarget(standing, JUMP_RECOVERY_MORPH_SECONDS, fixpointTip.position, fixpointTip);
                    }
                }
            }
            redraw(body, world);
            if (jumpCharging[0] && jumpIsKeyDriven[0]) {
                double fraction = Math.max(0.0, Math.min(1.0, jumpChargeElapsed[0] / JUMP_CHARGE_SECONDS));
                drawChargeMeter(body, fraction);
            }
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
            redraw(body, world);
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
                redraw(body, world);
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
            redraw(body, world);
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
            if (jumpCharging[0] || jumpPendingLaunch[0] || jumpPendingChargeStart[0] || poseMorpher.isActive()) {
                return;
            }
            try {
                body.loadPose(new File(JUMP_CHARGE_POSE_PATH_L));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            currentPoseFile = new File(JUMP_CHARGE_POSE_PATH_L);
            body.bone[0].tips[0].position = new TupleD(JUMP_START_X, body.bone[0].tips[0].position.second);
            body.recomputeGeometry();

            jumpDirectionSign[0] = -1.0; // this button always launches left, as before
            jumpIsKeyDriven[0] = false; // fixed hold duration, fixed speed/angle
            jumpKeyHeld[0] = false;
            jumpChargeElapsed[0] = 0.0;
            jumpCharging[0] = true;
            jumpPendingLaunch[0] = false;
            jumpPendingChargeStart[0] = false;
            awaitingCollision[0] = false;
            collisionTimerRunning[0] = false;
            landingSuppressionFramesLeft[0] = 0;
            awaitingSecondCollision[0] = false;
            secondCollisionTimerRunning[0] = false;
            jumpPendingStandRecovery[0] = false;
            world.suppressAutoLanding = false;

            redraw(body, world);
            viewer.startPlaying();
        };

        // keypress jump ('o' left / 'p' right, see Viewer's KeyListener):
        // unlike the JUMP button, this starts from wherever the figure
        // currently stands -- morphs in place to the charging pose first
        // (jumpPendingChargeStart), then onStep's own morph-completion
        // handling falls into the ordinary charge/fly/launch sequence, using
        // directionSign for which way the final launch kicks. Ignored while
        // any jump/morph/recovery sequence is already in progress.
        DoubleConsumer startKeyJump = directionSign -> {
            if (jumpCharging[0] || jumpPendingLaunch[0] || jumpPendingChargeStart[0] || poseMorpher.isActive()) {
                return;
            }
            Body charging = new Body();
            try {
                charging.loadPose(new File(chargePosePathFor(directionSign)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            jumpDirectionSign[0] = directionSign;
            jumpIsKeyDriven[0] = true;
            jumpKeyHeld[0] = true; // released via viewer.onJumpKeyReleased below
            targetPose[0] = charging;
            jumpPendingChargeStart[0] = true;
            awaitingCollision[0] = false;
            collisionTimerRunning[0] = false;
            landingSuppressionFramesLeft[0] = 0;
            awaitingSecondCollision[0] = false;
            secondCollisionTimerRunning[0] = false;
            jumpPendingStandRecovery[0] = false;
            world.suppressAutoLanding = false;

            Tip fixpointTip = findBoneByName(body, "shinL").tips[1];
            poseMorpher.setTarget(charging, JUMP_MORPH_SECONDS, fixpointTip.position, fixpointTip);
            viewer.startPlaying();
        };
        viewer.onJumpLeft = () -> startKeyJump.accept(-1.0);
        viewer.onJumpRight = () -> startKeyJump.accept(1.0);

        // ends the current keypress jump's hold (see jumpKeyHeld above) --
        // a no-op if nothing is currently charging (e.g. the key came up
        // after a jump/launch already finished, or was never the one that
        // started a charge in the first place).
        viewer.onJumpKeyReleased = () -> jumpKeyHeld[0] = false;
    }

    // -------------------------------------------------------------------------
    // a horizontal bar below the figure (its lowest tip, e.g. a foot -- not a
    // fixed world height, so it still sits right under the figure even
    // standing on a raised rock), centered under the hip: a light-gray
    // full-width track plus an orange fill growing left-to-right with the
    // charge fraction (0..1), giving live feedback on how long 'o'/'p' has
    // been held (see JUMP_LAUNCH_SPEED_MIN/MAX and the angle equivalents,
    // which the fraction ultimately maps to at launch time).
    private static void drawChargeMeter(Body body, double fraction) {
        double centerX = body.bone[0].tips[0].position.first;
        double lowestY = Double.POSITIVE_INFINITY;
        for (Bone b : body.bone) {
            lowestY = Math.min(lowestY, Math.min(b.tips[0].position.second, b.tips[1].position.second));
        }
        double meterY = lowestY - CHARGE_METER_GAP_BELOW_FIGURE;

        viewer.drawRect(new TupleD(centerX, meterY), CHARGE_METER_WIDTH, CHARGE_METER_HEIGHT,
                new Color(200, 200, 200));
        double fillWidth = CHARGE_METER_WIDTH * fraction;
        double fillCenterX = centerX - CHARGE_METER_WIDTH / 2.0 + fillWidth / 2.0;
        viewer.drawRect(new TupleD(fillCenterX, meterY), fillWidth, CHARGE_METER_HEIGHT, Color.ORANGE);
    }

    // -------------------------------------------------------------------------
    private static String chargePosePathFor(double directionSign) {
        return directionSign < 0.0 ? JUMP_CHARGE_POSE_PATH_L : JUMP_CHARGE_POSE_PATH_R;
    }

    // -------------------------------------------------------------------------
    private static String flyingPosePathFor(double directionSign) {
        return directionSign < 0.0 ? JUMP_FLYING_POSE_PATH_L : JUMP_FLYING_POSE_PATH_R;
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

        viewer.drawFloor();
        redraw(body, world);
    }

    // -------------------------------------------------------------------------
    // re-centers the camera on the figure's own neck (so the viewer scrolls
    // to follow it rather than staying fixed on the world origin), then
    // redraws everything -- shared by every site that changes the pose/
    // anchors/obstacles and needs the screen to reflect it. A no-op camera
    // move if the neck tip isn't found (e.g. a body built without a head).
    private static void redraw(Body body, World world) {
        Tip neckTip = body.tipByName.get("head_neck");
        if (neckTip != null) {
            viewer.setCameraFocus(neckTip.position);
        }
        viewer.clear();
        for (Polygon obstacle : world.obstacles) {
            obstacle.display();
        }
        body.draw();
    }

}
