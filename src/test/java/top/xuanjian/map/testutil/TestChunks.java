package top.xuanjian.map.testutil;

import top.xuanjian.map.core.chunk.BlockHash;
import top.xuanjian.map.core.chunk.ChunkData;

import java.util.ArrayList;
import java.util.List;

/** 测试用区块样本构造。 */
public final class TestChunks {

    private TestChunks() {
    }

    public static ChunkData sample(int chunkX, int chunkZ, boolean withBiomes, boolean withGaps) {
        return build(chunkX, chunkZ, withBiomes, withGaps, false, false);
    }

    /** 带 blockHist 的样本（验证生产契约 u8 n + n*(u16 blockId,u32 count) 布局）。 */
    public static ChunkData sampleWithHist(int chunkX, int chunkZ) {
        return build(chunkX, chunkZ, true, false, true, false);
    }

    /** 带 v2 surfaceBlockMatrix 的样本（每列地表方块 hash16）。 */
    public static ChunkData sampleWithSurface(int chunkX, int chunkZ) {
        return build(chunkX, chunkZ, true, false, true, true);
    }

    private static ChunkData build(int chunkX, int chunkZ, boolean withBiomes,
                                   boolean withGaps, boolean withHist, boolean withSurface) {
        int n = 16;
        short[] heights = new short[n * n];
        int[] biomes = withBiomes ? new int[n * n] : null;
        int[] surface = withSurface ? new int[n * n] : null;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int idx = r * n + c;
                heights[idx] = (short) (64 + r / 2 + c % 3);
                if (biomes != null) {
                    biomes[idx] = c % 3;
                }
                if (surface != null) {
                    // 交替草方块/水/石头，并且留一个 0xFFFF 无数据格用于校验
                    int kind = (r + c) % 3;
                    surface[idx] = switch (kind) {
                        case 0 -> BlockHash.hash16("minecraft:grass_block");
                        case 1 -> BlockHash.hash16("minecraft:water");
                        default -> BlockHash.hash16("minecraft:stone");
                    };
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
        if (surface != null) {
            surface[0] = BlockHash.NO_SURFACE;
            surface[255] = BlockHash.NO_SURFACE;
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
                histogram, surface);
    }
}
