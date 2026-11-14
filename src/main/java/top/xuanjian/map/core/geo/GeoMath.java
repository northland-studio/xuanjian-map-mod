package top.xuanjian.map.core.geo;

/** 纯几何计算（不依赖 Minecraft）：点在多边形内、点到线段/折线距离、缓冲区判定。 */
public final class GeoMath {

    private GeoMath() {
    }

    /**
     * 射线法（crossing number）判断点是否在环内。
     *
     * <p>注意：这是 HUD 城区判定的核心，必须用真实多边形边界，不能用“中心点半径”近似。
     */
    public static boolean pointInPolygon(double x, double z, double[] xs, double[] zs) {
        boolean inside = false;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = xs[i];
            double zi = zs[i];
            double xj = xs[j];
            double zj = zs[j];
            boolean intersects = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 点到线段的距离（平方根形式）。 */
    public static double distanceToSegment(double px, double pz,
                                           double ax, double az,
                                           double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 <= 1.0e-12) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / len2;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        double cx = ax + t * dx;
        double cz = az + t * dz;
        return Math.hypot(px - cx, pz - cz);
    }

    /**
     * 点到折线的最近距离。
     *
     * <p>道路判定按规范要求用「道路几何 + 宽度缓冲区」，即本距离 <= 半宽才认为在道路上。
     */
    public static double distanceToLineString(double px, double pz, double[] xs, double[] zs) {
        if (xs.length == 1) {
            return Math.hypot(px - xs[0], pz - zs[0]);
        }
        double best = Double.MAX_VALUE;
        for (int i = 1; i < xs.length; i++) {
            double d = distanceToSegment(px, pz, xs[i - 1], zs[i - 1], xs[i], zs[i]);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    /** 道路宽度缓冲区判定：dist <= max(宽度/2, 最小半宽)。 */
    public static boolean inRoadBuffer(double px, double pz, double[] xs, double[] zs,
                                       double roadWidth, double minHalfWidth) {
        double half = Math.max(roadWidth / 2.0, minHalfWidth);
        return distanceToLineString(px, pz, xs, zs) <= half;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
