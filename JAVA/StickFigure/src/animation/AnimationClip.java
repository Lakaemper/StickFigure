package animation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import utils.Json;

// -----------------------------------------------------------------------------
// A looping keyframed animation, e.g. a walk cycle: per key, every bone's own
// ABSOLUTE angle (degrees, same convention as Bone.angle()) plus which foot
// tip carries the weight ("Stance", a Body.tipByName name). Only angles are
// stored -- bone lengths come from whatever skeleton plays it, and forward
// motion isn't stored at all: ClipPlayer derives it by keeping the stance
// foot planted. "Legs" names the bones ClipPlayer solves by IK instead of
// playing their angles: one {upper, lower} pair per leg, upper tip0 at its
// root (hip/shoulder), lower tip1 the foot. File format:
//   {"Type":"Animation", "Duration":1.0, "Loop":true, "Direction":-1,
//    "Legs":[["thighL","shinL"], ["thighR","shinR"]],
//    "Keys":[{"T":0.0, "Stance":"shinL_foot", "Contacts":["shinL_foot","shinR_foot"],
//             "Angles":{"torso":94.0, ...}}, ...]}
// "Contacts" (optional) lists the feet on the ground from that key on -- what
// tells ClipPlayer exactly when a planted foot lifts off.
public class AnimationClip {

    public final double duration;
    public final boolean loop;
    public final double direction; // -1 walks toward -x, +1 toward +x, 0 in place/unknown
    public final List<String[]> legs; // {upper, lower} bone names per leg
    private final List<Key> keys;

    public static class Key {
        public final double t;
        public final String stance;
        public final Set<String> contacts; // feet on the ground from this key on, or null if not given
        public final Map<String, Double> anglesDeg;

        Key(double t, String stance, Set<String> contacts, Map<String, Double> anglesDeg) {
            this.t = t;
            this.stance = stance;
            this.contacts = contacts;
            this.anglesDeg = anglesDeg;
        }
    }

    // -------------------------------------------------------------------------
    private AnimationClip(double duration, boolean loop, double direction, List<String[]> legs, List<Key> keys) {
        this.duration = duration;
        this.loop = loop;
        this.direction = direction;
        this.legs = legs;
        this.keys = keys;
    }

    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public static AnimationClip load(String path) throws IOException {
        Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(new File(path).toPath()));
        if (!"Animation".equals(root.get("Type"))) {
            throw new IOException(path + " is not an animation file (missing \"Type\": \"Animation\")");
        }
        double duration = ((Number) root.get("Duration")).doubleValue();
        boolean loop = !Boolean.FALSE.equals(root.get("Loop"));
        double direction = root.get("Direction") instanceof Number ? ((Number) root.get("Direction")).doubleValue() : 0.0;
        List<String[]> legs = new ArrayList<>();
        if (root.get("Legs") instanceof List) {
            for (Object o : (List<Object>) root.get("Legs")) {
                List<Object> pair = (List<Object>) o;
                legs.add(new String[]{(String) pair.get(0), (String) pair.get(1)});
            }
        }
        List<Key> keys = new ArrayList<>();
        for (Object o : (List<Object>) root.get("Keys")) {
            Map<String, Object> k = (Map<String, Object>) o;
            Map<String, Double> angles = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) k.get("Angles")).entrySet()) {
                angles.put(e.getKey(), ((Number) e.getValue()).doubleValue());
            }
            Set<String> contacts = null;
            if (k.get("Contacts") instanceof List) {
                contacts = new HashSet<>();
                for (Object c : (List<Object>) k.get("Contacts")) {
                    contacts.add((String) c);
                }
            }
            keys.add(new Key(((Number) k.get("T")).doubleValue(), (String) k.get("Stance"), contacts, angles));
        }
        if (keys.size() < 2) {
            throw new IOException(path + " needs at least 2 keys");
        }
        keys.sort((a, b) -> Double.compare(a.t, b.t));
        return new AnimationClip(duration, loop, direction, legs, keys);
    }

    // -------------------------------------------------------------------------
    // the same clip facing the other way: mirrored horizontally, exactly like
    // the _L -> _R pose files (absolute bone angle theta -> 180 - theta). Bone
    // and tip names stay as they are -- the LEFT leg is still the left leg,
    // the figure just walks toward +x instead of -x.
    public AnimationClip mirrored() {
        List<Key> m = new ArrayList<>();
        for (Key k : keys) {
            Map<String, Double> angles = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : k.anglesDeg.entrySet()) {
                angles.put(e.getKey(), 180.0 - e.getValue());
            }
            m.add(new Key(k.t, k.stance, k.contacts, angles));
        }
        return new AnimationClip(duration, loop, -direction, legs, m);
    }

    // -------------------------------------------------------------------------
    // every bone's angle at time t (seconds, wrapped into the clip for a
    // looping clip, clamped otherwise), Catmull-Rom interpolated between the
    // surrounding keys -- smooth velocities through each key, rather than the
    // robotic constant-speed-then-kink of a plain linear blend. Angles are
    // unwrapped relative to the key at/before t first, so an interpolation
    // never sweeps the long way around.
    public Map<String, Double> anglesAt(double t) {
        int i = keyIndexAt(t);
        double t1 = keys.get(i).t;
        double t2 = (i + 1 < keys.size()) ? keys.get(i + 1).t : duration + keys.get(0).t;
        double u = t2 > t1 ? (wrap(t) - t1) / (t2 - t1) : 0.0;
        Key k0 = keys.get(neighbor(i, -1));
        Key k1 = keys.get(i);
        Key k2 = keys.get(neighbor(i, 1));
        Key k3 = keys.get(neighbor(i, 2));

        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : k1.anglesDeg.entrySet()) {
            String bone = e.getKey();
            double p1 = e.getValue();
            double p0 = p1 + shortDelta(p1, k0.anglesDeg.getOrDefault(bone, p1));
            double p2 = p1 + shortDelta(p1, k2.anglesDeg.getOrDefault(bone, p1));
            double p3 = p2 + shortDelta(p2, k3.anglesDeg.getOrDefault(bone, p2));
            out.put(bone, catmullRom(p0, p1, p2, p3, u));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // the stance foot at time t: that of the key at or before t -- so the
    // weight switches exactly at the key where the other foot touches down.
    public String stanceAt(double t) {
        return keys.get(keyIndexAt(t)).stance;
    }

    // -------------------------------------------------------------------------
    // the feet on the ground at time t (those of the key at or before t), or
    // null if this clip doesn't say -- a foot leaves this set at its lift-off.
    public Set<String> contactsAt(double t) {
        return keys.get(keyIndexAt(t)).contacts;
    }

    // -------------------------------------------------------------------------
    // the keys where the weight moves to another foot (its touchdown), in
    // time order -- e.g. candidate points to start the clip from.
    public List<Key> footfalls() {
        List<Key> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            Key previous = keys.get(neighbor(i, -1));
            if (i == 0 && !loop || !keys.get(i).stance.equals(previous.stance)) {
                out.add(keys.get(i));
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    private int keyIndexAt(double t) {
        double tw = wrap(t);
        int idx = keys.size() - 1;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).t <= tw) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    // -------------------------------------------------------------------------
    private int neighbor(int i, int offset) {
        int n = keys.size();
        int j = i + offset;
        if (loop) {
            return ((j % n) + n) % n;
        }
        return Math.max(0, Math.min(n - 1, j));
    }

    // -------------------------------------------------------------------------
    private double wrap(double t) {
        if (loop) {
            double w = t % duration;
            return w < 0.0 ? w + duration : w;
        }
        return Math.max(0.0, Math.min(duration, t));
    }

    // -------------------------------------------------------------------------
    private static double catmullRom(double p0, double p1, double p2, double p3, double u) {
        double u2 = u * u;
        double u3 = u2 * u;
        return 0.5 * (2.0 * p1 + (-p0 + p2) * u + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3);
    }

    // -------------------------------------------------------------------------
    // to - from, wrapped into (-180, 180].
    static double shortDelta(double fromDeg, double toDeg) {
        double d = (toDeg - fromDeg) % 360.0;
        if (d <= -180.0) {
            d += 360.0;
        } else if (d > 180.0) {
            d -= 360.0;
        }
        return d;
    }
}
