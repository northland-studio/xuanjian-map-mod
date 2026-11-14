package top.xuanjian.map.core.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBodyCodecTest {

    private static ChunkData sample(boolean withSurface) {
        int n = 16;
        short[] heights = new short[n * n];
        int[] biomes = new int[n * n];
        int[] surface = withSurface ? new int[n * n] : null;
        for (int i = 0; i < n * n; i++) {
            heights[i] = (short) (60 + i % 11);
            biomes[i] = i % 4;
            if (surface != null) {
                surface[i] = BlockHash.hash16(i % 2 == 0 ? "minecraft:grass_block" : "minecraft:water");
            }
        }
        if (surface != null) {
            surface[5] = BlockHash.NO_SURFACE;
        }
        short[] minMax = ChunkData.computeMinMax(heights);
        return new ChunkData(1, 2, 1, n, minMax[0], minMax[1], heights, biomes,
                java.util.List.of(), surface);
    }

    @Test
    void v2BodyRoundtrip() {
        ChunkData data = sample(true);
        byte[] body = ChunkBodyCodec.encodeBody(data);
        ChunkData decoded = ChunkBodyCodec.decodeBody(body);
        assertEquals(data, decoded);
        assertArrayEquals(data.surfaceBlocks(), decoded.surfaceBlocks());
        assertEquals(BlockHash.NO_SURFACE, decoded.surfaceBlocks()[5]);
        assertTrue(decoded.hasSurfaceBlocks());
        assertEquals(ChunkData.FLAG_SURFACE_BLOCKS, decoded.flags() & ChunkData.FLAG_SURFACE_BLOCKS);
        assertEquals(body.length, ChunkBodyCodec.bodySize(data));
    }

    @Test
    void legacyBodyWithoutSurfaceRoundtrip() {
        ChunkData data = sample(false);
        byte[] body = ChunkBodyCodec.encodeBody(data);
        ChunkData decoded = ChunkBodyCodec.decodeBody(body);
        assertFalse(decoded.hasSurfaceBlocks());
        assertEquals(0, decoded.flags() & ChunkData.FLAG_SURFACE_BLOCKS);
        assertEquals(body.length, ChunkBodyCodec.bodySize(data));
    }
}
