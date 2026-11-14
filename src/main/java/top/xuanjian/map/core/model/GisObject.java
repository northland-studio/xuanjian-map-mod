package top.xuanjian.map.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个已发布的 GIS 对象（只来自服务端 Published Revision，客户端不自造对象）。
 *
 * @param id        对象 ID（服务端生成）
 * @param kind      district / road / marker / area / other
 * @param worldId   所属世界数字 ID
 * @param dimension 所属 Dimension（归一化后的完整 id）
 * @param name      显示名称
 * @param geometry  2D 几何
 * @param revision  版本号（只同步 Published）
 * @param updatedAt 服务端更新时间（毫秒）
 * @param props     其他属性（道路宽度/等级/分类/wikiPageId/颜色等）
 * @param bbox      几何包围盒（构造时算好，供空间索引用）
 */
public record GisObject(String id, GisObjectKind kind, long worldId, String dimension, String name,
                        Geometry geometry, int revision, long updatedAt,
                        Map<String, Object> props, BBox bbox) {

    public GisObject {
        props = props == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(props));
        if (bbox == null) {
            bbox = geometry == null ? BBox.point(0, 0) : geometry.bbox();
        }
    }

    public String propString(String key, String def) {
        Object v = props.get(key);
        if (v == null) {
            return def;
        }
        return String.valueOf(v);
    }

    public double propDouble(String key, double def) {
        Object v = props.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public int propInt(String key, int def) {
        Object v = props.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** 道路宽度（方块），缺省 8；道路判定必须用宽度缓冲区而不是中心点半径。 */
    public double roadWidth() {
        return Math.max(1.0, propDouble("width", 8.0));
    }

    /** 父级城区 ID（城区层级：城区 → 道路）。 */
    public String parentId() {
        return propString("parentId", null);
    }

    public MarkerCategory markerCategory() {
        return MarkerCategory.fromWire(propString("category", "other"));
    }
}
