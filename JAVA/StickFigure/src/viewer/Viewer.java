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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import utils.TupleD;

public class Viewer extends JFrame {

    private static final int SIZE = 800;
    private static final double SCALE = 200.0; // pixels per unit

    private static final int ORIGIN_X = 400;
    private static final int ORIGIN_Y = 600;

    private static final int STEP_DELAY_MS = 16; // ~60fps while playing

    private final List<TupleD[]> lines = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();
    private boolean floorVisible = false;
    private final DrawPanel panel = new DrawPanel();

    // set by whoever wants Play/Step/Reset to actually do something (e.g. Main
    // wiring up a physics.World) -- Viewer itself has no idea what a step or a
    // reset means.
    public Runnable onStep;
    public Runnable onReset;

    // mouse-drag hooks, given world-space points -- Viewer only knows about
    // screen<->world conversion, not about Body/Tip; whoever wires these up
    // (Main) is responsible for e.g. finding the nearest tip and moving it.
    public Consumer<TupleD> onDragStart;
    public BiConsumer<TupleD, TupleD> onDragEnd;

    private TupleD dragStartWorld;
    private TupleD dragCurrentWorld;

    private boolean playing = false;
    private final JButton playPauseButton = new JButton("Play");
    private final Timer timer = new Timer(STEP_DELAY_MS, e -> {
        if (playing) {
            runStep();
        }
    });

    private static class Circle {
        TupleD center;
        double radius;

        Circle(TupleD center, double radius) {
            this.center = center;
            this.radius = radius;
        }
    }

    public Viewer() {
        super("Stick Figure");
        panel.setPreferredSize(new Dimension(SIZE, SIZE));
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
                dragStartWorld = toWorld(e.getX(), e.getY());
                dragCurrentWorld = dragStartWorld;
                if (onDragStart != null) {
                    onDragStart.accept(dragStartWorld);
                }
                panel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragStartWorld != null) {
                    dragCurrentWorld = toWorld(e.getX(), e.getY());
                    if (onDragEnd != null) {
                        onDragEnd.accept(dragStartWorld, dragCurrentWorld);
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

        controls.add(playPauseButton);
        controls.add(stepButton);
        controls.add(resetButton);
        return controls;
    }

    private void runStep() {
        if (onStep != null) {
            onStep.run();
        }
    }

    private void runReset() {
        playing = false;
        playPauseButton.setText("Play");
        if (onReset != null) {
            onReset.run();
        }
    }

    public void drawLine(TupleD from, TupleD to) {
        lines.add(new TupleD[]{from, to});
        panel.repaint();
    }

    public void drawCircle(TupleD center, double radius) {
        circles.add(new Circle(center, radius));
        panel.repaint();
    }

    public void clear() {
        lines.clear();
        circles.clear();
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
                g2.drawOval(ccx - r, ccy - r, 2 * r, 2 * r);
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
