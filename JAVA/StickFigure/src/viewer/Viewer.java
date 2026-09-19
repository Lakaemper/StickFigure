package viewer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.Timer;
import utils.TupleD;

public class Viewer extends JFrame {

    private static final int DISPLAY_WIDTH = 2000;
    private static final int DISPLAY_HEIGHT = 1200;
    private static final double SCALE = 200.0; // pixels per unit

    private static final Dimension BUTTON_SIZE = new Dimension(130, 28); // uniform, independent of label text

    private static final int ORIGIN_X = 1000;
    private static final int ORIGIN_Y = 600;

    private static final int STEP_DELAY_MS = 16; // ~60fps while playing
    private static final int CLICK_THRESHOLD_PIXELS = 5; // press-release moves less than this = a click, not a drag

    private static final Color ELLIPSE_FILL = new Color(255, 0, 0, (int) Math.round(0.7 * 255));

    private final List<TupleD[]> lines = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();
    private final List<Ellipse> ellipses = new ArrayList<>();
    private boolean floorVisible = false;
    private final DrawPanel panel = new DrawPanel();

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

        Circle(TupleD center, double radius, Color color) {
            this.center = center;
            this.radius = radius;
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
        add(panel, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.EAST);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        timer.start();

        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
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

    private static TupleD toWorld(int screenX, int screenY) {
        return new TupleD((screenX - ORIGIN_X) / SCALE, (ORIGIN_Y - screenY) / SCALE);
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

        for (JButton b : new JButton[]{playPauseButton, stepButton, resetButton, storePoseButton,
                loadPoseButton, loadTargetButton, morphButton}) {
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
        return controls;
    }

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

    public void drawLine(TupleD from, TupleD to) {
        lines.add(new TupleD[]{from, to});
        panel.repaint();
    }

    public void drawCircle(TupleD center, double radius) {
        drawCircle(center, radius, Color.BLACK);
    }

    public void drawCircle(TupleD center, double radius, Color color) {
        circles.add(new Circle(center, radius, color));
        panel.repaint();
    }

    // an ellipse whose main axis has length 2*majorRadius, pointing in
    // direction angleRad (world convention: counterclockwise from +x, y up).
    public void drawEllipse(TupleD center, double majorRadius, double minorRadius, double angleRad) {
        ellipses.add(new Ellipse(center, majorRadius, minorRadius, angleRad));
        panel.repaint();
    }

    public void clear() {
        lines.clear();
        circles.clear();
        ellipses.clear();
        panel.repaint();
    }

    // draws a fixed green horizontal line at world y=0, e.g. to mark the ground.
    // Not affected by clear() -- it's a stage element, not part of the figure.
    public void drawFloor() {
        floorVisible = true;
        panel.repaint();
    }

    private class DrawPanel extends JPanel {

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(2));

            int cx = ORIGIN_X;
            int cy = ORIGIN_Y;

            if (floorVisible) {
                g2.setColor(Color.GREEN);
                g2.setStroke(new BasicStroke(6));
                g2.drawLine(0, cy, getWidth(), cy);
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(4));
            }

            for (TupleD[] line : lines) {
                int x1 = cx + (int) Math.round(line[0].first * SCALE);
                int y1 = cy - (int) Math.round(line[0].second * SCALE);
                int x2 = cx + (int) Math.round(line[1].first * SCALE);
                int y2 = cy - (int) Math.round(line[1].second * SCALE);
                g2.drawLine(x1, y1, x2, y2);
            }

            for (Circle c : circles) {
                int ccx = cx + (int) Math.round(c.center.first * SCALE);
                int ccy = cy - (int) Math.round(c.center.second * SCALE);
                int r = (int) Math.round(c.radius * SCALE);
                g2.setColor(c.color);
                g2.drawOval(ccx - r, ccy - r, 2 * r, 2 * r);
            }

            for (Ellipse e : ellipses) {
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
