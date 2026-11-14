package top.xuanjian.map.core.model;

import java.util.Locale;

/** Marker 分类（生产契约 markerCategories 原样：housing 等）。 */
public enum MarkerCategory {
    PUBLIC_BUILDING("public_building"),
    HOUSING("housing"),
    SHOP("shop"),
    INDUSTRY("industry"),
    AGRICULTURE("agriculture"),
    TRANSPORT("transport"),
    ENTERTAINMENT("entertainment"),
    GOVERNMENT("government"),
    OTHER("other");

    private final String wire;

    MarkerCategory(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static MarkerCategory fromWire(String wire) {
        if (wire == null) {
            return OTHER;
        }
        String w = wire.trim().toLowerCase(Locale.ROOT);
        for (MarkerCategory c : values()) {
            if (c.wire.equals(w)) {
                return c;
            }
        }
        return switch (w) {
            case "residential", "house", "home" -> HOUSING;
            case "public", "public_building" -> PUBLIC_BUILDING;
            case "gov", "government_org" -> GOVERNMENT;
            default -> OTHER;
        };
    }

    public String labelZh() {
        return switch (this) {
            case PUBLIC_BUILDING -> "公共建筑";
            case HOUSING -> "住宅";
            case SHOP -> "商铺";
            case INDUSTRY -> "工业";
            case AGRICULTURE -> "农业";
            case TRANSPORT -> "交通";
            case ENTERTAINMENT -> "娱乐";
            case GOVERNMENT -> "政府/组织";
            case OTHER -> "其他";
        };
    }
}
