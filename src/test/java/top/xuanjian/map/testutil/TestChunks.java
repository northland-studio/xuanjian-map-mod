package top.xuanjian.map.testutil;

import top.xuanjian.map.core.chunk.ChunkData;

import java.util.ArrayList;
import java.util.List;

/** 测试用区块样本构造。 */
public final class TestChunks {

    private TestChunks() {
    }

    public static ChunkData sample(int chunkX, int chunkZ, boolean withBiomes, boolean withGaps) {
        return build(chunkX, chunkZ, withBiomes, withGaps, false);
    }

    /** 带 blockHist 的样本（验证生产契约 u8 n + n*(u16 blockId,u32 count) 布局）。 */
    public static ChunkData sampleWithHist(int chunkX, int chunkZ) {
        return build(chunkX, chunkZ, true, false, true);
    }

    private static ChunkData build(int chunkX, int chunkZ, boolean withBiomes,
                                   boolean withGaps, boolean withHist) {
        int n = 16;
        short[] heights = new short[n * n];
        int[] biomes = withBiomes ? new int[n * n] : null;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                heights[r * n + c] = (short) (64 + r / 2 + c % 3);
                if (biomes != null) {
                    biomes[r * n + c] = c % 3;
                }
            }
        }
        if (withGaps) {
            heights[0] = ChunkData.NO_DATA;
            heights[10] = ChunkData.NO_DATA;
            if (biomes != null) {
                biomes[10] = -1;
            }
        }
        List<ChunkData.BlockCount> histogram = List.of();
        if (withHist) {
            List<ChunkData.BlockCount> list = new ArrayList<>();
            list.add(new ChunkData.BlockCount(1, 120));
            list.add(new ChunkData.BlockCount(9, 80));
            list.add(new ChunkData.BlockCount(255, 56));
            histogram = list;
        }
        short[] minMax = ChunkData.computeMinMax(heights);
        return new ChunkData(chunkX, chunkZ, 1, n, minMax[0], minMax[1], heights, biomes,
                histogram);
    }
}
