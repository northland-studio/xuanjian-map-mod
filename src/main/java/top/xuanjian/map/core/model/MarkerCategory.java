package top.xuanjian.map.core.model;

import java.util.Locale;

/** Marker 分类（规范第九节）。 */
public enum MarkerCategory {
    PUBLIC_BUILDING("public_building"),
    RESIDENTIAL("residential"),
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
        return OTHER;
    }
}
