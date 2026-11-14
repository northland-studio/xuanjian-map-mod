package top.xuanjian.map.core.geo;

import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;
import top.xuanjian.map.core.model.Geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 GIS 定位器：由本地缓存（SQLite → 内存）构建空间索引，供 HUD / 地图叠加使用。
 *
 * <p>规范要求：
 * <ul>
 *   <li>城区判定用 Point-in-Polygon（真实多边形，不是中心点半径）；</li>
 *   <li>道路判定用道路几何 + 宽度缓冲区；</li>
 *   <li>不得每帧全量计算 → 这里全部走空间索引取候选后精确判定；</li>
 *   <li>不上传玩家实时位置，本类只在客户端内存中工作。</li>
 * </ul>
 *
 * <p>线程模型：{@link #replaceDimension} 可在后台线程构建完整索引后原子替换；
 * {@link #locate} 在渲染线程读 volatile 快照。
 */
public final class GisLocator {

    private static final double CELL = 256.0;
    private static final double ROAD_QUERY_RADIUS = 64.0;

    private volatile Map<String, DimIndex> index = Map.of();

    /** 用某 Dimension 的全量对象替换索引（后台线程调用，完成后原子可见）。 */
    public synchronized void replaceDimension(String dimension, List<GisObject> objects) {
        String dim = Dimensions.normalize(dimension);
        Map<String, DimIndex> next = new LinkedHashMap<>(index);
        next.put(dim, DimIndex.build(dim, objects));
        index = Map.copyOf(next);
    }

    public synchronized void clearAll() {
        index = Map.of();
    }

    public synchronized void clearDimension(String dimension) {
        Map<String, DimIndex> next = new LinkedHashMap<>(index);
        next.remove(Dimensions.normalize(dimension));
        index = Map.copyOf(next);
    }

    public int objectCount() {
        int total = 0;
        for (DimIndex d : index.values()) {
            total += d.total;
        }
        return total;
    }

    public int objectCount(String dimension) {
        DimIndex d = index.get(Dimensions.normalize(dimension));
        return d == null ? 0 : d.total;
    }

    /** 各图层对象数量：{districts, roads, markers, others}（图层管理页展示）。 */
    public int[] layerCounts(String dimension) {
        DimIndex d = index.get(Dimensions.normalize(dimension));
        if (d == null) {
            return new int[]{0, 0, 0, 0};
        }
        return new int[]{d.districts.size(), d.roads.size(), d.markers.size(), d.others.size()};
    }

    /** 定位玩家所在城区与道路（HUD 核心）。 */
    public LocationInfo locate(String dimension, double x, double z) {
        DimIndex idx = index.get(Dimensions.normalize(dimension));
        if (idx == null) {
            return LocationInfo.EMPTY;
        }
        GisObject district = null;
        for (GisObject candidate : idx.districts.queryRadius(x, z, 0.0)) {
            if (candidate.geometry() instanceof Geometry.GisPolygon poly && poly.contains(x, z)) {
                if (district == null || poly.outer().bbox().area() < areaOf(district)) {
                    district = candidate;
                }
            }
        }

        GisObject road = null;
        double bestDistance = Double.MAX_VALUE;
        for (GisObject candidate : idx.roads.queryRadius(x, z, ROAD_QUERY_RADIUS)) {
            if (candidate.geometry() instanceof Geometry.GisLine line) {
                double half = Math.max(candidate.roadWidth() / 2.0, 1.0);
                if (half > ROAD_QUERY_RADIUS) {
                    continue;
                }
                double d = distanceToLine(line, x, z);
                if (d <= half && d < bestDistance) {
                    bestDistance = d;
                    road = candidate;
                }
            }
        }

        int nearby = nearbyMarkers(Dimensions.normalize(dimension), x, z, 64.0, 64).size();
        return new LocationInfo(
                district == null ? null : district.id(),
                district == null ? null : district.name(),
                road == null ? null : road.id(),
                road == null ? null : road.name(),
                nearby);
    }

    /** 视口内对象（地图叠加渲染用，按图层类型过滤）。 */
    public List<GisObject> objectsInView(String dimension, BBox view, FeatureFilter filter) {
        DimIndex idx = index.get(Dimensions.normalize(dimension));
        if (idx == null) {
            return List.of();
        }
        List<GisObject> out = new ArrayList<>();
        if (filter.districts()) {
            out.addAll(idx.districts.query(view));
        }
        if (filter.roads()) {
            out.addAll(idx.roads.query(view));
        }
        if (filter.markers()) {
            out.addAll(idx.markers.query(view));
        }
        if (filter.others()) {
            out.addAll(idx.others.query(view));
        }
        return out;
    }

    /** 附近 Marker（HUD 折叠态 / 小地图附近对象）。 */
    public List<GisObject> nearbyMarkers(String dimension, double x, double z, double radius, int limit) {
        DimIndex idx = index.get(Dimensions.normalize(dimension));
        if (idx == null) {
            return List.of();
        }
        List<GisObject> candidates = new ArrayList<>(idx.markers.queryRadius(x, z, radius));
        candidates.sort(Comparator.comparingDouble(o -> distanceSq(o, x, z)));
        if (candidates.size() > limit) {
            return candidates.subList(0, limit);
        }
        return candidates;
    }

    /** 图层过滤（自动叠加层 / HUD 小地图）。 */
    public record FeatureFilter(boolean districts, boolean roads, boolean markers, boolean others) {
        public static final FeatureFilter ALL = new FeatureFilter(true, true, true, true);
    }

    private static double areaOf(GisObject object) {
        return object.geometry() == null ? Double.MAX_VALUE : object.geometry().bbox().area();
    }

    private static double distanceSq(GisObject object, double x, double z) {
        Geometry.Point p = object.geometry() == null ? null : object.geometry().asPoint();
        if (p == null) {
            return Double.MAX_VALUE;
        }
        double dx = p.x() - x;
        double dz = p.z() - z;
        return dx * dx + dz * dz;
    }

    private static double distanceToLine(Geometry.GisLine line, double x, double z) {
        double[][] pts = line.points();
        double best = Double.MAX_VALUE;
        for (int i = 1; i < pts.length; i++) {
            double d = top.xuanjian.map.core.geo.GeoMath.distanceToSegment(
                    x, z, pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private static final class DimIndex {
        final SpatialIndex<GisObject> districts = new SpatialIndex<>(CELL);
        final SpatialIndex<GisObject> roads = new SpatialIndex<>(CELL);
        final SpatialIndex<GisObject> markers = new SpatialIndex<>(CELL);
        final SpatialIndex<GisObject> others = new SpatialIndex<>(CELL);
        int total;

        static DimIndex build(String dimension, List<GisObject> objects) {
            DimIndex idx = new DimIndex();
            if (objects == null) {
                return idx;
            }
            for (GisObject o : objects) {
                if (!Dimensions.normalize(o.dimension()).equals(dimension)) {
                    continue;
                }
                switch (o.kind()) {
                    case DISTRICT -> idx.districts.insert(o.bbox(), o);
                    case ROAD -> idx.roads.insert(o.bbox(), o);
                    case MARKER -> idx.markers.insert(o.bbox(), o);
                    default -> idx.others.insert(o.bbox(), o);
                }
                idx.total++;
            }
            return idx;
        }
    }
}
