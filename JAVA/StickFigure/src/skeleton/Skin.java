package skeleton;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import utils.Json;
import utils.TupleD;

// -----------------------------------------------------------------------------
// A cut-out skin, as authored in editor.SkinEditor: per bone name, a closed
// "cut" polygon in that bone's local frame (see Bone.localToWorld), and
// optionally an image filling it (laid out in that same frame). Keyed by
// name rather than bound to Bone objects, so one Skin survives every pose
// reload of the same skeleton -- Body.draw just looks each bone up by name.
public class Skin {

    private final Map<String, List<TupleD>> cuts = new LinkedHashMap<>();
    private final Map<String, BufferedImage> textures = new HashMap<>(); // bone name -> its cut's infill

    // -------------------------------------------------------------------------
    // the bone's cut, or null if this skin doesn't define one for it (the
    // bone then keeps its default shape).
    public List<TupleD> cutFor(String boneName) {
        return cuts.get(boneName);
    }

    // -------------------------------------------------------------------------
    // the image filling the bone's cut, or null for a flat fill (no "Image",
    // or it couldn't be read).
    public BufferedImage textureFor(String boneName) {
        return textures.get(boneName);
    }

    // -------------------------------------------------------------------------
    // reads {"Type":"Skin", "Bones":{<bone>:{"Cut":[[x,y],...], "Image":<path>}}}.
    // Image paths resolve relative to the working directory (the game's own
    // "src/assets/..." convention), each file read once even if several bones
    // share it. An unreadable image only costs that bone its texture (flat
    // fill, with a warning), not the whole skin. The file's "Skeleton"
    // reference is only an editor convenience and is ignored here.
    @SuppressWarnings("unchecked")
    public static Skin load(File file) throws IOException {
        Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(file.toPath()));
        if (!"Skin".equals(root.get("Type"))) {
            throw new IOException(file + " is not a skin file (missing \"Type\": \"Skin\")");
        }
        Skin skin = new Skin();
        Map<String, Object> bones = (Map<String, Object>) root.get("Bones");
        if (bones == null) {
            return skin;
        }
        Map<String, BufferedImage> imageByPath = new HashMap<>();
        for (Map.Entry<String, Object> e : bones.entrySet()) {
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            List<Object> cutJson = (List<Object>) entry.get("Cut");
            if (cutJson == null || cutJson.size() < 3) {
                continue;
            }
            List<TupleD> cut = new ArrayList<>();
            for (Object o : cutJson) {
                List<Object> pair = (List<Object>) o;
                cut.add(new TupleD(((Number) pair.get(0)).doubleValue(), ((Number) pair.get(1)).doubleValue()));
            }
            skin.cuts.put(e.getKey(), cut);

            if (entry.get("Image") instanceof String) {
                String path = (String) entry.get("Image");
                if (!imageByPath.containsKey(path)) {
                    BufferedImage img = null;
                    try {
                        img = ImageIO.read(new File(path));
                    } catch (IOException ex) {
                        // handled below, same as an unrecognized format
                    }
                    if (img == null) {
                        System.err.println("Skin: couldn't read image " + path + " -- " + e.getKey() + " drawn with a flat fill");
                    }
                    imageByPath.put(path, img);
                }
                if (imageByPath.get(path) != null) {
                    skin.textures.put(e.getKey(), imageByPath.get(path));
                }
            }
        }
        return skin;
    }
}
