package skeleton;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import main.Main;
import skeleton.attachables.Anchor;
import skeleton.attachables.FrictionPad;
import skeleton.attachables.Joint;
import skeleton.attachables.Motor;
import utils.Json;
import utils.TupleD;
import viewer.Viewer;

public class Body {

    public String name = "";

    // bone[0].tips[0] is the graph's root
    public Bone[] bone;

    // adjacency: for each bone, the joints attached to either of its tips
    public Map<Bone, List<Joint>> boneGraph = new LinkedHashMap<>();

    // flat registries an animation.World needs to walk each step, built alongside
    // boneGraph so it doesn't have to re-scan every tip's attachables every frame
    public List<Joint> joints = new ArrayList<>();
    public List<Motor> motors = new ArrayList<>();
    public Map<Tip, FrictionPad> frictionPads = new LinkedHashMap<>();

    // every tip, keyed by its "<BoneName>_<TipName>" name -- built alongside
    // bone[] so a tip can be looked up directly instead of scanning bone[]/tips[].
    public Map<String, Tip> tipByName = new HashMap<>();

    // external behaviors (e.g. a HipBalancer) that animation.World runs once per
    // substep, alongside but separate from the skeleton's own joints/motors
    public List<PhysicalUpgrades> physicalUpgrades = new ArrayList<>();

    // the raw JSON each entry above was built from -- a PhysicalUpgrades
    // instance is bound to whichever Body's own Motors it was constructed
    // against, so a target pose's upgrades (e.g. a different jitter
    // amplitude) can't just be copied onto another Body's list directly; this
    // is what applyPhysicalUpgradesFrom replays instead.
    public List<Map<String, Object>> physicalUpgradesConfig = new ArrayList<>();

    // pins added/removed at runtime (e.g. by a mouse click), never part of the
    // JSON -- see toggleAnchor.
    public List<Anchor> anchors = new ArrayList<>();
    private int anchorCounter = 0;

    // world units a click may be from a tip/anchor and still count as "on" it.
    private static final double ANCHOR_CLICK_RADIUS = 0.15;

    // a fixed screen size regardless of zoom, since it's a UI marker, not part
    // of the figure's own geometry.
    private static final double ANCHOR_MARKER_RADIUS = Viewer.pixelsToWorldLength(10.0);

    // same gains verified (empirically, against light limb-sized tips) to hold
    // firmly without the PD loop overshooting into instability -- see Anchor.
    private static final double ANCHOR_STIFFNESS = 250.0;
    private static final double ANCHOR_DAMPING = 35.0;
    private static final double ANCHOR_MAX_TORQUE = 20000.0;

    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public void buildFromConfig(String filename) throws IOException {
        String text = Files.readString(Paths.get(filename));
        Map<String, Object> root = (Map<String, Object>) Json.parse(text);
        Map<String, Object> bodyJson = (Map<String, Object>) root.get("Body");
        name = (String) bodyJson.get("Name");

        // the root's own position isn't derivable from anything else (every
        // other bone's position comes from its parent via recomputeGeometry),
        // so it needs its own optional field -- absent for an ordinary
        // structural config (Main.java then positions it externally), present
        // for a pose file written by storePose so the whole pose is self-contained.
        List<Object> rootPositionJson = (List<Object>) bodyJson.get("RootPosition");
        TupleD rootPosition = rootPositionJson != null
                ? new TupleD(((Number) rootPositionJson.get(0)).doubleValue(), ((Number) rootPositionJson.get(1)).doubleValue())
                : new TupleD(0, 0);

        List<Object> bonesJson = (List<Object>) bodyJson.get("Bones");
        Map<String, Bone> boneByName = new HashMap<>();
        tipByName.clear(); // old entries reference Tips this rebuild is about to discard
        bone = new Bone[bonesJson.size()];
        for (int i = 0; i < bonesJson.size(); i++) {
            Map<String, Object> b = (Map<String, Object>) bonesJson.get(i);
            String boneName = (String) b.get("Name");
            double length = ((Number) b.get("Length")).doubleValue();
            double angleDeg = ((Number) b.get("Angle")).doubleValue();
            double mass = b.containsKey("Mass") ? ((Number) b.get("Mass")).doubleValue() : 1.0;
            TupleD position = i == 0 ? rootPosition : new TupleD(0, 0);

            Bone newBone = "head".equals(b.get("Type"))
                    ? new HeadBone(boneName, position, length, angleDeg)
                    : new Bone(boneName, position, length, angleDeg);
            newBone.setMass(mass);

            String tip0Name = b.containsKey("Tip0") ? (String) b.get("Tip0") : "0";
            String tip1Name = b.containsKey("Tip1") ? (String) b.get("Tip1") : "1";
            newBone.tips[0].name = boneName + "_" + tip0Name;
            newBone.tips[1].name = boneName + "_" + tip1Name;
            tipByName.put(newBone.tips[0].name, newBone.tips[0]);
            tipByName.put(newBone.tips[1].name, newBone.tips[1]);

            bone[i] = newBone;
            boneByName.put(boneName, newBone);
        }

        joints.clear();
        motors.clear();
        frictionPads.clear();
        physicalUpgrades.clear();
        anchors.clear(); // old anchors reference Tips this rebuild is about to discard
        Map<String, Joint> jointByName = new HashMap<>();
        List<Object> attachablesJson = (List<Object>) bodyJson.get("Attachables");
        if (attachablesJson != null) {
            // pass 1: joints, so every motor below can resolve its joint by name
            // regardless of where it appears in the file
            for (Object o : attachablesJson) {
                Map<String, Object> a = (Map<String, Object>) o;
                if (!"joint".equals(a.get("Type"))) {
                    continue;
                }
                String jointName = (String) a.get("name");
                Bone b0 = boneByName.get((String) a.get("Bone0"));
                int tipIdx0 = ((Number) a.get("TipIdx0")).intValue();
                Bone b1 = boneByName.get((String) a.get("Bone1"));
                int tipIdx1 = ((Number) a.get("TipIdx1")).intValue();

                Tip t0 = b0.tips[tipIdx0];
                Tip t1 = b1.tips[tipIdx1];
                Joint joint = new Joint(jointName, t0, t1);
                t0.addAttachable(joint);
                t1.addAttachable(joint);
                joints.add(joint);
                jointByName.put(jointName, joint);
            }

            // pass 2: motors, each referencing an already-built joint by name
            for (Object o : attachablesJson) {
                Map<String, Object> a = (Map<String, Object>) o;
                if (!"motor".equals(a.get("Type"))) {
                    continue;
                }
                String motorName = (String) a.get("name");
                Joint joint = jointByName.get((String) a.get("Joint"));
                double targetAngleDeg = ((Number) a.get("TargetAngle")).doubleValue();
                double stiffness = ((Number) a.get("Stiffness")).doubleValue();
                double damping = ((Number) a.get("Damping")).doubleValue();
                double maxTorque = ((Number) a.get("MaxTorque")).doubleValue();

                Motor motor = new Motor(motorName, joint, targetAngleDeg, stiffness, damping, maxTorque);
                Object enabledJson = a.get("Enabled");
                if (enabledJson != null) {
                    motor.enabled = (Boolean) enabledJson;
                }

                joint.tips[0].addAttachable(motor);
                joint.tips[1].addAttachable(motor);
                motors.add(motor);
            }

            // pass 3: friction pads, each sitting directly on one bone's tip --
            // no joint/motor relationship involved
            for (Object o : attachablesJson) {
                Map<String, Object> a = (Map<String, Object>) o;
                if (!"frictionPad".equals(a.get("Type"))) {
                    continue;
                }
                String padName = (String) a.get("name");
                Bone b = boneByName.get((String) a.get("Bone"));
                int tipIdx = ((Number) a.get("TipIdx")).intValue();
                double frictionCoefficient = ((Number) a.get("FrictionCoefficient")).doubleValue();

                FrictionPad pad = new FrictionPad(padName, frictionCoefficient);
                b.tips[tipIdx].addAttachable(pad);
                frictionPads.put(b.tips[tipIdx], pad);
            }
        }

        List<Object> upgradesJson = (List<Object>) bodyJson.get("PhysicalUpgrades");
        physicalUpgradesConfig.clear();
        if (upgradesJson != null) {
            for (Object o : upgradesJson) {
                Map<String, Object> upgradeJson = (Map<String, Object>) o;
                physicalUpgradesConfig.add(upgradeJson);
                addPhysicalUpgrade(upgradeJson);
            }
        }

        buildBoneGraph(joints);
        recomputeGeometry();

        // anchors are reconstructed last, once the pose they pin is already
        // correct -- Anchor's constructor freezes each participant's CURRENT
        // angle/position, so this only works after recomputeGeometry() has
        // already put every bone where this config says it belongs.
        if (attachablesJson != null) {
            for (Object o : attachablesJson) {
                Map<String, Object> a = (Map<String, Object>) o;
                if (!"anchor".equals(a.get("Type"))) {
                    continue;
                }
                String tipName = (String) a.get("Name");
                boolean angleEnabled = !a.containsKey("AngleEnabled") || (Boolean) a.get("AngleEnabled");
                addAnchor(tipName, angleEnabled);
            }
        }
    }

    // -------------------------------------------------------------------------
    // writes the CURRENT pose as a full skeleton config: the same schema as an
    // ordinary Skeletons.json (Bones/Attachables/PhysicalUpgrades), so it
    // reloads through the exact same buildFromConfig path. Each bone's Angle
    // is its actual current angle(), and each motor's TargetAngle is the
    // joint's actual current relative angle (Motor.relativeAngleRad) rather
    // than copying that motor's own in-progress target -- so recomputeGeometry
    // reproduces this exact pose on load, not wherever the motors were still
    // heading. The one addition over an ordinary config is "RootPosition",
    // the one position nothing else can derive.
    public void storePose(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"Body\": {\n");
        sb.append("    \"Name\": \"").append(name).append("\",\n");
        sb.append("    \"RootPosition\": [").append(bone[0].tips[0].position.first)
          .append(", ").append(bone[0].tips[0].position.second).append("],\n");

        sb.append("    \"Bones\": [\n");
        for (int i = 0; i < bone.length; i++) {
            Bone b = bone[i];
            sb.append("      {\"Name\": \"").append(b.name).append("\", ")
              .append("\"Tip0\": \"").append(b.tips[0].name.substring(b.name.length() + 1)).append("\", ")
              .append("\"Tip1\": \"").append(b.tips[1].name.substring(b.name.length() + 1)).append("\", ")
              .append("\"Length\": ").append(b.length).append(", ")
              .append("\"Angle\": ").append(Math.toDegrees(b.angle())).append(", ")
              .append("\"Mass\": ").append(b.mass);
            if (b instanceof HeadBone) {
                sb.append(", \"Type\": \"head\"");
            }
            sb.append("}").append(i < bone.length - 1 ? ",\n" : "\n");
        }
        sb.append("    ],\n");

        List<String> attachableEntries = new ArrayList<>();
        for (Joint j : joints) {
            attachableEntries.add("      {\"name\": \"" + j.name + "\", \"Type\": \"joint\", "
                    + "\"Bone0\": \"" + j.tips[0].bone.name + "\", \"TipIdx0\": " + j.tips[0].tipIdx + ", "
                    + "\"Bone1\": \"" + j.tips[1].bone.name + "\", \"TipIdx1\": " + j.tips[1].tipIdx + "}");
        }
        for (Motor m : motors) {
            double currentTargetDeg = Math.toDegrees(Motor.relativeAngleRad(m.joint));
            attachableEntries.add("      {\"name\": \"" + m.name + "\", \"Type\": \"motor\", "
                    + "\"Joint\": \"" + m.joint.name + "\", \"TargetAngle\": " + currentTargetDeg + ", "
                    + "\"Stiffness\": " + m.stiffness + ", \"Damping\": " + m.damping + ", "
                    + "\"MaxTorque\": " + m.maxTorque + ", \"Enabled\": " + m.enabled + "}");
        }
        for (Map.Entry<Tip, FrictionPad> e : frictionPads.entrySet()) {
            Tip tip = e.getKey();
            FrictionPad pad = e.getValue();
            attachableEntries.add("      {\"name\": \"" + pad.name + "\", \"Type\": \"frictionPad\", "
                    + "\"Bone\": \"" + tip.bone.name + "\", \"TipIdx\": " + tip.tipIdx + ", "
                    + "\"FrictionCoefficient\": " + pad.frictionCoefficient + "}");
        }
        // one entry per Anchor, naming any single one of its participant tips --
        // addAnchor rediscovers the whole group (all bones meeting at that
        // point) from just that one name on load, so only one is needed.
        for (Anchor a : anchors) {
            attachableEntries.add("      {\"Name\": \"" + a.tipsSnapshot()[0].name + "\", \"Type\": \"anchor\", "
                    + "\"AngleEnabled\": " + a.angleEnabled + "}");
        }
        sb.append("    \"Attachables\": [\n").append(String.join(",\n", attachableEntries)).append("\n    ],\n");

        List<String> upgradeEntries = new ArrayList<>();
        for (PhysicalUpgrades u : physicalUpgrades) {
            if (u instanceof HipBalancer) {
                upgradeEntries.add("{\"Type\": \"HipBalancer\"}");
            } else if (u instanceof HandJitter) {
                upgradeEntries.add("{\"Type\": \"HandJitter\", \"AmplitudeDeg\": " + ((HandJitter) u).amplitudeDeg + "}");
            } else if (u instanceof ShoulderJitter) {
                upgradeEntries.add("{\"Type\": \"ShoulderJitter\", \"AmplitudeDeg\": " + ((ShoulderJitter) u).amplitudeDeg + "}");
            }
        }
        sb.append("    \"PhysicalUpgrades\": [").append(String.join(", ", upgradeEntries)).append("]\n");

        sb.append("  }\n}\n");
        Files.writeString(file.toPath(), sb.toString());
    }

    // -------------------------------------------------------------------------
    // loads a pose written by storePose -- same schema as an ordinary
    // Skeletons.json, so this is just buildFromConfig by another name.
    public void loadPose(File file) throws IOException {
        buildFromConfig(file.getAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // rebuilds this Body's PhysicalUpgrades from another Body's own stored
    // config (e.g. a just-completed morph's target pose) -- reconstructs
    // fresh instances bound to THIS body's own motors/bones, never the
    // other's, since a target pose loaded for morphing is a separate,
    // never-stepped Body whose own Motor objects aren't the ones actually
    // being simulated. E.g. so morphing into a pose with a different jitter
    // amplitude actually takes effect, not just that pose's anchors/geometry.
    public void applyPhysicalUpgradesFrom(Body other) {
        physicalUpgrades.clear();
        physicalUpgradesConfig.clear();
        for (Map<String, Object> upgradeJson : other.physicalUpgradesConfig) {
            physicalUpgradesConfig.add(upgradeJson);
            addPhysicalUpgrade(upgradeJson);
        }
    }

    // -------------------------------------------------------------------------
    // recognizes upgrade types from the "PhysicalUpgrades" JSON list and
    // constructs them, deriving whatever they need (torso, specific motors)
    // from what buildFromConfig has already built -- the JSON only ever names
    // the type plus its own tunable parameters (e.g. HandJitter/ShoulderJitter's
    // AmplitudeDeg), since e.g. a HipBalancer's wiring (which bones/motors) is
    // implied by this skeleton's own structure, not separate data to author.
    private void addPhysicalUpgrade(Map<String, Object> upgradeJson) {
        String type = (String) upgradeJson.get("Type");
        if ("HipBalancer".equals(type)) {
            Motor hipL = null;
            Motor hipR = null;
            for (Motor m : motors) {
                if ("hipL".equals(m.name)) {
                    hipL = m;
                } else if ("hipR".equals(m.name)) {
                    hipR = m;
                }
            }
            physicalUpgrades.add(new HipBalancer(bone[0], new Motor[]{hipL, hipR}, bone[0].angleDeg));
        } else if ("HandJitter".equals(type)) {
            Motor elbowL = null;
            Motor elbowR = null;
            for (Motor m : motors) {
                if ("elbowL".equals(m.name)) {
                    elbowL = m;
                } else if ("elbowR".equals(m.name)) {
                    elbowR = m;
                }
            }
            double amplitudeDeg = ((Number) upgradeJson.get("AmplitudeDeg")).doubleValue();
            physicalUpgrades.add(new HandJitter(new Motor[]{elbowL, elbowR}, amplitudeDeg));
        } else if ("ShoulderJitter".equals(type)) {
            Motor shoulderL = null;
            Motor shoulderR = null;
            for (Motor m : motors) {
                if ("shoulderL".equals(m.name)) {
                    shoulderL = m;
                } else if ("shoulderR".equals(m.name)) {
                    shoulderR = m;
                }
            }
            double amplitudeDeg = ((Number) upgradeJson.get("AmplitudeDeg")).doubleValue();
            physicalUpgrades.add(new ShoulderJitter(new Motor[]{shoulderL, shoulderR}, amplitudeDeg));
        }
    }

    // -------------------------------------------------------------------------
    // the tip (across every bone) whose current position is nearest `point` --
    // e.g. for a mouse-drag interaction picking which tip to grab.
    public Tip closestTip(TupleD point) {
        Tip closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (Bone b : bone) {
            for (Tip tip : b.tips) {
                double dx = tip.position.first - point.first;
                double dy = tip.position.second - point.second;
                double distSq = dx * dx + dy * dy;
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = tip;
                }
            }
        }
        return closest;
    }

    // -------------------------------------------------------------------------
    // every tip coincident with the given seed, discovered by walking Joints
    // outward from it -- a leaf seed yields just itself; a branching point
    // like the hip pulls in every bone jointed there, directly or
    // transitively. Also collects every Joint found to be INTERNAL to the
    // group (both its endpoints ended up in it), so Anchor can disable their
    // Motors for its lifetime.
    private void collectAnchorGroup(Tip seedTip, List<Tip> groupTips, List<Joint> internalJoints) {
        groupTips.add(seedTip);

        boolean added = true;
        while (added) {
            added = false;
            for (Joint j : joints) {
                if (internalJoints.contains(j)) {
                    continue;
                }
                boolean has0 = groupTips.contains(j.tips[0]);
                boolean has1 = groupTips.contains(j.tips[1]);
                if (has0 && has1) {
                    internalJoints.add(j);
                } else if (has0) {
                    groupTips.add(j.tips[1]);
                    internalJoints.add(j);
                    added = true;
                } else if (has1) {
                    groupTips.add(j.tips[0]);
                    internalJoints.add(j);
                    added = true;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    private boolean isTipAnchored(Tip tip) {
        for (Anchor a : anchors) {
            if (a.covers(tip)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // the nearest existing anchor to `point`, within radius -- or null.
    private Anchor closestAnchor(TupleD point, double radius) {
        Anchor closest = null;
        double closestDistSq = radius * radius;
        for (Anchor a : anchors) {
            double dx = a.position().first - point.first;
            double dy = a.position().second - point.second;
            double distSq = dx * dx + dy * dy;
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = a;
            }
        }
        return closest;
    }

    // -------------------------------------------------------------------------
    // the nearest anchor to `point`, if one is within grab range -- e.g. so a
    // right-drag can pick up an existing anchor to reposition it.
    public Anchor grabAnchorNear(TupleD point) {
        return closestAnchor(point, ANCHOR_CLICK_RADIUS);
    }

    // -------------------------------------------------------------------------
    // clamps a proposed new position for `anchor` (e.g. from a right-drag) so
    // its distance to every OTHER active anchor never exceeds the skeletal
    // path length connecting them -- the true physical limit, reached only
    // once every bone along that path is fully straightened. No limit at all
    // if `anchor` is the only thing currently pinning the skeleton down.
    public TupleD clampAnchorDrag(Anchor anchor, TupleD desired) {
        TupleD candidate = desired;
        for (int iter = 0; iter < 4; iter++) {
            for (Anchor other : anchors) {
                if (other == anchor) {
                    continue;
                }
                double reach = pathLength(anchor, other);
                if (Double.isInfinite(reach)) {
                    continue;
                }
                TupleD center = other.position();
                TupleD delta = candidate.sub(center);
                double dist = delta.length();
                if (dist > reach && dist > 0.0) {
                    candidate = center.add(delta.times(reach / dist));
                }
            }
        }
        return candidate;
    }

    // -------------------------------------------------------------------------
    // shortest skeletal path length (sum of bone lengths; joints cost 0, since
    // they hold two tips coincident) from any of `from`'s participant tips to
    // any of `to`'s -- Dijkstra over a graph whose nodes are Tips: each bone
    // is an edge of weight = its length between its own two tips, each joint
    // an edge of weight 0 between the two tips it connects.
    private double pathLength(Anchor from, Anchor to) {
        Map<Tip, Double> dist = new HashMap<>();
        PriorityQueue<Tip> queue = new PriorityQueue<>((a, b) -> Double.compare(dist.get(a), dist.get(b)));
        Set<Tip> visited = new HashSet<>();

        for (Tip t : from.tipsSnapshot()) {
            dist.put(t, 0.0);
            queue.add(t);
        }
        Set<Tip> targets = new HashSet<>();
        for (Tip t : to.tipsSnapshot()) {
            targets.add(t);
        }

        while (!queue.isEmpty()) {
            Tip current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            double d = dist.get(current);
            if (targets.contains(current)) {
                return d;
            }
            for (Edge e : neighborEdges(current)) {
                double nd = d + e.weight;
                Double old = dist.get(e.tip);
                if (old == null || nd < old) {
                    dist.put(e.tip, nd);
                    queue.add(e.tip);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    // -------------------------------------------------------------------------
    private List<Edge> neighborEdges(Tip tip) {
        List<Edge> edges = new ArrayList<>();
        for (Bone b : bone) {
            if (b.tips[0] == tip) {
                edges.add(new Edge(b.tips[1], b.length));
            } else if (b.tips[1] == tip) {
                edges.add(new Edge(b.tips[0], b.length));
            }
        }
        for (Joint j : joints) {
            if (j.tips[0] == tip) {
                edges.add(new Edge(j.tips[1], 0.0));
            } else if (j.tips[1] == tip) {
                edges.add(new Edge(j.tips[0], 0.0));
            }
        }
        return edges;
    }

    private static class Edge {
        final Tip tip;
        final double weight;

        Edge(Tip tip, double weight) {
            this.tip = tip;
            this.weight = weight;
        }
    }

    // -------------------------------------------------------------------------
    // the nearest unanchored tip within radius -- or null. Any tip is a valid
    // seed; toggleAnchor resolves the rest of its group (if any) from there.
    private Tip closestUnanchoredTip(TupleD point, double radius) {
        Tip closest = null;
        double closestDistSq = radius * radius;
        for (Tip tip : tipByName.values()) {
            if (isTipAnchored(tip)) {
                continue;
            }
            double dx = tip.position.first - point.first;
            double dy = tip.position.second - point.second;
            double distSq = dx * dx + dy * dy;
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = tip;
            }
        }
        return closest;
    }

    // -------------------------------------------------------------------------
    // programmatic equivalent of a GUI click that pins a tip: pins the whole
    // group of bones meeting at the named tip (just that one bone for a leaf,
    // every bone jointed there for a branching point like the hip). A no-op
    // if the name doesn't resolve to a tip, or that tip is already anchored.
    public void addAnchor(String tipName, boolean angleEnabled) {
        Tip seedTip = tipByName.get(tipName);
        if (seedTip == null || isTipAnchored(seedTip)) {
            return;
        }

        List<Tip> groupTips = new ArrayList<>();
        List<Joint> internalJoints = new ArrayList<>();
        collectAnchorGroup(seedTip, groupTips, internalJoints);

        List<Motor> internalMotors = new ArrayList<>();
        for (Joint j : internalJoints) {
            Motor m = findMotorForJoint(j);
            if (m != null) {
                internalMotors.add(m);
            }
        }

        Anchor anchor = new Anchor("anchor" + (anchorCounter++),
                groupTips.toArray(new Tip[0]), internalMotors.toArray(new Motor[0]),
                ANCHOR_STIFFNESS, ANCHOR_DAMPING, ANCHOR_MAX_TORQUE, angleEnabled);
        anchors.add(anchor);
    }

    // -------------------------------------------------------------------------
    // programmatic equivalent of a GUI click that un-pins an anchor: releases
    // whichever anchor covers the named tip. A no-op if the name doesn't
    // resolve to a tip, or nothing currently anchors it.
    public void removeAnchor(String tipName) {
        Tip tip = tipByName.get(tipName);
        if (tip == null) {
            return;
        }
        Anchor found = findAnchorCovering(tip);
        if (found != null) {
            found.release();
            anchors.remove(found);
        }
    }

    // -------------------------------------------------------------------------
    // releases the named anchor's POSITION lock (its angle lock, if any,
    // keeps running -- it stays in `anchors` so apply() still holds this
    // bone's orientation via real torque every substep) and gives every
    // participant tip the same one-time velocity kick, so the whole group
    // flies off together as a single rigid unit -- e.g. launching a
    // flying-pose hip anchor into a gravity-driven parabola while it keeps
    // holding that pose in the air. A no-op if the name doesn't resolve to an
    // anchored tip.
    public void launchAnchor(String tipName, TupleD velocityKick) {
        Tip tip = tipByName.get(tipName);
        if (tip == null) {
            return;
        }
        Anchor found = findAnchorCovering(tip);
        if (found != null) {
            found.releasePosition();
            found.kick(velocityKick);
        }
    }

    // -------------------------------------------------------------------------
    // disables every active anchor (position lock AND angle lock both go
    // away, unlike launchAnchor's single-anchor releasePosition) and gives
    // every tip in the body the same one-time velocity kick. Gravity is
    // mass-independent (World.substep) and every tip now shares the same
    // velocity, so absent any other force the whole body would free-fall as
    // a perfectly rigid unit. Motors are deliberately left enabled, though:
    // a Motor realizes its torque as reaction forces at BOTH ends of its
    // joint (see Motor.apply), so an active one (e.g. HandJitter's cosmetic
    // idle-sway) reacts back into the rest of the body and visibly rotates
    // it during flight -- confirmed to look fine (a gradual settling drift,
    // not a tumble) once HipBalancer, whose strong hip motors fight a
    // "falling over" reading that doesn't apply mid-air, was removed from
    // the poses that use this. E.g. for a "kick" step right after morphing
    // into a held flying pose.
    public void launch(TupleD velocityKick) {
        for (Anchor a : new ArrayList<>(anchors)) {
            a.release();
            anchors.remove(a);
        }
        for (Tip tip : tipByName.values()) {
            tip.velocity = tip.velocity.add(velocityKick);
        }
    }

    // -------------------------------------------------------------------------
    private Anchor findAnchorCovering(Tip tip) {
        for (Anchor a : anchors) {
            if (a.covers(tip)) {
                return a;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // click handling for Anchor: a click near an EXISTING anchor removes it
    // (toggle off) -- checked first, so clicking an already-pinned point never
    // just re-pins it. Otherwise, a click near an unanchored tip pins it via
    // addAnchor. A no-op if neither is within ANCHOR_CLICK_RADIUS. shiftHeld
    // creates the anchor with angleEnabled=false -- position-only, no angle lock.
    public void toggleAnchor(TupleD point, boolean shiftHeld) {
        Anchor existing = closestAnchor(point, ANCHOR_CLICK_RADIUS);
        if (existing != null) {
            removeAnchor(existing.tipsSnapshot()[0].name);
            return;
        }

        Tip seedTip = closestUnanchoredTip(point, ANCHOR_CLICK_RADIUS);
        if (seedTip == null) {
            return;
        }

        addAnchor(seedTip.name, !shiftHeld);
    }

    // -------------------------------------------------------------------------
    public void draw() {
        for (Bone b : bone) {
            b.draw();
        }
        for (Anchor a : anchors) {
            Color markerColor = a.angleEnabled ? Color.RED : Color.YELLOW;
            Main.viewer.drawCircle(a.position(), ANCHOR_MARKER_RADIUS, markerColor);
        }
    }

    // -------------------------------------------------------------------------
    private void buildBoneGraph(List<Joint> joints) {
        boneGraph.clear();
        for (Bone b : bone) {
            boneGraph.put(b, new ArrayList<>());
        }
        for (Joint j : joints) {
            boneGraph.get(j.tips[0].bone).add(j);
            boneGraph.get(j.tips[1].bone).add(j);
        }
    }

    // -------------------------------------------------------------------------
    // root = bone[0], tips[0]: fixed in place, everything else positioned outward
    // from it by walking boneGraph and, at each joint, carrying the shared point
    // over to the next bone before deriving that bone's other tip from its own
    // length/angle.
    public void recomputeGeometry() {
        recomputeGeometry(bone[0].tips[0]);
    }

    // -------------------------------------------------------------------------
    // same walk, but pivoting around an arbitrary tip instead of always
    // bone[0]/tips[0] -- e.g. so a PoseMorpher can keep a chosen hand or foot
    // fixed while the rest of the body re-poses around it.
    public void recomputeGeometry(Tip rootTip) {
        rootTip.bone.recomputeTip(rootTip.tipIdx);
        Set<Bone> visited = new HashSet<>();
        visited.add(rootTip.bone);
        recomputeGeometry(rootTip.bone, visited, true);
    }

    // -------------------------------------------------------------------------
    // same walk as recomputeGeometry(Tip), but never derives a bone's angle
    // from its joint's motor -- it only carries tip POSITIONS outward via
    // joint coincidence, leaving every bone's angleDeg exactly as the caller
    // already set it. For a caller (PoseMorpher) that independently
    // interpolates each bone's own absolute angle directly: chaining
    // recomputeGeometry's motor-derived relative angles through several
    // joints can compose into a near-360-degree sweep for a bone far from
    // the root even when every individual relative-angle lerp was itself
    // under 180 degrees, since "each term shortest" doesn't imply "sum
    // shortest". Position propagation has no such issue -- a position is a
    // point, not an angle, so there's no "long way around" to take.
    public void propagatePositions(Tip rootTip) {
        rootTip.bone.recomputeTip(rootTip.tipIdx);
        Set<Bone> visited = new HashSet<>();
        visited.add(rootTip.bone);
        recomputeGeometry(rootTip.bone, visited, false);
    }

    private void recomputeGeometry(Bone b, Set<Bone> visited, boolean deriveAngleFromMotor) {
        for (Joint j : boneGraph.get(b)) {
            int selfIdx = (j.tips[0].bone == b) ? 0 : 1;
            int otherIdx = 1 - selfIdx;
            Tip otherTipObj = j.tips[otherIdx];
            Bone other = otherTipObj.bone;
            if (visited.contains(other)) {
                continue;
            }

            int selfTip = j.tips[selfIdx].tipIdx;
            int otherTip = otherTipObj.tipIdx;

            // if this joint has a motor, `other`'s angle is DERIVED from the
            // motor's own target (using the same joint-outward convention
            // Motor.apply() uses) rather than kept as its own independently
            // authored "Angle" -- so the built pose always starts at exactly
            // zero motor error, instead of the two numbers silently drifting
            // out of sync as target angles get tuned separately from bone
            // angles. Skipped when deriveAngleFromMotor is false -- the
            // caller has already set `other.angleDeg` itself and this walk
            // should only carry position (see propagatePositions).
            if (deriveAngleFromMotor) {
                Motor motor = findMotorForJoint(j);
                if (motor != null) {
                    double selfOutwardDeg = (selfTip == 0) ? b.angleDeg : b.angleDeg + 180.0;
                    double sign = (selfIdx == 0) ? 1.0 : -1.0;
                    double otherOutwardDeg = selfOutwardDeg + sign * motor.targetAngleDeg;
                    other.angleDeg = (otherTip == 0) ? otherOutwardDeg : otherOutwardDeg - 180.0;
                }
            }

            other.tips[otherTip].position = b.tips[selfTip].position;
            other.recomputeTip(otherTip);

            visited.add(other);
            recomputeGeometry(other, visited, deriveAngleFromMotor);
        }
    }

    // -------------------------------------------------------------------------
    private Motor findMotorForJoint(Joint j) {
        for (Motor m : motors) {
            if (m.joint == j) {
                return m;
            }
        }
        return null;
    }
}
