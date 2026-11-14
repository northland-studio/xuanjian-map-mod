package top.xuanjian.map.core.chunk;

import java.util.Arrays;
import java.util.List;

/**
 * 单区块采集样本（契约二进制里一个 chunk 段的内容）。
 *
 * <p>严格按契约：
 * <ul>
 *   <li>{@code heightMatrix i16[n*n]}，<b>-32768 表示无数据，必须保留缺口</b>，不得填 0；</li>
 *   <li>{@code biomeMatrix u16[n*n]} 可选，0xFFFF 表示未知；</li>
 *   <li>blockHist 可选（契约未定义布局，默认不上传，见 docs/api-contract.md 待确认项）。</li>
 * </ul>
 *
 * @param chunkX      区块 X
 * @param chunkZ      区块 Z
 * @param sampleStep  采样步长（1..32，且 sampleSize*step 必须 = 16）
 * @param minY        有效高度最小值（含）
 * @param maxY        有效高度最大值（含）
 * @param heights     高度矩阵，行优先：index = row * sampleSize + col
 * @param biomes      biome 注册表数字 ID 矩阵，-1 表示未知
 * @param blockPalette block id 调色板（本地采集/可选上传）
 * @param blockIndices 每列的调色板下标（-1 未知），长度 n*n
 */
public record ChunkData(int chunkX, int chunkZ, int sampleStep, int sampleSize,
                        short minY, short maxY, short[] heights, int[] biomes,
                        List<String> blockPalette, short[] blockIndices) {

    public static final short NO_DATA = -32768;
    public static final int UNKNOWN_BIOME = 0xFFFF;

    public static final int FLAG_HEIGHTS = 0x01;
    public static final int FLAG_BIOMES = 0x02;
    public static final int FLAG_BLOCK_HIST = 0x04;

    public ChunkData {
        if (sampleStep < 1 || sampleStep > 32) {
            throw new IllegalArgumentException("sampleStep 越界: " + sampleStep);
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
        if (blockIndices != null && blockIndices.length != expected) {
            throw new IllegalArgumentException("blockIndices 长度必须为 " + expected);
        }
        if (blockPalette == null) {
            blockPalette = List.of();
        } else {
            blockPalette = List.copyOf(blockPalette);
        }
    }

    public int cells() {
        return sampleSize * sampleSize;
    }

    public boolean hasBiomes() {
        return biomes != null;
    }

    public boolean hasBlockIndices() {
        return blockIndices != null && !blockPalette.isEmpty();
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
        if (hasBlockIndices()) {
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
                && blockPalette.equals(other.blockPalette)
                && Arrays.equals(blockIndices, other.blockIndices);
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
