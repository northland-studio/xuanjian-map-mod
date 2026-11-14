package top.xuanjian.map.core.geo;

import top.xuanjian.map.core.model.BBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 均匀网格空间索引（无第三方依赖、线程不安全、构建后只读使用）。
 *
 * <p>HUD 每帧都可能查询「所在城区/道路」，规范要求不得每帧做全量几何计算，
 * 因此 GIS 对象加载后先建索引：先在少量网格单元里取候选，再做精确几何判定。
 */
public final class SpatialIndex<T> {

    private final double cellSize;
    private final Map<Long, List<T>> cells = new LinkedHashMap<>();
    private int size;

    public SpatialIndex(double cellSize) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize 必须为正: " + cellSize);
        }
        this.cellSize = cellSize;
    }

    public double cellSize() {
        return cellSize;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        cells.clear();
        size = 0;
    }

    public void insert(BBox bbox, T value) {
        if (bbox == null || value == null) {
            return;
        }
        int minCx = cellX(bbox.minX());
        int maxCx = cellX(bbox.maxX());
        int minCz = cellZ(bbox.minZ());
        int maxCz = cellZ(bbox.maxZ());
        // 超大几何（例如跨很多格）允许退化：把上限收紧，避免单对象占满 map
        if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) > 4096L) {
            maxCx = minCx;
            maxCz = minCz;
        }
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                cells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(value);
            }
        }
        size++;
    }

    /** 查询与 bbox 相交的候选对象（去重，不保证顺序）。 */
    public List<T> query(BBox bbox) {
        if (bbox == null || cells.isEmpty()) {
            return Collections.emptyList();
        }
        Set<T> found = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> out = new ArrayList<>();
        int minCx = cellX(bbox.minX());
        int maxCx = cellX(bbox.maxX());
        int minCz = cellZ(bbox.minZ());
        int maxCz = cellZ(bbox.maxZ());
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                List<T> bucket = cells.get(key(cx, cz));
                if (bucket == null) {
                    continue;
                }
                for (T item : bucket) {
                    if (found.add(item)) {
                        out.add(item);
                    }
                }
            }
        }
        return out;
    }

    /** 半径查询（圆形 bbox 近似，返回后再由调用方精确过滤）。 */
    public List<T> queryRadius(double x, double z, double radius) {
        return query(new BBox(x - radius, z - radius, x + radius, z + radius));
    }

    public boolean containsCell(int cellX, int cellZ) {
        return cells.containsKey(key(cellX, cellZ));
    }

    private int cellX(double x) {
        return (int) Math.floor(x / cellSize);
    }

    private int cellZ(double z) {
        return (int) Math.floor(z / cellSize);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
