package top.xuanjian.map.core.model;

import java.util.Locale;

/** GIS 对象类型（契约 types 参数使用小写 wire 名）。 */
public enum GisObjectKind {
    DISTRICT("district"),
    ROAD("road"),
    MARKER("marker"),
    AREA("area"),
    OTHER("other");

    private final String wire;

    GisObjectKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static GisObjectKind fromWire(String wire) {
        if (wire == null) {
            return OTHER;
        }
        String w = wire.trim().toLowerCase(Locale.ROOT);
        for (GisObjectKind k : values()) {
            if (k.wire.equals(w)) {
                return k;
            }
        }
        // 服务端可能的别名
        return switch (w) {
            case "districts" -> DISTRICT;
            case "roads" -> ROAD;
            case "markers" -> MARKER;
            case "areas", "region", "regions" -> AREA;
            default -> OTHER;
        };
    }
}
