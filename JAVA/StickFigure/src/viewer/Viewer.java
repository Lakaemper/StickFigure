package viewer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.TexturePaint;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.Timer;
import utils.TupleD;

public class Viewer extends JFrame {

    private static final int DISPLAY_WIDTH = 2000;
    private static final int DISPLAY_HEIGHT = 1200;
    private static final double SCALE = 100.0; // pixels per unit

    private static final Dimension BUTTON_SIZE = new Dimension(130, 28); // uniform, independent of label text

    private static final int ORIGIN_X = 1000;
    private static final int ORIGIN_Y = 600;

    private static final int STEP_DELAY_MS = 16; // ~60fps while playing
    private static final int CLICK_THRESHOLD_PIXELS = 5; // press-release moves less than this = a click, not a drag

    private static final Color ELLIPSE_FILL = new Color(255, 0, 0, 255);

    private final List<TupleD[]> lines = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();
    private final List<Ellipse> ellipses = new ArrayList<>();
    private final List<Rect> rects = new ArrayList<>();
    private final List<Poly> polys = new ArrayList<>();
    private boolean floorVisible = false;
    private final DrawPanel panel = new DrawPanel();

    // a tiled, scrolling backdrop (e.g. a jungle texture) drawn behind
    // everything else -- see loadBackgroundImage. Scrolls at
    // BACKGROUND_PARALLAX_FACTOR times the foreground's own camera motion
    // (less than 1), so it visually sits further away -- a classic parallax
    // depth cue -- while tiling seamlessly regardless of how far the camera
    // has scrolled, via a modulo offset (see DrawPanel.paintComponent).
    private BufferedImage backgroundImage;
    private static final double BACKGROUND_PARALLAX_FACTOR = 0.25;
    // opacity the backdrop is drawn with (1 = fully opaque) -- faded so it
    // reads as scenery and doesn't compete with the figure and rocks.
    private static final float BACKGROUND_ALPHA = 0.5f;

    // the world point that always renders at screen center -- e.g. Main sets
    // this to the figure's own neck tip every frame, turning the viewer into
    // a scrolling window that follows the figure instead of a fixed stage.
    // Defaults to the world origin, so anything that never calls
    // setCameraFocus behaves exactly as if there were no camera at all.
    private TupleD cameraFocus = new TupleD(0.0, 0.0);

    // set by whoever wants Play/Step/Reset to actually do something (e.g. Main
    // wiring up an animation.World) -- Viewer itself has no idea what a step or
    // a reset means.
    public Runnable onStep;
    public Runnable onReset;

    // StorePose/LoadPose: Viewer owns the file-chooser dialogs (a Swing
    // concern, same as everything else here), and just hands the chosen file
    // to whoever wires this up -- it has no idea what a "pose" is.
    public Consumer<File> onStorePose;
    public Consumer<File> onLoadPose;

    // Morphing section: LoadTarget picks the target pose file the same way
    // LoadPose does; Morph starts a morph toward it (using getMorphTimeSeconds()
    // for the duration) -- neither carries the target Body itself, since Viewer
    // has no idea what a Body is.
    public Consumer<File> onLoadTarget;
    public Runnable onMorph;

    // Jump: a single button that starts a whole automated sequence (Main
    // owns what that sequence actually does) -- Viewer just reports the click.
    public Runnable onJump;

    // keyboard-triggered jumps -- 'o'/'p' while the draw panel has focus (see
    // the KeyListener below). Separate from onJump: Main owns what direction
    // means and what sequence runs, Viewer just reports which key. Fired on
    // press (start charging); onJumpKeyReleased fires when EITHER key comes
    // back up (Main only ever has one jump in progress at a time, so it
    // doesn't need to know which key this was -- just that charging ended).
    public Runnable onJumpLeft;
    public Runnable onJumpRight;
    public Runnable onJumpKeyReleased;

    // keyboard walking -- 'u' walk left / 'i' walk right, held to keep
    // walking: fired on press, onWalkKeyReleased when either comes back up.
    public Runnable onWalkLeft;
    public Runnable onWalkRight;
    public Runnable onWalkKeyReleased;

    // 'h': switch the playable figure (see main.CharacterProfile).
    public Runnable onToggleCharacter;

    // character selector, at the bottom of the control column: Main supplies
    // the names (setCharacterChoices); picking one -- even the one already
    // selected, as a way to start over -- fires onSelectCharacter with its name.
    public Consumer<String> onSelectCharacter;
    private final JPanel characterPanel = new JPanel();
    private final ButtonGroup characterGroup = new ButtonGroup();
    private final Map<String, JRadioButton> characterButtons = new LinkedHashMap<>();

    // mouse-drag hooks, given world-space points -- Viewer only knows about
    // screen<->world conversion, not about Body/Tip; whoever wires these up
    // (Main) is responsible for e.g. finding the nearest tip and moving it.
    public Consumer<TupleD> onDragStart;
    public BiConsumer<TupleD, TupleD> onDragEnd;

    // fired instead of onDragEnd when the mouse barely moved between press and
    // release -- a plain click rather than a drag. Second argument is whether
    // shift was held.
    public BiConsumer<TupleD, Boolean> onClick;

    // right-button drag -- a completely separate gesture from the left-button
    // ones above (e.g. repositioning an existing anchor rather than kicking a
    // tip), so it gets its own start/continue hooks instead of reusing onDrag*.
    public Consumer<TupleD> onRightDragStart;
    public Consumer<TupleD> onRightDrag;

    private TupleD dragStartWorld;
    private TupleD dragCurrentWorld;
    private int pressScreenX;
    private int pressScreenY;
    private boolean rightDragActive;

    private boolean playing = false;
    private final JButton playPauseButton = new JButton("Play");
    private final Timer timer = new Timer(STEP_DELAY_MS, e -> {
        if (playing) {
            runStep();
        }
    });

    // slider range is 0.15-3.0 seconds, represented as hundredths (15-300)
    // since JSlider only works in integers.
    private static final int MORPH_TIME_MIN_HUNDREDTHS = 15;
    private static final int MORPH_TIME_MAX_HUNDREDTHS = 300;
    private final JSlider morphTimeSlider =
            new JSlider(JSlider.HORIZONTAL, MORPH_TIME_MIN_HUNDREDTHS, MORPH_TIME_MAX_HUNDREDTHS, 100);

    private static class Circle {
        TupleD center;
        double radius;
        Color color;
        boolean filled;

        Circle(TupleD center, double radius, Color color, boolean filled) {
            this.center = center;
            this.radius = radius;
            this.color = color;
            this.filled = filled;
        }
    }

    // a flat polygon (texture == null: vertices in world coords, filled with
    // color) or a textured one (vertices in a local frame given by origin +
    // angleRad, filled with texture laid out in that same frame). One class,
    // one list, so flat and textured polygons keep their draw order.
    private static class Poly {
        TupleD[] vertices;
        Color color;
        BufferedImage texture;
        TupleD origin;
        double angleRad;

        Poly(TupleD[] vertices, Color color) {
            this.vertices = vertices;
            this.color = color;
        }

        Poly(TupleD[] localVertices, TupleD origin, double angleRad, BufferedImage texture) {
            this.vertices = localVertices;
            this.origin = origin;
            this.angleRad = angleRad;
            this.texture = texture;
        }
    }

    private static class Rect {
        TupleD center;
        double width;
        double height;
        Color color;

        Rect(TupleD center, double width, double height, Color color) {
            this.center = center;
            this.width = width;
            this.height = height;
            this.color = color;
        }
    }

    private static class Ellipse {
        TupleD center;
        double majorRadius; // half the length of the main axis
        double minorRadius;
        double angleRad;    // main axis direction, world convention (ccw from +x, y-up)

        Ellipse(TupleD center, double majorRadius, double minorRadius, double angleRad) {
            this.center = center;
            this.majorRadius = majorRadius;
            this.minorRadius = minorRadius;
            this.angleRad = angleRad;
        }
    }

    public Viewer() {
        super("Stick Figure");
        panel.setPreferredSize(new Dimension(DISPLAY_WIDTH, DISPLAY_HEIGHT));
        panel.setBackground(Color.WHITE);
        panel.setFocusable(true); // so it can actually receive key events below
        add(panel, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.EAST);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        panel.requestFocusInWindow();
        timer.start();

        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // OS auto-repeat re-fires keyPressed continuously while held;
                // Main's own "already charging" guard makes repeats harmless,
                // so no de-duplication is needed here.
                if (e.getKeyCode() == KeyEvent.VK_O && onJumpLeft != null) {
                    onJumpLeft.run();
                } else if (e.getKeyCode() == KeyEvent.VK_P && onJumpRight != null) {
                    onJumpRight.run();
                } else if (e.getKeyCode() == KeyEvent.VK_U && onWalkLeft != null) {
                    onWalkLeft.run();
                } else if (e.getKeyCode() == KeyEvent.VK_I && onWalkRight != null) {
                    onWalkRight.run();
                } else if (e.getKeyCode() == KeyEvent.VK_H && onToggleCharacter != null) {
                    onToggleCharacter.run();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if ((e.getKeyCode() == KeyEvent.VK_O || e.getKeyCode() == KeyEvent.VK_P)
                        && onJumpKeyReleased != null) {
                    onJumpKeyReleased.run();
                } else if ((e.getKeyCode() == KeyEvent.VK_U || e.getKeyCode() == KeyEvent.VK_I)
                        && onWalkKeyReleased != null) {
                    onWalkKeyReleased.run();
                }
            }
        });

        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                panel.requestFocusInWindow(); // clicking the panel should also let it catch keys
                if (e.getButton() == MouseEvent.BUTTON3) {
                    rightDragActive = true;
                    if (onRightDragStart != null) {
                        onRightDragStart.accept(toWorld(e.getX(), e.getY()));
                    }
                    return;
                }
                pressScreenX = e.getX();
                pressScreenY = e.getY();
                dragStartWorld = toWorld(e.getX(), e.getY());
                dragCurrentWorld = dragStartWorld;
                if (onDragStart != null) {
                    onDragStart.accept(dragStartWorld);
                }
                panel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (rightDragActive) {
                    rightDragActive = false;
                    return;
                }
                if (dragStartWorld != null) {
                    int dx = e.getX() - pressScreenX;
                    int dy = e.getY() - pressScreenY;
                    boolean wasClick = dx * dx + dy * dy <= CLICK_THRESHOLD_PIXELS * CLICK_THRESHOLD_PIXELS;
                    if (wasClick) {
                        if (onClick != null) {
                            onClick.accept(dragStartWorld, e.isShiftDown());
                        }
                    } else {
                        dragCurrentWorld = toWorld(e.getX(), e.getY());
                        if (onDragEnd != null) {
                            onDragEnd.accept(dragStartWorld, dragCurrentWorld);
                        }
                    }
                }
                dragStartWorld = null;
                dragCurrentWorld = null;
                panel.repaint();
            }
        });
        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (rightDragActive) {
                    if (onRightDrag != null) {
                        onRightDrag.accept(toWorld(e.getX(), e.getY()));
                    }
                    return;
                }
                if (dragStartWorld != null) {
                    dragCurrentWorld = toWorld(e.getX(), e.getY());
                    panel.repaint();
                }
            }
        });
    }

    private TupleD toWorld(int screenX, int screenY) {
        return new TupleD((screenX - ORIGIN_X) / SCALE + cameraFocus.first,
                (ORIGIN_Y - screenY) / SCALE + cameraFocus.second);
    }

    // the world point that should render at screen center from now on --
    // call every frame (e.g. right before clearing/redrawing) to keep the
    // view scrolled to wherever that point currently is.
    public void setCameraFocus(TupleD worldPos) {
        this.cameraFocus = worldPos;
    }

    // loads (once) the image tiled behind everything else -- Main decides
    // which file, Viewer just handles the Swing-side loading/rendering.
    public void loadBackgroundImage(String filePath) throws IOException {
        backgroundImage = ImageIO.read(new File(filePath));
        panel.repaint();
    }

    // a length in world units that renders as a fixed number of screen pixels
    // regardless of SCALE -- e.g. for a UI marker that shouldn't grow/shrink
    // if the viewer's zoom ever changes.
    public static double pixelsToWorldLength(double pixels) {
        return pixels / SCALE;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        playPauseButton.addActionListener(e -> {
            playing = !playing;
            playPauseButton.setText(playing ? "Pause" : "Play");
        });

        JButton stepButton = new JButton("Step");
        stepButton.addActionListener(e -> runStep());

        JButton resetButton = new JButton("Reset");
        resetButton.addActionListener(e -> runReset());

        JButton storePoseButton = new JButton("StorePose");
        storePoseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Store Pose");
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION && onStorePose != null) {
                onStorePose.accept(chooser.getSelectedFile());
            }
        });

        JButton loadPoseButton = new JButton("LoadPose");
        loadPoseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Load Pose");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && onLoadPose != null) {
                onLoadPose.accept(chooser.getSelectedFile());
            }
        });

        JLabel morphingLabel = new JLabel("Morphing");

        JButton loadTargetButton = new JButton("Load Target");
        loadTargetButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Load Target");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && onLoadTarget != null) {
                onLoadTarget.accept(chooser.getSelectedFile());
            }
        });

        JButton morphButton = new JButton("Morph");
        morphButton.addActionListener(e -> {
            if (onMorph != null) {
                onMorph.run();
            }
        });

        JButton jumpButton = new JButton("JUMP");
        jumpButton.addActionListener(e -> {
            if (onJump != null) {
                onJump.run();
            }
        });

        for (JButton b : new JButton[]{playPauseButton, stepButton, resetButton, storePoseButton,
                loadPoseButton, loadTargetButton, morphButton, jumpButton}) {
            b.setPreferredSize(BUTTON_SIZE);
            b.setMaximumSize(BUTTON_SIZE);
        }

        controls.add(playPauseButton);
        controls.add(stepButton);
        controls.add(resetButton);
        controls.add(storePoseButton);
        controls.add(loadPoseButton);
        controls.add(Box.createVerticalStrut(12));
        controls.add(morphingLabel);
        controls.add(loadTargetButton);
        controls.add(morphButton);
        controls.add(morphTimeSlider);
        controls.add(Box.createVerticalStrut(12));
        controls.add(jumpButton);
        controls.add(Box.createVerticalStrut(12));
        JLabel characterLabel = new JLabel("Character");
        characterLabel.setAlignmentX(LEFT_ALIGNMENT);
        characterPanel.setLayout(new BoxLayout(characterPanel, BoxLayout.Y_AXIS));
        characterPanel.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(characterLabel);
        controls.add(characterPanel);
        return controls;
    }

    // (re)fills the character selector with one radio button per name,
    // `selected` checked. The buttons never take keyboard focus, so the draw
    // panel keeps getting u/i/o/p/h right after a switch.
    public void setCharacterChoices(List<String> names, String selected) {
        characterPanel.removeAll();
        characterButtons.clear();
        for (String name : names) {
            JRadioButton button = new JRadioButton(name, name.equals(selected));
            button.setFocusable(false);
            button.addActionListener(e -> {
                if (onSelectCharacter != null) {
                    onSelectCharacter.accept(name);
                }
                panel.requestFocusInWindow();
            });
            characterGroup.add(button);
            characterButtons.put(name, button);
            characterPanel.add(button);
        }
        characterPanel.revalidate();
        characterPanel.repaint();
    }

    // -------------------------------------------------------------------------
    // checks `name` in the selector without firing onSelectCharacter -- e.g.
    // when the figure was switched some other way (the 'h' key).
    public void setSelectedCharacter(String name) {
        JRadioButton button = characterButtons.get(name);
        if (button != null) {
            button.setSelected(true);
        }
    }

    // -------------------------------------------------------------------------
    // the morph duration currently selected on the slider, in seconds.
    public double getMorphTimeSeconds() {
        return morphTimeSlider.getValue() / 100.0;
    }

    private void runStep() {
        if (onStep != null) {
            onStep.run();
        }
    }

    private void runReset() {
        stopPlaying();
        if (onReset != null) {
            onReset.run();
        }
    }

    // stops Play (as if Pause were pressed) without touching anything else --
    // e.g. so whoever wires onStep can end playback once a morph completes.
    public void stopPlaying() {
        playing = false;
        playPauseButton.setText("Play");
    }

    // starts Play (as if it were pressed) without touching anything else --
    // e.g. so an automated sequence (Jump) can set itself running.
    public void startPlaying() {
        playing = true;
        playPauseButton.setText("Pause");
    }

    public void drawLine(TupleD from, TupleD to) {
        lines.add(new TupleD[]{from, to});
        panel.repaint();
    }

    public void drawCircle(TupleD center, double radius) {
        drawCircle(center, radius, Color.BLACK);
    }

    public void drawCircle(TupleD center, double radius, Color color) {
        circles.add(new Circle(center, radius, color, false));
        panel.repaint();
    }

    // a filled circle -- e.g. HeadBone's own head, as opposed to the plain
    // outline anchor markers use via the other drawCircle overloads.
    public void drawFilledCircle(TupleD center, double radius, Color color) {
        circles.add(new Circle(center, radius, color, true));
        panel.repaint();
    }

    // an ellipse whose main axis has length 2*majorRadius, pointing in
    // direction angleRad (world convention: counterclockwise from +x, y up).
    public void drawEllipse(TupleD center, double majorRadius, double minorRadius, double angleRad) {
        ellipses.add(new Ellipse(center, majorRadius, minorRadius, angleRad));
        panel.repaint();
    }

    // an axis-aligned filled rectangle -- e.g. a charge-meter bar.
    public void drawRect(TupleD center, double width, double height, Color color) {
        rects.add(new Rect(center, width, height, color));
        panel.repaint();
    }

    // a filled, closed polygon -- e.g. an animation.Polygon obstacle's
    // interior. Use a translucent color (non-opaque alpha) if it shouldn't
    // hide whatever's drawn under/over it.
    public void drawPolygon(TupleD[] vertices, Color color) {
        polys.add(new Poly(vertices, color));
        panel.repaint();
    }

    // a polygon filled with a texture, both defined in a local frame: origin
    // (world coords) with its x-axis at angleRad -- e.g. a bone's own frame
    // (see Bone.localToWorld), so the texture moves and rotates with it. Tile
    // origin at the frame origin, image upright when the frame points right,
    // one image pixel per screen pixel (tiles repeat if the polygon is
    // larger) -- the same layout editor.SkinEditor previews.
    public void drawTexturedPolygon(TupleD[] localVertices, TupleD origin, double angleRad, BufferedImage texture) {
        polys.add(new Poly(localVertices, origin, angleRad, texture));
        panel.repaint();
    }

    public void clear() {
        lines.clear();
        circles.clear();
        ellipses.clear();
        rects.clear();
        polys.clear();
        panel.repaint();
    }

    // draws a fixed green horizontal line at world y=0, e.g. to mark the ground.
    // Not affected by clear() -- it's a stage element, not part of the figure.
    public void drawFloor() {
        floorVisible = true;
        panel.repaint();
    }

    private class DrawPanel extends JPanel {

        // fills p (a textured Poly) in its own local frame. Drawn in a y-down
        // copy of that frame (local y negated) so the image isn't mirrored:
        // screen = frameOrigin + rotate(-angle) * SCALE * (x, -y), with the
        // texture tiled at image-pixels / SCALE world units per tile.
        private void fillTexturedPolygon(Graphics2D g2, Poly p, int cx, int cy) {
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < p.vertices.length; i++) {
                if (i == 0) {
                    path.moveTo(p.vertices[i].first, -p.vertices[i].second);
                } else {
                    path.lineTo(p.vertices[i].first, -p.vertices[i].second);
                }
            }
            path.closePath();

            AffineTransform savedTransform = g2.getTransform();
            Paint savedPaint = g2.getPaint();
            g2.translate(cx + p.origin.first * SCALE, cy - p.origin.second * SCALE);
            g2.rotate(-p.angleRad);
            g2.scale(SCALE, SCALE);
            g2.setPaint(new TexturePaint(p.texture, new Rectangle2D.Double(0, 0,
                    p.texture.getWidth() / SCALE, p.texture.getHeight() / SCALE)));
            g2.fill(path);
            g2.setPaint(savedPaint);
            g2.setTransform(savedTransform);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            // snapshot each draw list before iterating -- Main can clear()/
            // repopulate them from the next onStep tick at any time, and
            // iterating the live list directly occasionally raced against
            // that (a ConcurrentModificationException, seen intermittently
            // in practice). Iterating a frozen copy instead makes that
            // impossible: worst case this paint shows one tick's stale/mixed
            // frame, harmless at 60fps since the next paint self-corrects.
            List<Poly> polysSnapshot = new ArrayList<>(polys);
            List<TupleD[]> linesSnapshot = new ArrayList<>(lines);
            List<Circle> circlesSnapshot = new ArrayList<>(circles);
            List<Rect> rectsSnapshot = new ArrayList<>(rects);
            List<Ellipse> ellipsesSnapshot = new ArrayList<>(ellipses);

            if (backgroundImage != null) {
                int imgW = backgroundImage.getWidth();
                int imgH = backgroundImage.getHeight();
                // same screen-shift math as the foreground's cx/cy below, just
                // scaled by BACKGROUND_PARALLAX_FACTOR (<1) so it moves less --
                // farther away, visually. Wrapped into a single tile's worth via
                // modulo so it tiles seamlessly no matter how far the camera
                // has scrolled, rather than the offset growing without bound.
                int shiftX = -(int) Math.round(cameraFocus.first * SCALE * BACKGROUND_PARALLAX_FACTOR);
                int shiftY = (int) Math.round(cameraFocus.second * SCALE * BACKGROUND_PARALLAX_FACTOR);
                int startX = ((shiftX % imgW) + imgW) % imgW - imgW;
                int startY = ((shiftY % imgH) + imgH) % imgH - imgH;
                Composite oldComposite = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, BACKGROUND_ALPHA));
                for (int x = startX; x < getWidth(); x += imgW) {
                    for (int y = startY; y < getHeight(); y += imgH) {
                        g2.drawImage(backgroundImage, x, y, null);
                    }
                }
                g2.setComposite(oldComposite);
            }

            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(2));

            // shifting the effective screen origin by the camera focus (in
            // screen pixels) is equivalent to drawing every world point
            // relative to cameraFocus instead of the world origin -- so
            // cameraFocus itself always lands exactly at (ORIGIN_X, ORIGIN_Y).
            int cx = ORIGIN_X - (int) Math.round(cameraFocus.first * SCALE);
            int cy = ORIGIN_Y + (int) Math.round(cameraFocus.second * SCALE);

            if (floorVisible) {
                g2.setColor(Color.GREEN);
                g2.setStroke(new BasicStroke(6));
                g2.drawLine(0, cy, getWidth(), cy);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(4));
            }

            for (Poly p : polysSnapshot) {
                if (p.texture != null) {
                    fillTexturedPolygon(g2, p, cx, cy);
                    continue;
                }
                int n = p.vertices.length;
                int[] xs = new int[n];
                int[] ys = new int[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = cx + (int) Math.round(p.vertices[i].first * SCALE);
                    ys[i] = cy - (int) Math.round(p.vertices[i].second * SCALE);
                }
                g2.setColor(p.color);
                g2.fillPolygon(xs, ys, n);
            }
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(2));

            for (TupleD[] line : linesSnapshot) {
                int x1 = cx + (int) Math.round(line[0].first * SCALE);
                int y1 = cy - (int) Math.round(line[0].second * SCALE);
                int x2 = cx + (int) Math.round(line[1].first * SCALE);
                int y2 = cy - (int) Math.round(line[1].second * SCALE);
                g2.drawLine(x1, y1, x2, y2);
            }

            for (Circle c : circlesSnapshot) {
                int ccx = cx + (int) Math.round(c.center.first * SCALE);
                int ccy = cy - (int) Math.round(c.center.second * SCALE);
                int r = (int) Math.round(c.radius * SCALE);
                g2.setColor(c.color);
                if (c.filled) {
                    g2.fillOval(ccx - r, ccy - r, 2 * r, 2 * r);
                } else {
                    g2.drawOval(ccx - r, ccy - r, 2 * r, 2 * r);
                }
            }

            for (Rect r : rectsSnapshot) {
                int w = (int) Math.round(r.width * SCALE);
                int h = (int) Math.round(r.height * SCALE);
                int rx = cx + (int) Math.round(r.center.first * SCALE) - w / 2;
                int ry = cy - (int) Math.round(r.center.second * SCALE) - h / 2;
                g2.setColor(r.color);
                g2.fillRect(rx, ry, w, h);
            }

            for (Ellipse e : ellipsesSnapshot) {
                int ecx = cx + (int) Math.round(e.center.first * SCALE);
                int ecy = cy - (int) Math.round(e.center.second * SCALE);
                int majorPx = (int) Math.round(e.majorRadius * SCALE);
                int minorPx = (int) Math.round(e.minorRadius * SCALE);

                // screen y is flipped relative to world y, which flips the
                // sense of rotation too -- negate the world angle to compensate.
                AffineTransform saved = g2.getTransform();
                g2.translate(ecx, ecy);
                g2.rotate(-e.angleRad);
                g2.setColor(ELLIPSE_FILL);
                g2.fillOval(-majorPx, -minorPx, 2 * majorPx, 2 * minorPx);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1));
                g2.drawOval(-majorPx, -minorPx, 2 * majorPx, 2 * minorPx);
                g2.setTransform(saved);
            }

            if (dragStartWorld != null) {
                int x1 = cx + (int) Math.round(dragStartWorld.first * SCALE);
                int y1 = cy - (int) Math.round(dragStartWorld.second * SCALE);
                int x2 = cx + (int) Math.round(dragCurrentWorld.first * SCALE);
                int y2 = cy - (int) Math.round(dragCurrentWorld.second * SCALE);
                g2.setColor(Color.ORANGE);
                g2.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4, 3}, 0));
                g2.drawLine(x1, y1, x2, y2);
                g2.fillOval(x1 - 4, y1 - 4, 8, 8);
            }
        }
    }
}
