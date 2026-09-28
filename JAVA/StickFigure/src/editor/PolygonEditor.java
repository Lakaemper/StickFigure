package editor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import utils.Json;

// -----------------------------------------------------------------------------
// A minimal single-polygon editor: click out corners in Draw mode until the
// click lands back near the first corner (closing the shape), or drag
// existing corners around in Edit mode. Saves/loads just the corner points as
// JSON, in pixel coordinates exactly as drawn -- no game-specific scaling.
public class PolygonEditor extends JFrame {

    private static final int WINDOW_WIDTH = 1200;
    private static final int WINDOW_HEIGHT = 900;
    private static final int CLOSE_RADIUS_PIXELS = 5; // how close a new click must be to the
                                                        // first corner to close the polygon
    private static final int DRAG_PICK_RADIUS_PIXELS = 8; // how close a press must be to an
                                                            // existing corner to grab it in Edit mode
    private static final int GRID_SIZE_PIXELS = 10;
    private static final int CENTER_CROSS_SIZE_PIXELS = 8;

    private enum Mode {
        DRAW, EDIT
    }

    private Mode mode = Mode.DRAW;
    private final List<Point> points = new ArrayList<>();
    private boolean closed = false;
    private boolean fitToGrid = false;
    private int draggedPointIndex = -1;
    private boolean draggingCenter = false;

    // an independent reference point, not the corners' average kept in sync --
    // set to that average once when the polygon closes, but from then on only
    // moves when explicitly dragged, leaving every corner's on-screen position
    // untouched. Corners are saved/loaded relative to wherever this ends up.
    private Point center = new Point(0, 0);

    // pixels per world unit -- matches the main game's Viewer.SCALE, so a
    // saved shape's corners/center can be converted to world units there.
    private double scale = 200.0;

    private final CanvasPanel canvas = new CanvasPanel();

    // -------------------------------------------------------------------------
    public PolygonEditor() {
        super("Polygon Editor");
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        setJMenuBar(buildMenuBar());
        add(buildLeftPanel(), BorderLayout.WEST);
        add(canvas, BorderLayout.CENTER);

        setVisible(true);
    }

    // -------------------------------------------------------------------------
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem loadItem = new JMenuItem("Load");
        loadItem.addActionListener(e -> onLoad());
        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.addActionListener(e -> onSave());

        fileMenu.add(loadItem);
        fileMenu.add(saveItem);
        menuBar.add(fileMenu);
        return menuBar;
    }

    // -------------------------------------------------------------------------
    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(150, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JToggleButton drawButton = new JToggleButton("Draw", true);
        JToggleButton editButton = new JToggleButton("Edit", false);
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(drawButton);
        modeGroup.add(editButton);
        drawButton.addActionListener(e -> {
            mode = Mode.DRAW;
            draggedPointIndex = -1;
        });
        editButton.addActionListener(e -> {
            mode = Mode.EDIT;
        });

        JButton newButton = new JButton("New");
        newButton.addActionListener(e -> {
            points.clear();
            closed = false;
            center = new Point(0, 0);
            draggedPointIndex = -1;
            draggingCenter = false;
            canvas.repaint();
        });

        JCheckBox gridCheckBox = new JCheckBox("Fit to grid");
        gridCheckBox.addActionListener(e -> {
            fitToGrid = gridCheckBox.isSelected();
            canvas.repaint();
        });

        for (JToggleButton b : new JToggleButton[]{drawButton, editButton}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        newButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        gridCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(drawButton);
        panel.add(Box.createVerticalStrut(6));
        panel.add(editButton);
        panel.add(Box.createVerticalStrut(20));
        panel.add(newButton);
        panel.add(Box.createVerticalStrut(20));
        panel.add(gridCheckBox);
        return panel;
    }

    // -------------------------------------------------------------------------
    // snaps a point to the nearest grid intersection if "Fit to grid" is on --
    // a no-op otherwise. Applies in both Draw (placing a new corner) and Edit
    // (dragging an existing one).
    private Point snap(Point p) {
        if (!fitToGrid) {
            return p;
        }
        int x = Math.round(p.x / (float) GRID_SIZE_PIXELS) * GRID_SIZE_PIXELS;
        int y = Math.round(p.y / (float) GRID_SIZE_PIXELS) * GRID_SIZE_PIXELS;
        return new Point(x, y);
    }

    // -------------------------------------------------------------------------
    // adds a new corner at (the possibly grid-snapped) point, unless the
    // polygon is already closed (use New to start over), or this click lands
    // within CLOSE_RADIUS_PIXELS of the first corner -- which closes the
    // polygon instead of adding a near-duplicate point.
    private void handleDrawClick(Point rawPoint) {
        if (closed) {
            return;
        }
        Point p = snap(rawPoint);
        if (points.size() >= 3 && p.distance(points.get(0)) <= CLOSE_RADIUS_PIXELS) {
            closed = true;
            center = averageOfPoints();
            canvas.repaint();
            return;
        }
        points.add(p);
        canvas.repaint();
    }

    // -------------------------------------------------------------------------
    // the average of every corner -- used only to give the center a sensible
    // starting position the moment the polygon closes. (0,0) if there are no
    // points, though that's never actually reached (only called on close).
    private Point averageOfPoints() {
        if (points.isEmpty()) {
            return new Point(0, 0);
        }
        double sumX = 0;
        double sumY = 0;
        for (Point p : points) {
            sumX += p.x;
            sumY += p.y;
        }
        return new Point((int) Math.round(sumX / points.size()), (int) Math.round(sumY / points.size()));
    }

    // -------------------------------------------------------------------------
    // a press on the center cross (only shown once closed) grabs the center
    // to reposition on its own; otherwise, a press on a corner grabs just that one.
    private void handleEditPress(Point rawPoint) {
        draggedPointIndex = -1;
        draggingCenter = false;
        if (closed && center.distance(rawPoint) <= DRAG_PICK_RADIUS_PIXELS) {
            draggingCenter = true;
            return;
        }
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).distance(rawPoint) <= DRAG_PICK_RADIUS_PIXELS) {
                draggedPointIndex = i;
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // right-click removes the nearest corner, in either mode -- a no-op if
    // nothing is within pick range. Dropping below 3 corners reopens the
    // polygon for drawing (an already-closed shape stays closed if it still
    // has enough corners left).
    private void eraseCornerNear(Point rawPoint) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).distance(rawPoint) <= DRAG_PICK_RADIUS_PIXELS) {
                points.remove(i);
                if (points.size() < 3) {
                    closed = false;
                }
                draggedPointIndex = -1;
                draggingCenter = false;
                canvas.repaint();
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    private void onSave() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Polygon");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"Type\": \"Polygon\",\n");
        sb.append("  \"Scale\": ").append(scale).append(",\n");
        sb.append("  \"Points\": [\n");
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            sb.append("    [").append(p.x - center.x).append(", ").append(p.y - center.y).append("]");
            sb.append(i < points.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), sb.toString());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void onLoad() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Polygon");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            String text = Files.readString(chooser.getSelectedFile().toPath());
            Map<String, Object> root = (Map<String, Object>) Json.parse(text);
            scale = root.containsKey("Scale") ? ((Number) root.get("Scale")).doubleValue() : 200.0;
            // the center isn't stored -- a saved file's Points are already
            // relative to it, so loading just places them back around a
            // fresh center at the middle of the window, purely so the shape
            // lands somewhere visible/editable instead of clustered at the
            // screen's top-left corner (where (0,0) would otherwise put it).
            center = new Point(canvas.getWidth() / 2, canvas.getHeight() / 2);
            List<Object> pointsJson = (List<Object>) root.get("Points");
            points.clear();
            for (Object o : pointsJson) {
                List<Object> pair = (List<Object>) o;
                int x = center.x + ((Number) pair.get(0)).intValue();
                int y = center.y + ((Number) pair.get(1)).intValue();
                points.add(new Point(x, y));
            }
            closed = points.size() >= 3;
            draggedPointIndex = -1;
            draggingCenter = false;
            canvas.repaint();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    private class CanvasPanel extends JPanel {

        CanvasPanel() {
            setBackground(Color.WHITE);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON3) {
                        eraseCornerNear(e.getPoint());
                        return;
                    }
                    if (mode == Mode.DRAW) {
                        handleDrawClick(e.getPoint());
                    } else {
                        handleEditPress(e.getPoint());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    draggedPointIndex = -1;
                    draggingCenter = false;
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (mode != Mode.EDIT) {
                        return;
                    }
                    if (draggingCenter) {
                        center = snap(e.getPoint());
                        repaint();
                    } else if (draggedPointIndex >= 0) {
                        points.set(draggedPointIndex, snap(e.getPoint()));
                        repaint();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            if (fitToGrid) {
                g2.setColor(new Color(230, 230, 230));
                for (int x = 0; x < getWidth(); x += GRID_SIZE_PIXELS) {
                    g2.drawLine(x, 0, x, getHeight());
                }
                for (int y = 0; y < getHeight(); y += GRID_SIZE_PIXELS) {
                    g2.drawLine(0, y, getWidth(), y);
                }
            }

            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(closed ? 5 : 2));
            for (int i = 0; i + 1 < points.size(); i++) {
                Point a = points.get(i);
                Point b = points.get(i + 1);
                g2.drawLine(a.x, a.y, b.x, b.y);
            }
            if (closed && points.size() >= 3) {
                Point a = points.get(points.size() - 1);
                Point b = points.get(0);
                g2.drawLine(a.x, a.y, b.x, b.y);
            }

            g2.setColor(Color.RED);
            for (Point p : points) {
                g2.fillOval(p.x - 4, p.y - 4, 8, 8);
            }

            if (closed && points.size() >= 3) {
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(2));
                g2.drawLine(center.x - CENTER_CROSS_SIZE_PIXELS, center.y, center.x + CENTER_CROSS_SIZE_PIXELS, center.y);
                g2.drawLine(center.x, center.y - CENTER_CROSS_SIZE_PIXELS, center.x, center.y + CENTER_CROSS_SIZE_PIXELS);
            }
        }
    }

    // -------------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(PolygonEditor::new);
    }
}
