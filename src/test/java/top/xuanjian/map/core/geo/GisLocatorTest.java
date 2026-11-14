package top.xuanjian.map.core.geo;

import org.junit.jupiter.api.Test;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.model.Geometry;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GisLocatorTest {

    private static GisObject district(long id, String name, double size) {
        Geometry.GisPolygon poly = Geometry.GisPolygon.ofRing(
                new double[]{-size, size, size, -size},
                new double[]{-size, -size, size, size});
        return new GisObject("d" + id, GisObjectKind.DISTRICT, 1, Dimensions.OVERWORLD,
                name, poly, 1, 0, Map.of(), poly.bbox());
    }

    private static GisObject road(String id, String name, double width) {
        Geometry.GisLine line = Geometry.GisLine.of(new double[]{0, 200}, new double[]{0, 0});
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("width", width);
        return new GisObject(id, GisObjectKind.ROAD, 1, Dimensions.OVERWORLD, name, line,
                1, 0, props, line.bbox());
    }

    private static GisObject marker(String id, String name, double x, double z) {
        Geometry.GisPoint p = new Geometry.GisPoint(x, z);
        return new GisObject(id, GisObjectKind.MARKER, 1, Dimensions.OVERWORLD, name, p,
                1, 0, Map.of(), p.bbox());
    }

    @Test
    void locate_picksSmallestContainingDistrict() {
        GisLocator locator = new GisLocator();
        locator.replaceDimension(Dimensions.OVERWORLD, List.of(
                district(1, "大城区", 1000),
                district(2, "小城区", 100),
                road("r1", "主街", 10),
                marker("m1", "商店", 10, 2)));
        LocationInfo info = locator.locate(Dimensions.OVERWORLD, 5, 4);
        assertEquals("d2", info.districtId());
        assertEquals("小城区", info.districtName());
        assertEquals("r1", info.roadId());
        assertEquals("主街", info.roadName());
        assertEquals(1, info.nearbyMarkers());
    }

    @Test
    void locate_noRoadOutsideBuffer() {
        GisLocator locator = new GisLocator();
        locator.replaceDimension(Dimensions.OVERWORLD, List.of(road("r1", "主街", 4)));
        LocationInfo info = locator.locate(Dimensions.OVERWORLD, 50, 10);
        assertNull(info.roadId());
        assertEquals("无道路", info.roadLabel());
    }

    @Test
    void locate_emptyDimension() {
        GisLocator locator = new GisLocator();
        LocationInfo info = locator.locate(Dimensions.THE_NETHER, 0, 0);
        assertFalse(info.hasDistrict());
        assertEquals(LocationInfo.EMPTY, info);
    }

    @Test
    void objectsInView_filtersByLayer() {
        GisLocator locator = new GisLocator();
        locator.replaceDimension(Dimensions.OVERWORLD, List.of(
                district(1, "城区", 100), road("r1", "路", 6), marker("m1", "点", 1, 1)));
        var all = locator.objectsInView(Dimensions.OVERWORLD,
                new top.xuanjian.map.core.model.BBox(-200, -200, 200, 200),
                GisLocator.FeatureFilter.ALL);
        assertEquals(3, all.size());
        var onlyRoads = locator.objectsInView(Dimensions.OVERWORLD,
                new top.xuanjian.map.core.model.BBox(-200, -200, 200, 200),
                new GisLocator.FeatureFilter(false, true, false, false));
        assertEquals(1, onlyRoads.size());
        var none = locator.objectsInView(Dimensions.OVERWORLD,
                new top.xuanjian.map.core.model.BBox(-200, -200, 200, 200),
                new GisLocator.FeatureFilter(false, false, false, false));
        assertTrue(none.isEmpty());
    }

    @Test
    void nearbyMarkers_sortedByDistance() {
        GisLocator locator = new GisLocator();
        locator.replaceDimension(Dimensions.OVERWORLD, List.of(
                marker("m1", "远", 50, 0), marker("m2", "近", 5, 0), marker("m3", "中", 20, 0)));
        var nearby = locator.nearbyMarkers(Dimensions.OVERWORLD, 0, 0, 100, 2);
        assertEquals(2, nearby.size());
        assertEquals("m2", nearby.get(0).id());
        assertEquals("m3", nearby.get(1).id());
    }

    @Test
    void indexScales_reasonableQuery() {
        GisLocator locator = new GisLocator();
        java.util.List<GisObject> many = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            many.add(marker("m" + i, "点" + i, (i % 100) * 16, (i / 100) * 16));
        }
        locator.replaceDimension(Dimensions.OVERWORLD, many);
        assertEquals(5000, locator.objectCount(Dimensions.OVERWORLD));
        long start = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < 200; i++) {
            if (locator.locate(Dimensions.OVERWORLD, i * 4, i * 4).nearbyMarkers() > 0) {
                hits++;
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(hits > 0);
        // 空间索引保证 200 次定位远快于全量扫描（给足余量，避免 CI 抖动）
        assertTrue(elapsedMs < 2000, "定位过慢: " + elapsedMs + "ms");
    }
}
