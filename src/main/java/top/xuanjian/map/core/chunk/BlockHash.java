package top.xuanjian.map.core.chunk;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 地表方块 hash16（契约 v2 的 surfaceBlockMatrix 用）。
 *
 * <p>算法与站点 palette 一致：{@code CRC32(方块资源名) & 0xFFFF}，
 * 例如 {@code CRC32("minecraft:grass_block") & 0xFFFF == 0xE06D (57453)}。
 * 纯 JDK 实现，两条分支（26 / 1.21）与 Java 版本无关，保证同一方块 hash 稳定。
 *
 * <p>{@link #NO_SURFACE} (0xFFFF) 表示无数据/空列，必须保留，不能填 0。
 */
public final class BlockHash {

    /** 无数据/空列。 */
    public static final int NO_SURFACE = 0xFFFF;

    private BlockHash() {
    }

    /** 方块资源名（如 minecraft:grass_block）→ 16 位 hash。 */
    public static int hash16(String blockResourceName) {
        if (blockResourceName == null || blockResourceName.isBlank()) {
            return NO_SURFACE;
        }
        CRC32 crc = new CRC32();
        crc.update(blockResourceName.trim().getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() & 0xFFFFL);
    }

    public static boolean isNoSurface(int value) {
        return (value & 0xFFFF) == NO_SURFACE;
    }
}
