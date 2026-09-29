package editor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import skeleton.Body;
import skeleton.Bone;
import skeleton.HeadBone;
import skeleton.Tip;
import utils.Json;
import utils.TupleD;
import viewer.Viewer;

// -----------------------------------------------------------------------------
// Cut-out skinning editor: load a pose, select a bone, draw its "cut" -- the
// closed polygon its skin image will later be clipped to. Bones without a cut
// yet show a faint outline of their default ellipse (or head circle).
//
// Each cut is stored in its bone's LOCAL frame (origin at tip0, x-axis along
// tip0->tip1, world units), not in screen or world coordinates -- that is
// what makes it follow the bone rigidly in any pose. It also means cuts
// survive loading a different pose of the same skeleton: they're keyed by
// bone name, and simply re-render wherever that bone now is.
public class SkinEditor extends JFrame {

    private static final int WINDOW_SIZE = 800;
    private static final double FIT_MARGIN_PIXELS = 80;
    private static final int SELECT_RADIUS_PIXELS = 15; // click-to-bone-axis distance
    private static final int CLOSE_RADIUS_PIXELS = 5;   // click-to-first-vertex, closes a cut
    private static final int ERASE_RADIUS_PIXELS = 8;   // right-click-to-vertex, erases it
    private static final int DRAG_SPACING_PIXELS = 4;   // min distance between freehand points
    private static final int DRAG_THRESHOLD_PIXELS = 5; // press-release moved more = a drag, not a click

    // texture palette: slots Texture_00..Texture_09 in TEXTURE_DIR, any of
    // these extensions (first found wins). Missing slots show as disabled.
    private static final int TEXTURE_COUNT = 10;
    private static final String TEXTURE_DIR = "src/assets";
    private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".bmp"};
    private static final int THUMB_SIZE = 56;
    private static final Dimension SLOT_SIZE = new Dimension(72, 84);

    private static final Color FAINT = new Color(0, 0, 0, 55);
    private static final Color SELECTED = new Color(30, 110, 255);
    private static final Color CUT_FILL = new Color(160, 32, 240, 60);
    private static final Color CUT_OUTLINE = new Color(110, 20, 170);
    private static final Color DRAWING = new Color(220, 60, 0);

    private enum Mode {
        SELECT, DRAW
    }

    private Mode mode = Mode.SELECT;
    private Body body;
    private File skeletonFile;    // the file `body` was loaded from -- a skin references it
    private Bone selected;
    private final Map<String, List<TupleD>> cuts = new LinkedHashMap<>(); // bone name -> bone-local polygon
    private final Map<String, String> textures = new LinkedHashMap<>();   // bone name -> image path (its cut's infill)
    private final Map<String, BufferedImage> imageCache = new HashMap<>(); // image path -> image, null if unreadable
    private final String[] slotPaths = new String[TEXTURE_COUNT];         // palette slot -> image path, null if missing
    private final JToggleButton[] slotButtons = new JToggleButton[TEXTURE_COUNT];
    private final JToggleButton noTextureButton = new JToggleButton("None");
    private final ButtonGroup textureGroup = new ButtonGroup();
    private final List<TupleD> drawing = new ArrayList<>();              // in-progress cut, world coords
    private Point pressPoint;     // where the current left-button press started (DRAW mode)
    private boolean dragged;      // whether that press has moved far enough to count as a drag

    // view transform: fits the loaded pose's bounding box into the canvas
    private double scale = 1.0;
    private double viewCenterX = 0.0;
    private double viewCenterY = 0.0;

    private final CanvasPanel canvas = new CanvasPanel();
    private final JToggleButton selectButton = new JToggleButton("Select", true);
    private final JToggleButton drawButton = new JToggleButton("Draw Cut");
    private final JButton clearButton = new JButton("Clear Cut");
    private final JLabel status = new JLabel();

    // -------------------------------------------------------------------------
    public SkinEditor() {
        super("Skin Editor");
        JPanel palette = buildPalette();
        // widened by the palette, so the drawing area itself stays WINDOW_SIZE wide
        setSize(WINDOW_SIZE + palette.getPreferredSize().width, WINDOW_SIZE);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        setJMenuBar(buildMenuBar());
        add(buildToolBar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(palette, BorderLayout.EAST);
        reloadTextures();
        updateControls();

        setVisible(true);
    }

    // -------------------------------------------------------------------------
    // "None" plus one toggle per texture slot, two columns; clicking one sets
    // (or clears) the selected bone's infill. Reload re-scans the folder, so
    // textures added while the editor is open show up without a restart.
    private JPanel buildPalette() {
        JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
        noTextureButton.setPreferredSize(SLOT_SIZE);
        noTextureButton.addActionListener(e -> assignTexture(null));
        textureGroup.add(noTextureButton);
        grid.add(noTextureButton);
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            final int slot = i;
            JToggleButton b = new JToggleButton();
            b.setPreferredSize(SLOT_SIZE);
            b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setHorizontalTextPosition(SwingConstants.CENTER);
            b.addActionListener(e -> assignTexture(slotPaths[slot]));
            textureGroup.add(b);
            slotButtons[i] = b;
            grid.add(b);
        }

        JButton reload = new JButton("Reload");
        reload.addActionListener(e -> reloadTextures());

        JLabel title = new JLabel("Textures");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        reload.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setMaximumSize(grid.getPreferredSize());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        column.add(title);
        column.add(Box.createVerticalStrut(4));
        column.add(grid);
        column.add(Box.createVerticalStrut(6));
        column.add(reload);
        return column;
    }

    // -------------------------------------------------------------------------
    // re-scans Texture_00..Texture_09 and re-reads every image from disk
    // (including ones a loaded skin references outside the palette).
    private void reloadTextures() {
        imageCache.clear();
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            String name = String.format("Texture_%02d", i);
            slotPaths[i] = null;
            for (String ext : IMAGE_EXTENSIONS) {
                File f = new File(TEXTURE_DIR, name + ext);
                if (f.isFile()) {
                    slotPaths[i] = portablePath(f);
                    break;
                }
            }
            BufferedImage img = slotPaths[i] != null ? imageFor(slotPaths[i]) : null;
            JToggleButton b = slotButtons[i];
            b.setIcon(img != null ? new ImageIcon(img.getScaledInstance(THUMB_SIZE, THUMB_SIZE, Image.SCALE_SMOOTH)) : null);
            b.setText(String.format("%02d", i) + (img == null ? " missing" : ""));
            b.setToolTipText(slotPaths[i] != null ? slotPaths[i] : name + ".* not found in " + TEXTURE_DIR);
        }
        canvas.repaint();
        updateControls();
    }

    // -------------------------------------------------------------------------
    // cached read of an image path (relative to the working directory, or
    // absolute); null, also cached, if it can't be read.
    private BufferedImage imageFor(String path) {
        if (!imageCache.containsKey(path)) {
            BufferedImage img = null;
            try {
                img = ImageIO.read(new File(path));
            } catch (IOException ex) {
                // stays null: the cut falls back to a flat fill
            }
            imageCache.put(path, img);
        }
        return imageCache.get(path);
    }

    // -------------------------------------------------------------------------
    // sets (path != null) or clears the selected bone's infill -- only
    // meaningful once it has a cut to fill.
    private void assignTexture(String path) {
        if (selected == null || !cuts.containsKey(selected.name)) {
            return;
        }
        if (path == null) {
            textures.remove(selected.name);
        } else {
            textures.put(selected.name, path);
        }
        canvas.repaint();
        updateControls();
    }

    // -------------------------------------------------------------------------
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem loadSkeleton = new JMenuItem("Load Skeleton");
        loadSkeleton.addActionListener(e -> onLoadSkeleton());
        JMenuItem loadSkin = new JMenuItem("Load Skin");
        loadSkin.addActionListener(e -> onLoadSkin());
        JMenuItem saveSkin = new JMenuItem("Save Skin");
        saveSkin.addActionListener(e -> onSaveSkin());
        fileMenu.add(loadSkeleton);
        fileMenu.addSeparator();
        fileMenu.add(loadSkin);
        fileMenu.add(saveSkin);
        menuBar.add(fileMenu);
        return menuBar;
    }

    // -------------------------------------------------------------------------
    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(selectButton);
        modeGroup.add(drawButton);
        selectButton.addActionListener(e -> setMode(Mode.SELECT));
        drawButton.addActionListener(e -> setMode(Mode.DRAW));

        clearButton.addActionListener(e -> {
            if (selected != null) {
                cuts.remove(selected.name);
                textures.remove(selected.name);
                canvas.repaint();
                updateControls();
            }
        });

        bar.add(selectButton);
        bar.add(drawButton);
        bar.addSeparator();
        bar.add(clearButton);
        bar.addSeparator();
        bar.add(status);
        return bar;
    }

    // -------------------------------------------------------------------------
    // entering DRAW starts a fresh cut for the selected bone (replacing any
    // existing one only once the new polygon actually closes); leaving it
    // discards an unfinished one.
    private void setMode(Mode newMode) {
        mode = newMode;
        drawing.clear();
        selectButton.setSelected(mode == Mode.SELECT);
        drawButton.setSelected(mode == Mode.DRAW);
        canvas.repaint();
        updateControls();
    }

    // -------------------------------------------------------------------------
    private void updateControls() {
        boolean hasCut = selected != null && cuts.containsKey(selected.name);
        drawButton.setEnabled(selected != null);
        clearButton.setEnabled(hasCut);

        // palette: usable only for a bone that has a cut to fill; highlights
        // that bone's current infill (or None)
        String current = hasCut ? textures.get(selected.name) : null;
        noTextureButton.setEnabled(hasCut);
        boolean matched = false;
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            slotButtons[i].setEnabled(hasCut && slotPaths[i] != null && imageFor(slotPaths[i]) != null);
            if (hasCut && current != null && current.equals(slotPaths[i])) {
                slotButtons[i].setSelected(true);
                matched = true;
            }
        }
        if (!hasCut) {
            textureGroup.clearSelection();
        } else if (current == null) {
            noTextureButton.setSelected(true);
        } else if (!matched) {
            textureGroup.clearSelection(); // an image from outside the palette (e.g. a loaded skin)
        }

        if (body == null) {
            status.setText("  File > Load Skeleton to begin");
        } else if (selected == null) {
            status.setText("  Click near a bone to select it");
        } else if (mode == Mode.DRAW) {
            status.setText("  Drawing cut for " + selected.name
                    + " -- drag to draw (release closes), or click corners (click first to close); right-click erases");
        } else if (!hasCut) {
            status.setText("  Selected: " + selected.name + " (no cut yet)");
        } else {
            status.setText("  Selected: " + selected.name + " (has cut"
                    + (current != null ? ", texture " + new File(current).getName() : ", pick a texture on the right") + ")");
        }
    }

    // -------------------------------------------------------------------------
    private void onLoadSkeleton() {
        JFileChooser chooser = new JFileChooser(new File("src/assets"));
        chooser.setDialogTitle("Load Skeleton");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadSkeletonFrom(chooser.getSelectedFile());
        }
    }

    // -------------------------------------------------------------------------
    // keeps any existing cuts -- they're bone-local and keyed by bone name, so
    // they simply re-render on the newly loaded pose.
    private boolean loadSkeletonFrom(File file) {
        Body loaded = new Body();
        try {
            loaded.loadPose(file);
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Load skeleton failed: " + ex.getMessage());
            return false;
        }
        body = loaded;
        skeletonFile = file;
        selected = null;
        setMode(Mode.SELECT);
        return true;
    }

    // -------------------------------------------------------------------------
    // writes {"Type":"Skin","Skeleton":<path>,"Bones":{<bone>:{"Cut":[[x,y],...],
    // "Image":<path, only if textured>}}}, cuts in bone-local world units
    // exactly as held in memory. The skeleton
    // path is stored relative to the working directory when possible, the same
    // convention the game uses for its own asset paths ("src/assets/...").
    private void onSaveSkin() {
        if (body == null) {
            JOptionPane.showMessageDialog(this, "Load a skeleton first.");
            return;
        }
        JFileChooser chooser = new JFileChooser(new File("src/assets"));
        chooser.setDialogTitle("Save Skin");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getPath() + ".json");
        }
        saveSkinTo(file);
    }

    // -------------------------------------------------------------------------
    private void saveSkinTo(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"Type\": \"Skin\",\n");
        sb.append("  \"Skeleton\": \"").append(jsonEscape(portablePath(skeletonFile))).append("\",\n");
        sb.append("  \"Bones\": {");
        int i = 0;
        for (Map.Entry<String, List<TupleD>> e : cuts.entrySet()) {
            sb.append(i++ == 0 ? "\n" : ",\n");
            sb.append("    \"").append(jsonEscape(e.getKey())).append("\": {\"Cut\": [");
            List<TupleD> cut = e.getValue();
            for (int j = 0; j < cut.size(); j++) {
                sb.append(j == 0 ? "" : ", ");
                sb.append("[").append(cut.get(j).first).append(", ").append(cut.get(j).second).append("]");
            }
            sb.append("]");
            String image = textures.get(e.getKey());
            if (image != null) {
                sb.append(", \"Image\": \"").append(jsonEscape(image)).append("\"");
            }
            sb.append("}");
        }
        sb.append(cuts.isEmpty() ? "}\n" : "\n  }\n");
        sb.append("}\n");

        try {
            Files.writeString(file.toPath(), sb.toString());
            status.setText("  Saved " + cuts.size() + " cut(s) to " + file.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // replaces the current cuts with the file's. If no skeleton is loaded yet,
    // also loads the one the skin references; if one IS loaded, keeps it --
    // cuts are pose-independent, so a skin can be previewed on any pose of
    // the same skeleton.
    private void onLoadSkin() {
        JFileChooser chooser = new JFileChooser(new File("src/assets"));
        chooser.setDialogTitle("Load Skin");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadSkinFrom(chooser.getSelectedFile());
        }
    }

    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void loadSkinFrom(File file) {
        Map<String, Object> root;
        try {
            root = (Map<String, Object>) Json.parse(Files.readString(file.toPath()));
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Load skin failed: " + ex.getMessage());
            return;
        }
        if (!"Skin".equals(root.get("Type"))) {
            JOptionPane.showMessageDialog(this, "Not a skin file (missing \"Type\": \"Skin\").");
            return;
        }

        if (body == null && root.get("Skeleton") instanceof String) {
            File skel = resolveSkeleton((String) root.get("Skeleton"), file);
            if (skel == null || !loadSkeletonFrom(skel)) {
                JOptionPane.showMessageDialog(this, "Skin loaded, but its skeleton wasn't found: "
                        + root.get("Skeleton") + "\nUse File > Load Skeleton to show it.");
            }
        }

        cuts.clear();
        textures.clear();
        Map<String, Object> bones = (Map<String, Object>) root.get("Bones");
        if (bones != null) {
            for (Map.Entry<String, Object> e : bones.entrySet()) {
                Map<String, Object> entry = (Map<String, Object>) e.getValue();
                List<Object> cutJson = (List<Object>) entry.get("Cut");
                if (cutJson == null) {
                    continue;
                }
                List<TupleD> cut = new ArrayList<>();
                for (Object o : cutJson) {
                    List<Object> pair = (List<Object>) o;
                    cut.add(new TupleD(((Number) pair.get(0)).doubleValue(), ((Number) pair.get(1)).doubleValue()));
                }
                cuts.put(e.getKey(), cut);
                // kept even if the file is missing right now, so re-saving
                // doesn't silently drop it -- the cut just draws flat meanwhile
                if (entry.get("Image") instanceof String) {
                    textures.put(e.getKey(), (String) entry.get("Image"));
                }
            }
        }
        setMode(Mode.SELECT);
        status.setText("  Loaded " + cuts.size() + " cut(s) from " + file.getName());
    }

    // -------------------------------------------------------------------------
    // the skeleton path as written by onSaveSkin: tried as-is (relative to the
    // working directory, or absolute), then relative to the skin file's folder.
    private static File resolveSkeleton(String path, File skinFile) {
        File direct = new File(path);
        if (direct.isFile()) {
            return direct;
        }
        File besideSkin = new File(skinFile.getParentFile(), path);
        return besideSkin.isFile() ? besideSkin : null;
    }

    // -------------------------------------------------------------------------
    private static String portablePath(File file) {
        Path abs = file.toPath().toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path p = abs.startsWith(cwd) ? cwd.relativize(abs) : abs;
        return p.toString().replace('\\', '/');
    }

    // -------------------------------------------------------------------------
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------------------------------
    // fits the pose's tip bounding box into the canvas (minus a margin for
    // ellipse widths / the head circle), preserving aspect ratio.
    private void updateView() {
        if (body == null) {
            return;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Bone b : body.bone) {
            for (Tip t : b.tips) {
                minX = Math.min(minX, t.position.first);
                maxX = Math.max(maxX, t.position.first);
                minY = Math.min(minY, t.position.second);
                maxY = Math.max(maxY, t.position.second);
            }
        }
        double w = Math.max(maxX - minX, 1e-6);
        double h = Math.max(maxY - minY, 1e-6);
        double availW = Math.max(canvas.getWidth() - 2 * FIT_MARGIN_PIXELS, 1);
        double availH = Math.max(canvas.getHeight() - 2 * FIT_MARGIN_PIXELS, 1);
        scale = Math.min(availW / w, availH / h);
        viewCenterX = (minX + maxX) / 2.0;
        viewCenterY = (minY + maxY) / 2.0;
    }

    // -------------------------------------------------------------------------
    private Point2D.Double toScreen(TupleD world) {
        return new Point2D.Double(
                canvas.getWidth() / 2.0 + (world.first - viewCenterX) * scale,
                canvas.getHeight() / 2.0 - (world.second - viewCenterY) * scale);
    }

    // -------------------------------------------------------------------------
    private TupleD toWorld(Point screen) {
        return new TupleD(
                viewCenterX + (screen.x - canvas.getWidth() / 2.0) / scale,
                viewCenterY - (screen.y - canvas.getHeight() / 2.0) / scale);
    }

    // -------------------------------------------------------------------------
    // the bone-local frame is defined once, on Bone -- the game renders cuts
    // through the same methods, so editor and game can't disagree about it.
    private static TupleD toBoneLocal(Bone b, TupleD world) {
        return b.worldToLocal(world);
    }

    // -------------------------------------------------------------------------
    private static TupleD fromBoneLocal(Bone b, TupleD local) {
        return b.localToWorld(local);
    }

    // -------------------------------------------------------------------------
    // nearest bone whose tip0-tip1 axis is within SELECT_RADIUS_PIXELS of p.
    private Bone boneNear(Point p) {
        Bone best = null;
        double bestDist = SELECT_RADIUS_PIXELS;
        for (Bone b : body.bone) {
            Point2D.Double a = toScreen(b.tips[0].position);
            Point2D.Double c = toScreen(b.tips[1].position);
            double d = Line2D.ptSegDist(a.x, a.y, c.x, c.y, p.x, p.y);
            if (d <= bestDist) {
                bestDist = d;
                best = b;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // SELECT: picks a bone. DRAW: pressing near the first corner (with 3+
    // corners) closes the cut; otherwise adds a corner -- and if the button
    // is then dragged, handleLeftDrag keeps adding freehand points and
    // handleLeftRelease closes the cut. So plain clicks and drags can be
    // mixed within one cut (straight corners + freehand curves).
    private void handleLeftPress(Point p) {
        if (body == null) {
            return;
        }
        if (mode == Mode.SELECT) {
            selected = boneNear(p);
        } else if (drawing.size() >= 3 && toScreen(drawing.get(0)).distance(p) <= CLOSE_RADIUS_PIXELS) {
            commitCut();
        } else {
            drawing.add(toWorld(p));
            pressPoint = p;
            dragged = false;
        }
        canvas.repaint();
        updateControls();
    }

    // -------------------------------------------------------------------------
    private void handleLeftDrag(Point p) {
        if (mode != Mode.DRAW || pressPoint == null) {
            return;
        }
        if (p.distance(pressPoint) > DRAG_THRESHOLD_PIXELS) {
            dragged = true;
        }
        if (toScreen(drawing.get(drawing.size() - 1)).distance(p) >= DRAG_SPACING_PIXELS) {
            drawing.add(toWorld(p));
            canvas.repaint();
        }
    }

    // -------------------------------------------------------------------------
    private void handleLeftRelease() {
        if (mode == Mode.DRAW && pressPoint != null && dragged && drawing.size() >= 3) {
            commitCut();
        }
        pressPoint = null;
        dragged = false;
        canvas.repaint();
        updateControls();
    }

    // -------------------------------------------------------------------------
    // stores the in-progress polygon as the selected bone's cut, converted
    // into that bone's local frame, and returns to SELECT.
    private void commitCut() {
        List<TupleD> local = new ArrayList<>();
        for (TupleD w : drawing) {
            local.add(toBoneLocal(selected, w));
        }
        cuts.put(selected.name, local);
        pressPoint = null;
        dragged = false;
        setMode(Mode.SELECT);
    }

    // -------------------------------------------------------------------------
    private void handleRightClick(Point p) {
        if (mode != Mode.DRAW) {
            return;
        }
        for (int i = 0; i < drawing.size(); i++) {
            if (toScreen(drawing.get(i)).distance(p) <= ERASE_RADIUS_PIXELS) {
                drawing.remove(i);
                canvas.repaint();
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    private class CanvasPanel extends JPanel {

        CanvasPanel() {
            setBackground(Color.WHITE);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    updateView();
                    if (e.getButton() == MouseEvent.BUTTON3) {
                        handleRightClick(e.getPoint());
                    } else {
                        handleLeftPress(e.getPoint());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        handleLeftDrag(e.getPoint());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        handleLeftRelease();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (body == null) {
                return;
            }
            updateView();
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            for (Bone b : body.bone) {
                List<TupleD> cut = cuts.get(b.name);
                if (cut != null) {
                    Path2D path = cutPath(b, cut);
                    String image = textures.get(b.name);
                    BufferedImage tex = image != null ? imageFor(image) : null;
                    if (tex != null) {
                        fillTextured(g2, b, cut, tex);
                    } else {
                        g2.setColor(CUT_FILL);
                        g2.fill(path);
                    }
                    g2.setColor(b == selected ? SELECTED : CUT_OUTLINE);
                    g2.setStroke(new BasicStroke(b == selected ? 3 : 2));
                    g2.draw(path);
                } else {
                    g2.setColor(b == selected ? SELECTED : FAINT);
                    g2.setStroke(new BasicStroke(b == selected ? 2 : 1));
                    drawDefaultShape(g2, b);
                }
            }

            // every bone's axis, so bones stay visible/selectable even once cut
            for (Bone b : body.bone) {
                Point2D.Double a = toScreen(b.tips[0].position);
                Point2D.Double c = toScreen(b.tips[1].position);
                g2.setColor(b == selected ? SELECTED : FAINT);
                g2.setStroke(new BasicStroke(b == selected ? 3 : 1));
                g2.draw(new Line2D.Double(a, c));
            }

            if (mode == Mode.DRAW && !drawing.isEmpty()) {
                g2.setColor(DRAWING);
                g2.setStroke(new BasicStroke(2));
                for (int i = 0; i + 1 < drawing.size(); i++) {
                    g2.draw(new Line2D.Double(toScreen(drawing.get(i)), toScreen(drawing.get(i + 1))));
                }
                for (int i = 0; i < drawing.size(); i++) {
                    Point2D.Double p = toScreen(drawing.get(i));
                    int r = i == 0 ? 5 : 3; // first corner larger: click it to close
                    g2.fill(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
                }
            }
        }

        // the game's own default look for a bone (see Bone.draw/HeadBone.draw):
        // an ellipse along the axis, or the head circle.
        private void drawDefaultShape(Graphics2D g2, Bone b) {
            TupleD t0 = b.tips[0].position;
            TupleD t1 = b.tips[1].position;
            if (b instanceof HeadBone) {
                Point2D.Double c = toScreen(t0.add(t1.sub(t0).times(2.0 / 3.0)));
                double r = b.length / 2.0 * scale;
                g2.draw(new Ellipse2D.Double(c.x - r, c.y - r, 2 * r, 2 * r));
                return;
            }
            Point2D.Double c = toScreen(t0.add(t1).times(0.5));
            double major = t1.sub(t0).length() / 2.0 * scale;
            double minor = major / 3.0;
            AffineTransform saved = g2.getTransform();
            g2.translate(c.x, c.y);
            g2.rotate(-b.angle()); // screen y is flipped, so rotation sense flips too
            g2.draw(new Ellipse2D.Double(-major, -minor, 2 * major, 2 * minor));
            g2.setTransform(saved);
        }

        // fills the cut with `tex`, laid out in the bone's own local frame so
        // it moves and rotates with the bone: tile origin at tip0, image
        // upright when the bone points right, one image pixel per game-screen
        // pixel (Viewer's own pixels-per-unit), tiling if the cut is larger.
        // Drawn in a y-down copy of the local frame (local y negated) so the
        // image isn't mirrored: screen = tip0 + rotate(-angle) * scale * (x, -y).
        private void fillTextured(Graphics2D g2, Bone b, List<TupleD> cut, BufferedImage tex) {
            Path2D.Double local = new Path2D.Double();
            for (int i = 0; i < cut.size(); i++) {
                TupleD p = cut.get(i);
                if (i == 0) {
                    local.moveTo(p.first, -p.second);
                } else {
                    local.lineTo(p.first, -p.second);
                }
            }
            local.closePath();

            AffineTransform savedTransform = g2.getTransform();
            Paint savedPaint = g2.getPaint();
            Point2D.Double origin = toScreen(b.tips[0].position);
            g2.translate(origin.x, origin.y);
            g2.rotate(-b.angle());
            g2.scale(scale, scale);
            g2.setPaint(new TexturePaint(tex, new Rectangle2D.Double(0, 0,
                    Viewer.pixelsToWorldLength(tex.getWidth()), Viewer.pixelsToWorldLength(tex.getHeight()))));
            g2.fill(local);
            g2.setPaint(savedPaint);
            g2.setTransform(savedTransform);
        }

        private Path2D cutPath(Bone b, List<TupleD> cut) {
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < cut.size(); i++) {
                Point2D.Double p = toScreen(fromBoneLocal(b, cut.get(i)));
                if (i == 0) {
                    path.moveTo(p.x, p.y);
                } else {
                    path.lineTo(p.x, p.y);
                }
            }
            path.closePath();
            return path;
        }
    }

    // -------------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(SkinEditor::new);
    }
}
