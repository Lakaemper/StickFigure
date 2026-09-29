package animation;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import main.Main;
import utils.Json;
import utils.TupleD;
import viewer.Viewer;

// -----------------------------------------------------------------------------
// A static, closed, simple (non-self-intersecting) polygonal obstacle in world
// space, vertices in counter-clockwise order. World checks each FrictionPad
// tip against every registered Polygon the same way it already checks the
// flat floor -- push a penetrating tip back to the nearest boundary point,
// then apply Coulomb friction along that edge (see FrictionPad.enforceContact).
public class Polygon {
    public final TupleD[] vertices;

    // -------------------------------------------------------------------------
    public Polygon(TupleD[] vertices) {
        this.vertices = vertices;
    }

    // -------------------------------------------------------------------------
    // standard ray-casting point-in-polygon test -- works for convex or
    // concave simple polygons, regardless of winding order.
    public boolean contains(TupleD p) {
        boolean inside = false;
        int n = vertices.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            TupleD vi = vertices[i];
            TupleD vj = vertices[j];
            boolean crosses = (vi.second > p.second) != (vj.second > p.second);
            if (crosses) {
                double xIntersect = vj.first + (p.second - vj.second) / (vi.second - vj.second) * (vi.first - vj.first);
                if (p.first < xIntersect) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    // -------------------------------------------------------------------------
    // heights of every UPWARD-facing edge crossing the vertical line at x --
    // the surfaces something could stand on there, one per ledge (a rock
    // with an overhang has several). Relies on counter-clockwise winding: an
    // edge running right-to-left has its outward normal pointing up, so the
    // underside of an overhang (left-to-right) is never mistaken for ground.
    public List<Double> topSurfaceHeightsAt(double x) {
        List<Double> heights = new java.util.ArrayList<>();
        int n = vertices.length;
        for (int i = 0; i < n; i++) {
            TupleD a = vertices[i];
            TupleD b = vertices[(i + 1) % n];
            if (b.first < a.first && x >= b.first && x < a.first) {
                double u = (x - a.first) / (b.first - a.first);
                heights.add(a.second + u * (b.second - a.second));
            }
        }
        return heights;
    }

    // -------------------------------------------------------------------------
    // the closest point ON the polygon's boundary to p (clamped per-edge
    // projection, so this is correct for concave polygons too, not just
    // convex ones), and that edge's outward-pointing unit normal -- assumes
    // counter-clockwise winding, which is what makes "outward" well-defined.
    public ClosestPoint closestBoundaryPoint(TupleD p) {
        double bestDistSqr = Double.MAX_VALUE;
        TupleD bestPoint = null;
        TupleD bestNormal = null;
        int n = vertices.length;
        for (int i = 0; i < n; i++) {
            TupleD a = vertices[i];
            TupleD b = vertices[(i + 1) % n];
            TupleD edge = b.sub(a);
            double edgeLenSqr = edge.lengthSqr();
            double t = edgeLenSqr == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, p.sub(a).dot(edge) / edgeLenSqr));
            TupleD closest = a.add(edge.times(t));
            double distSqr = p.distSqr(closest);
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                bestPoint = closest;
                double edgeLen = Math.sqrt(edgeLenSqr);
                bestNormal = edgeLen == 0.0 ? new TupleD(0.0, 1.0) : new TupleD(edge.second / edgeLen, -edge.first / edgeLen);
            }
        }
        return new ClosestPoint(bestPoint, bestNormal);
    }

    // -------------------------------------------------------------------------
    // loads a polygon saved by editor.PolygonEditor -- its corners are in
    // screen pixels, relative to a local origin (the editor's own "center",
    // which isn't itself stored -- see PolygonEditor). Converts pixels to
    // world units using the file's own "Scale" (pixels per unit) field --
    // falling back to Viewer's own current pixels-per-unit if the field is
    // absent, rather than a hardcoded number that could silently drift out
    // of sync with it -- so a shape saved at a different scale actually
    // comes out a different size, rather than always being read back at a
    // fixed scale regardless of what the file says. Negates Y since screen
    // pixels increase downward
    // but world space is y-up, then places the result so the local origin
    // lands at worldCenter. Winding is normalized to counter-clockwise
    // (positive shoelace area) regardless of the order corners were clicked
    // in, since this class's own outward-normal math (closestBoundaryPoint)
    // assumes CCW.
    public static Polygon load(String filePath, TupleD worldCenter) throws IOException {
        return load(filePath, worldCenter, false);
    }

    // -------------------------------------------------------------------------
    // same as load(filePath, worldCenter), but flips the shape horizontally
    // around its own local origin first -- e.g. so the same rock file's flat
    // "back" edge (typically at local x=0) ends up facing the opposite way,
    // for placing two copies back-to-back with their fronts pointing apart.
    @SuppressWarnings("unchecked")
    public static Polygon load(String filePath, TupleD worldCenter, boolean mirrorX) throws IOException {
        String text = Files.readString(Paths.get(filePath));
        Map<String, Object> root = (Map<String, Object>) Json.parse(text);
        double pixelsPerUnit = root.containsKey("Scale")
                ? ((Number) root.get("Scale")).doubleValue()
                : 1.0 / Viewer.pixelsToWorldLength(1.0);
        List<Object> pointsJson = (List<Object>) root.get("Points");

        TupleD[] verts = new TupleD[pointsJson.size()];
        for (int i = 0; i < pointsJson.size(); i++) {
            List<Object> pair = (List<Object>) pointsJson.get(i);
            double pixelX = ((Number) pair.get(0)).doubleValue();
            double pixelY = ((Number) pair.get(1)).doubleValue();
            double worldX = (mirrorX ? -pixelX : pixelX) / pixelsPerUnit;
            double worldY = -pixelY / pixelsPerUnit;
            verts[i] = worldCenter.add(new TupleD(worldX, worldY));
        }

        if (signedArea(verts) < 0.0) {
            reverse(verts);
        }
        return new Polygon(verts);
    }

    // -------------------------------------------------------------------------
    private static double signedArea(TupleD[] v) {
        double area = 0.0;
        for (int i = 0; i < v.length; i++) {
            TupleD a = v[i];
            TupleD b = v[(i + 1) % v.length];
            area += a.first * b.second - b.first * a.second;
        }
        return 0.5 * area;
    }

    // -------------------------------------------------------------------------
    private static void reverse(TupleD[] v) {
        for (int i = 0, j = v.length - 1; i < j; i++, j--) {
            TupleD tmp = v[i];
            v[i] = v[j];
            v[j] = tmp;
        }
    }

    private static final Color FILL_COLOR = new Color(160, 32, 240, 255);

    // -------------------------------------------------------------------------
    // fills the interior (opaque purple), then draws every edge via
    // Viewer.drawLine on top, closing the loop back to the first vertex --
    // so callers just call obstacle.display() each frame instead of reaching
    // into vertices themselves.
    public void display() {
        Main.viewer.drawPolygon(vertices, FILL_COLOR);
        for (int i = 0; i < vertices.length; i++) {
            Main.viewer.drawLine(vertices[i], vertices[(i + 1) % vertices.length]);
        }
    }

    // -------------------------------------------------------------------------
    public static class ClosestPoint {
        public final TupleD point;
        public final TupleD normal;

        ClosestPoint(TupleD point, TupleD normal) {
            this.point = point;
            this.normal = normal;
        }
    }
}
