package top.xuanjian.map.core.dimension;

import java.util.Locale;

/**
 * Dimension 归一化与上传 code 映射。
 *
 * <p>生产契约 {@code GET /api/gis/client/contract} 明确 dimensions：
 * overworld=0, nether=1, end=2, custom=3。
 */
public final class Dimensions {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String THE_NETHER = "minecraft:the_nether";
    public static final String THE_END = "minecraft:the_end";
    public static final String UNKNOWN = "minecraft:unknown";

    private Dimensions() {
    }

    /** 接受 "overworld" / "nether" / "the_nether" / 完整 id，归一为 "minecraft:xxx"。 */
    public static String normalize(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return OVERWORLD;
        }
        String d = dimension.trim().toLowerCase(Locale.ROOT);
        if (d.contains(":")) {
            return d;
        }
        return switch (d) {
            case "overworld", "world", "主世界" -> OVERWORLD;
            case "nether", "the_nether", "下界" -> THE_NETHER;
            case "end", "the_end", "末地" -> THE_END;
            default -> "minecraft:" + d;
        };
    }

    public static int code(String dimension) {
        String d = normalize(dimension);
        return switch (d) {
            case OVERWORLD -> 0;
            case THE_NETHER -> 1;
            case THE_END -> 2;
            default -> 3; // 契约：custom = 3
        };
    }

    public static String fromCode(int code) {
        return switch (code & 0xFF) {
            case 0 -> OVERWORLD;
            case 1 -> THE_NETHER;
            case 2 -> THE_END;
            default -> UNKNOWN;
        };
    }

    public static String labelZh(String dimension) {
        String d = normalize(dimension);
        return switch (d) {
            case OVERWORLD -> "主世界";
            case THE_NETHER -> "下界";
            case THE_END -> "末地";
            default -> d;
        };
    }

    public static String shortName(String dimension) {
        String d = normalize(dimension);
        int idx = d.indexOf(':');
        return idx >= 0 ? d.substring(idx + 1) : d;
    }
}
