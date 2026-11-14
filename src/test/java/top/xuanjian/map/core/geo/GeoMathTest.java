package top.xuanjian.map.core.geo;

import org.junit.jupiter.api.Test;
import top.xuanjian.map.core.model.Geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoMathTest {

    /** 正方形城区：(-100,-100) ~ (100,100)。 */
    private static final double[] XS = {-100, 100, 100, -100};
    private static final double[] ZS = {-100, -100, 100, 100};

    @Test
    void pointInPolygon_basic() {
        assertTrue(GeoMath.pointInPolygon(0, 0, XS, ZS));
        assertTrue(GeoMath.pointInPolygon(99, 99, XS, ZS));
        assertFalse(GeoMath.pointInPolygon(101, 0, XS, ZS));
        assertFalse(GeoMath.pointInPolygon(0, -101, XS, ZS));
        assertFalse(GeoMath.pointInPolygon(-500, -500, XS, ZS));
    }

    @Test
    void pointInPolygon_concave() {
        // U 形凹多边形：中间凹口不属于内部
        double[] xs = {0, 30, 30, 20, 20, 10, 10, 0};
        double[] zs = {0, 0, 30, 30, 10, 10, 30, 30};
        assertTrue(GeoMath.pointInPolygon(5, 5, xs, zs));
        assertTrue(GeoMath.pointInPolygon(25, 5, xs, zs));
        assertFalse(GeoMath.pointInPolygon(15, 25, xs, zs));
    }

    @Test
    void distanceToSegment_edgesAndMiddle() {
        assertEquals(0.0, GeoMath.distanceToSegment(5, 0, 0, 0, 10, 0), 1e-9);
        assertEquals(3.0, GeoMath.distanceToSegment(5, 3, 0, 0, 10, 0), 1e-9);
        assertEquals(5.0, GeoMath.distanceToSegment(-5, 0, 0, 0, 10, 0), 1e-9);
        // 端点外侧取端点距离
        assertEquals(Math.hypot(5, 4), GeoMath.distanceToSegment(15, 4, 0, 0, 10, 0), 1e-9);
    }

    @Test
    void lineStringAndRoadBuffer() {
        double[] xs = {0, 100};
        double[] zs = {0, 0};
        assertEquals(5.0, GeoMath.distanceToLineString(50, 5, xs, zs), 1e-9);
        // 道路宽 8 → 半宽 4，5 格距离不在缓冲区内
        assertFalse(GeoMath.inRoadBuffer(50, 5, xs, zs, 8, 0));
        assertTrue(GeoMath.inRoadBuffer(50, 3.9, xs, zs, 8, 0));
        // 最小半宽兜底
        assertTrue(GeoMath.inRoadBuffer(50, 1.5, xs, zs, 1, 2));
    }

    @Test
    void polygonContains_usesRings() {
        Geometry.GisPolygon polygon = Geometry.GisPolygon.ofRing(XS, ZS);
        assertTrue(polygon.contains(0, 0));
        assertFalse(polygon.contains(200, 0));
        // 对角线点
        assertTrue(polygon.contains(99.5, -99.5));
    }
}
