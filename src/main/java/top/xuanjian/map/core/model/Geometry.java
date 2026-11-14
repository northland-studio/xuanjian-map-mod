package top.xuanjian.map.core.model;

import top.xuanjian.map.core.geo.GeoMath;

import java.util.List;

/**
 * GIS 几何（2D，X/Z 平面）：Point / LineString / Polygon，对应规范第九节。
 *
 * <p>用 sealed + record 表示，便于模式匹配与不可变共享；Y 不在几何中，作为对象属性单独存。
 */
public sealed interface Geometry permits Geometry.GisPoint, Geometry.GisLine, Geometry.GisPolygon {

    BBox bbox();

    /** GeoJSON 风格的 geometry type。 */
    String type();

    Point asPoint();

    /** Point（Marker 用）。 */
    record GisPoint(double x, double z) implements Geometry {
        @Override
        public BBox bbox() {
            return BBox.point(x, z);
        }

        @Override
        public String type() {
            return "Point";
        }

        @Override
        public Point asPoint() {
            return new Point(x, z);
        }
    }

    /** LineString（Road 中心线用），points[i] = {x, z}。 */
    record GisLine(double[][] points) implements Geometry {
        public GisLine {
            if (points == null || points.length < 2) {
                throw new IllegalArgumentException("LineString 至少需要 2 个点");
            }
        }

        public static GisLine of(double[] xs, double[] zs) {
            if (xs.length != zs.length) {
                throw new IllegalArgumentException("x/z 数量不一致");
            }
            double[][] pts = new double[xs.length][2];
            for (int i = 0; i < xs.length; i++) {
                pts[i][0] = xs[i];
                pts[i][1] = zs[i];
            }
            return new GisLine(pts);
        }

        @Override
        public BBox bbox() {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (double[] p : points) {
                minX = Math.min(minX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxZ = Math.max(maxZ, p[1]);
            }
            return new BBox(minX, minZ, maxX, maxZ);
        }

        @Override
        public String type() {
            return "LineString";
        }

        @Override
        public Point asPoint() {
            return new Point(points[0][0], points[0][1]);
        }
    }

    /** Polygon（城区边界用），rings.get(0) 为外环，其余为洞。 */
    record GisPolygon(List<Ring> rings) implements Geometry {
        public GisPolygon {
            if (rings == null || rings.isEmpty()) {
                throw new IllegalArgumentException("Polygon 至少需要一个环");
            }
        }

        public static GisPolygon ofRing(double[] xs, double[] zs) {
            return new GisPolygon(List.of(new Ring(xs, zs)));
        }

        public Ring outer() {
            return rings.get(0);
        }

        public boolean contains(double x, double z) {
            if (!outer().contains(x, z)) {
                return false;
            }
            for (int i = 1; i < rings.size(); i++) {
                if (rings.get(i).contains(x, z)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public BBox bbox() {
            BBox b = outer().bbox();
            for (int i = 1; i < rings.size(); i++) {
                b = b.union(rings.get(i).bbox());
            }
            return b;
        }

        @Override
        public String type() {
            return "Polygon";
        }

        @Override
        public Point asPoint() {
            return new Point(outer().xs()[0], outer().zs()[0]);
        }
    }

    /** 环：首尾可闭合也可不闭合（按隐式闭合处理）。 */
    record Ring(double[] xs, double[] zs) {
        public Ring {
            if (xs == null || zs == null || xs.length != zs.length || xs.length < 3) {
                throw new IllegalArgumentException("环至少需要 3 个坐标点");
            }
        }

        public boolean contains(double x, double z) {
            return GeoMath.pointInPolygon(x, z, xs, zs);
        }

        public BBox bbox() {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                minX = Math.min(minX, xs[i]);
                minZ = Math.min(minZ, zs[i]);
                maxX = Math.max(maxX, xs[i]);
                maxZ = Math.max(maxZ, zs[i]);
            }
            return new BBox(minX, minZ, maxX, maxZ);
        }
    }

    /** 简单点载体（非几何子类型，避免与 record 冲突）。 */
    record Point(double x, double z) {
    }
}
