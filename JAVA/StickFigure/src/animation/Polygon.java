package animation;

import utils.TupleD;

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
    public static class ClosestPoint {
        public final TupleD point;
        public final TupleD normal;

        ClosestPoint(TupleD point, TupleD normal) {
            this.point = point;
            this.normal = normal;
        }
    }
}
