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

    /**
     * 从任意 toString 中提取 {@code namespace:path}。
     *
     * <p>Minecraft 的 ResourceKey.toString() 形如
     * {@code ResourceKey[minecraft:dimension / minecraft:overworld]}，
     * 不同版本还可能给成 ResourceLocation 直接字符串；这里统一清洗，避免把
     * "minecraft:dimension"（注册表名）误当成维度。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return OVERWORLD;
        }
        String text = raw.trim();
        int slash = text.lastIndexOf(" / ");
        if (slash >= 0) {
            text = text.substring(slash + 3);
        }
        int bracket = text.indexOf(']');
        if (bracket >= 0) {
            text = text.substring(0, bracket);
        }
        text = text.trim();
        if (text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            return text;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)").matcher(text);
        return matcher.find() ? matcher.group(1) : text;
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
