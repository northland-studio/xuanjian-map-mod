package top.xuanjian.map.core.model;

import java.util.Locale;

/** 世界状态（契约 bootstrap.worlds[].status）。 */
public enum WorldStatus {
    ACTIVE,
    ARCHIVED,
    SNAPSHOT;

    public static WorldStatus fromWire(String wire) {
        if (wire == null) {
            return ACTIVE;
        }
        String w = wire.trim().toUpperCase(Locale.ROOT);
        for (WorldStatus s : values()) {
            if (s.name().equals(w)) {
                return s;
            }
        }
        return ACTIVE;
    }

    public String labelZh() {
        return switch (this) {
            case ACTIVE -> "当前世界";
            case ARCHIVED -> "已归档";
            case SNAPSHOT -> "快照";
        };
    }
}
