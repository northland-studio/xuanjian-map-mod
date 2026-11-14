package top.xuanjian.map.core.chunk;

import java.util.Arrays;
import java.util.List;

/**
 * 单区块采集样本（契约二进制里一个 chunk 段的内容）。
 *
 * <p>严格按 <b>生产契约</b>（{@code GET /api/gis/client/contract}）：
 * <ul>
 *   <li>{@code sampleStep 1..4}，{@code sampleSize = 16 / sampleStep}；</li>
 *   <li>{@code heightMatrix i16[n*n]}，<b>-32768 表示无数据，必须保留缺口</b>，不得填 0；</li>
 *   <li>{@code biomeMatrix u16[n*n]} 可选，0xFFFF 表示未知；</li>
 *   <li>{@code blockHist} 可选：{@code u8 n} + {@code n*(u16 blockId, u32 count)}（方块类型直方图）。</li>
 * </ul>
 *
 * @param chunkX        区块 X
 * @param chunkZ        区块 Z
 * @param sampleStep    采样步长（1..4）
 * @param sampleSize    每边采样点数（= 16 / sampleStep）
 * @param minY          有效高度最小值（含）
 * @param maxY          有效高度最大值（含）
 * @param heights       高度矩阵，行优先：index = row * sampleSize + col
 * @param biomes        biome 注册表数字 ID 矩阵，-1 表示未知
 * @param blockHistogram 方块类型直方图（按契约 blockId/count），null 表示不上传
 */
public record ChunkData(int chunkX, int chunkZ, int sampleStep, int sampleSize,
                        short minY, short maxY, short[] heights, int[] biomes,
                        List<BlockCount> blockHistogram) {

    public static final short NO_DATA = -32768;
    public static final int UNKNOWN_BIOME = 0xFFFF;
    public static final int MAX_SAMPLE_STEP = 4;
    public static final int MAX_BLOCK_HIST_ENTRIES = 255;

    public static final int FLAG_HEIGHTS = 0x01;
    public static final int FLAG_BIOMES = 0x02;
    public static final int FLAG_BLOCK_HIST = 0x04;

    /** blockHist 条目：方块注册表数字 ID 与出现次数。 */
    public record BlockCount(int blockId, long count) {
    }

    public ChunkData {
        if (sampleStep < 1 || sampleStep > MAX_SAMPLE_STEP) {
            throw new IllegalArgumentException("sampleStep 必须为 1.." + MAX_SAMPLE_STEP + ": " + sampleStep);
        }
        if (sampleSize < 1 || sampleSize > 32) {
            throw new IllegalArgumentException("sampleSize 越界: " + sampleSize);
        }
        if (sampleSize * sampleStep != 16) {
            throw new IllegalArgumentException("sampleStep*sampleSize 必须等于 16（区块宽度）: "
                    + sampleStep + "*" + sampleSize);
        }
        int expected = sampleSize * sampleSize;
        if (heights == null || heights.length != expected) {
            throw new IllegalArgumentException("heights 长度必须为 " + expected);
        }
        if (biomes != null && biomes.length != expected) {
            throw new IllegalArgumentException("biomes 长度必须为 " + expected);
        }
        if (blockHistogram == null) {
            blockHistogram = List.of();
        } else {
            blockHistogram = List.copyOf(blockHistogram.subList(0,
                    Math.min(blockHistogram.size(), MAX_BLOCK_HIST_ENTRIES)));
        }
    }

    public int cells() {
        return sampleSize * sampleSize;
    }

    public boolean hasBiomes() {
        return biomes != null;
    }

    public boolean hasBlockHistogram() {
        return !blockHistogram.isEmpty();
    }

    /** 由实际含数据的矩阵重算 minY/maxY（全缺口时 0/0）。 */
    public static short[] computeMinMax(short[] heights) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (short h : heights) {
            if (h == NO_DATA) {
                continue;
            }
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        if (min == Integer.MAX_VALUE) {
            return new short[]{0, 0};
        }
        return new short[]{(short) min, (short) max};
    }

    /** 是否有缺口（覆盖度/缺数据提示用）。 */
    public boolean hasGaps() {
        for (short h : heights) {
            if (h == NO_DATA) {
                return true;
            }
        }
        return false;
    }

    public int flags() {
        int flags = FLAG_HEIGHTS;
        if (biomes != null) {
            flags |= FLAG_BIOMES;
        }
        if (hasBlockHistogram()) {
            flags |= FLAG_BLOCK_HIST;
        }
        return flags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChunkData other)) {
            return false;
        }
        return chunkX == other.chunkX && chunkZ == other.chunkZ
                && sampleStep == other.sampleStep && sampleSize == other.sampleSize
                && minY == other.minY && maxY == other.maxY
                && Arrays.equals(heights, other.heights)
                && Arrays.equals(biomes, other.biomes)
                && blockHistogram.equals(other.blockHistogram);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(chunkX * 31L + chunkZ);
        result = 31 * result + sampleStep;
        result = 31 * result + sampleSize;
        result = 31 * result + Arrays.hashCode(heights);
        result = 31 * result + Arrays.hashCode(biomes);
        return result;
    }
}
