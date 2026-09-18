package skeleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import skeleton.attachables.FrictionPad;
import skeleton.attachables.Joint;
import skeleton.attachables.Motor;
import utils.Json;
import utils.TupleD;

public class Body {

    public String name = "";

    // bone[0].tips[0] is the graph's root
    public Bone[] bone;

    // adjacency: for each bone, the joints attached to either of its tips
    public Map<Bone, List<Joint>> boneGraph = new LinkedHashMap<>();

    // flat registries a physics.World needs to walk each step, built alongside
    // boneGraph so it doesn't have to re-scan every tip's attachables every frame
    public List<Joint> joints = new ArrayList<>();
    public List<Motor> motors = new ArrayList<>();
    public Map<Tip, FrictionPad> frictionPads = new LinkedHashMap<>();

    // external behaviors (e.g. a HipBalancer) that physics.World runs once per
    // substep, alongside but separate from the skeleton's own joints/motors
    public List<PhysicalUpgrades> physicalUpgrades = new ArrayList<>();

    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public void buildFromConfig(String filename) throws IOException {
        String text = Files.readString(Paths.get(filename));
        Map<String, Object> root = (Map<String, Object>) Json.parse(text);
        Map<String, Object> bodyJson = (Map<String, Object>) root.get("Body");
        name = (String) bodyJson.get("Name");

        List<Object> bonesJson = (List<Object>) bodyJson.get("Bones");
        Map<String, Bone> boneByName = new HashMap<>();
        bone = new Bone[bonesJson.size()];
        for (int i = 0; i < bonesJson.size(); i++) {
            Map<String, Object> b = (Map<String, Object>) bonesJson.get(i);
            String boneName = (String) b.get("Name");
            double length = ((Number) b.get("Length")).doubleValue();
            double angleDeg = ((Number) b.get("Angle")).doubleValue();
            double mass = b.containsKey("Mass") ? ((Number) b.get("Mass")).doubleValue() : 1.0;

            Bone newBone = "head".equals(b.get("Type"))
                    ? new HeadBone(boneName, new TupleD(0, 0), length, angleDeg)
                    : new Bone(boneName, new TupleD(0, 0), length, angleDeg);
            newBone.setMass(mass);
            bone[i] = newBone;
            boneByName.put(boneName, newBone);
        }

        joints.clear();
        motors.clear();
        frictionPads.clear();
        physicalUpgrades.clear();
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

                Joint joint = new Joint(jointName, b0, tipIdx0, b1, tipIdx1);
                b0.tips[tipIdx0].addAttachable(joint);
                b1.tips[tipIdx1].addAttachable(joint);
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

                joint.bones[0].tips[joint.tipIdx[0]].addAttachable(motor);
                joint.bones[1].tips[joint.tipIdx[1]].addAttachable(motor);
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
        if (upgradesJson != null) {
            for (Object o : upgradesJson) {
                addPhysicalUpgrade((String) o);
            }
        }

        buildBoneGraph(joints);
        recomputeGeometry();
    }

    // -------------------------------------------------------------------------
    // recognizes upgrade type names from the "PhysicalUpgrades" JSON list and
    // constructs them, deriving whatever they need (torso, specific motors)
    // from what buildFromConfig has already built -- the JSON only ever names
    // the type, since e.g. a HipBalancer's wiring (which bones/motors) is
    // implied by this skeleton's own structure, not separate data to author.
    private void addPhysicalUpgrade(String upgradeName) {
        if ("HipBalancer".equals(upgradeName)) {
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
        } else if ("HandJitter".equals(upgradeName)) {
            Motor elbowL = null;
            Motor elbowR = null;
            for (Motor m : motors) {
                if ("elbowL".equals(m.name)) {
                    elbowL = m;
                } else if ("elbowR".equals(m.name)) {
                    elbowR = m;
                }
            }
            physicalUpgrades.add(new HandJitter(new Motor[]{elbowL, elbowR}));
        } else if ("ShoulderJitter".equals(upgradeName)) {
            Motor shoulderL = null;
            Motor shoulderR = null;
            for (Motor m : motors) {
                if ("shoulderL".equals(m.name)) {
                    shoulderL = m;
                } else if ("shoulderR".equals(m.name)) {
                    shoulderR = m;
                }
            }
            physicalUpgrades.add(new ShoulderJitter(new Motor[]{shoulderL, shoulderR}));
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
    public void draw() {
        for (Bone b : bone) {
            b.draw();
        }
    }

    // -------------------------------------------------------------------------
    private void buildBoneGraph(List<Joint> joints) {
        boneGraph.clear();
        for (Bone b : bone) {
            boneGraph.put(b, new ArrayList<>());
        }
        for (Joint j : joints) {
            boneGraph.get(j.bones[0]).add(j);
            boneGraph.get(j.bones[1]).add(j);
        }
    }

    // -------------------------------------------------------------------------
    // root = bone[0], tips[0]: fixed in place, everything else positioned outward
    // from it by walking boneGraph and, at each joint, carrying the shared point
    // over to the next bone before deriving that bone's other tip from its own
    // length/angle.
    public void recomputeGeometry() {
        bone[0].recomputeTip(0);
        Set<Bone> visited = new HashSet<>();
        visited.add(bone[0]);
        recomputeGeometry(bone[0], visited);
    }

    private void recomputeGeometry(Bone b, Set<Bone> visited) {
        for (Joint j : boneGraph.get(b)) {
            int selfIdx = (j.bones[0] == b) ? 0 : 1;
            int otherIdx = 1 - selfIdx;
            Bone other = j.bones[otherIdx];
            if (visited.contains(other)) {
                continue;
            }

            int selfTip = j.tipIdx[selfIdx];
            int otherTip = j.tipIdx[otherIdx];

            // if this joint has a motor, `other`'s angle is DERIVED from the
            // motor's own target (using the same joint-outward convention
            // Motor.apply() uses) rather than kept as its own independently
            // authored "Angle" -- so the built pose always starts at exactly
            // zero motor error, instead of the two numbers silently drifting
            // out of sync as target angles get tuned separately from bone angles.
            Motor motor = findMotorForJoint(j);
            if (motor != null) {
                double selfOutwardDeg = (selfTip == 0) ? b.angleDeg : b.angleDeg + 180.0;
                double sign = (selfIdx == 0) ? 1.0 : -1.0;
                double otherOutwardDeg = selfOutwardDeg + sign * motor.targetAngleDeg;
                other.angleDeg = (otherTip == 0) ? otherOutwardDeg : otherOutwardDeg - 180.0;
            }

            other.tips[otherTip].position = b.tips[selfTip].position;
            other.recomputeTip(otherTip);

            visited.add(other);
            recomputeGeometry(other, visited);
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
