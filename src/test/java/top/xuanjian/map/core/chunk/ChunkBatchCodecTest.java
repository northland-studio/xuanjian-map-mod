package top.xuanjian.map.core.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBatchCodecTest {

    static ChunkData sample(int chunkX, int chunkZ, int step, boolean withBiomes, boolean withGaps) {
        int n = 16 / step;
        short[] heights = new short[n * n];
        int[] biomes = withBiomes ? new int[n * n] : null;
        for (int i = 0; i < n * n; i++) {
            heights[i] = (short) (60 + (i % 7));
            if (withBiomes) {
                biomes[i] = i % 3;
            }
        }
        if (withGaps) {
            heights[0] = ChunkData.NO_DATA;
            heights[n * n - 1] = ChunkData.NO_DATA;
            if (biomes != null) {
                biomes[n * n - 1] = -1;
            }
        }
        short[] minMax = ChunkData.computeMinMax(heights);
        return new ChunkData(chunkX, chunkZ, step, n, minMax[0], minMax[1], heights, biomes,
                java.util.List.of());
    }

    @Test
    void roundtrip_singleChunk_preservesNoDataGaps() {
        ChunkData data = sample(3, -7, 1, true, true);
        byte[] payload = ChunkBatchCodec.encode(42, "minecraft:overworld", 7, List.of(data));
        DecodedBatch decoded = ChunkBatchCodec.decode(payload);

        assertEquals(ChunkBatchCodec.VERSION, decoded.version());
        assertEquals("minecraft:overworld", decoded.dimension());
        assertEquals(42, decoded.worldId());
        assertEquals(7, decoded.batchSeq());
        assertEquals(1, decoded.chunks().size());
        ChunkData back = decoded.chunks().get(0);
        assertEquals(data, back);
        assertEquals(ChunkData.NO_DATA, back.heights()[0], "-32768 缺口必须原样保留");
        assertEquals(-1, back.biomes()[back.biomes().length - 1]);
        assertTrue(back.hasGaps());
    }

    @Test
    void roundtrip_multiChunkAndSteps() {
        ChunkData a = sample(0, 0, 1, false, false);
        ChunkData b = sample(-5, 12, 2, true, false);
        byte[] payload = ChunkBatchCodec.encode(1, "minecraft:the_nether", 123456789L,
                List.of(a, b));
        DecodedBatch decoded = ChunkBatchCodec.decode(payload);
        assertEquals("minecraft:the_nether", decoded.dimension());
        assertEquals(2, decoded.chunks().size());
        assertEquals(2, decoded.chunks().get(1).sampleStep());
        assertEquals(8, decoded.chunks().get(1).sampleSize());
    }

    @Test
    void littleEndianLayout_magicAndHeader() {
        ChunkData data = sample(0x01020304, -2, 1, false, false);
        byte[] payload = ChunkBatchCodec.encode(0x0A0B0C0D, "minecraft:the_end", 0x0102030405060708L,
                List.of(data));
        assertEquals('X', payload[0]);
        assertEquals('J', payload[1]);
        assertEquals('G', payload[2]);
        assertEquals('I', payload[3]);
        assertEquals('S', payload[4]);
        assertEquals(1, payload[5]);
        assertEquals(2, payload[6]); // the_end -> code 2
        assertEquals(1, payload[7]); // chunkCount
        // worldId u32 LE
        assertEquals(0x0D, payload[8] & 0xFF);
        assertEquals(0x0A, payload[11] & 0xFF);
        // batchSeq u64 LE 从 offset 12 开始
        assertEquals(0x08, payload[12] & 0xFF);
        assertEquals(0x01, payload[19] & 0xFF);
        // chunkX i32 LE（offset 20）
        assertEquals(0x04, payload[20] & 0xFF);
        assertEquals(0x01, payload[23] & 0xFF);
    }

    @Test
    void rejectsBadPayloads() {
        ChunkData data = sample(0, 0, 1, false, false);
        byte[] good = ChunkBatchCodec.encode(1, "minecraft:overworld", 1, List.of(data));

        byte[] badMagic = good.clone();
        badMagic[0] = 'Z';
        assertThrows(IllegalArgumentException.class, () -> ChunkBatchCodec.decode(badMagic));

        byte[] truncated = new byte[good.length - 3];
        System.arraycopy(good, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class, () -> ChunkBatchCodec.decode(truncated));

        byte[] hugeCount = good.clone();
        hugeCount[7] = 65;
        assertThrows(IllegalArgumentException.class, () -> ChunkBatchCodec.decode(hugeCount));

        assertThrows(IllegalArgumentException.class,
                () -> ChunkBatchCodec.encode(1, "minecraft:overworld", 1, List.of()));
    }

    @Test
    void encodeBodies_matchesEncodeOfChunks() {
        ChunkData a = sample(1, 2, 1, true, true);
        ChunkData b = sample(3, 4, 1, false, false);
        byte[] direct = ChunkBatchCodec.encode(9, "minecraft:overworld", 5, List.of(a, b));
        byte[] viaBodies = ChunkBatchCodec.encodeBodies(9, "minecraft:overworld", 5,
                List.of(ChunkBodyCodec.encodeBody(a), ChunkBodyCodec.encodeBody(b)));
        assertArrayEquals(direct, viaBodies);
    }

    @Test
    void blockHistogramRoundtripPerProductionContract() {
        // 生产契约：blockHist = u8 n + n*(u16 blockId, u32 count)
        ChunkData base = sample(0, 0, 1, true, false);
        java.util.List<ChunkData.BlockCount> hist = java.util.List.of(
                new ChunkData.BlockCount(1, 123456789L),
                new ChunkData.BlockCount(65535, 7L));
        ChunkData withHist = new ChunkData(base.chunkX(), base.chunkZ(), base.sampleStep(),
                base.sampleSize(), base.minY(), base.maxY(), base.heights(), base.biomes(), hist);
        byte[] payload = ChunkBatchCodec.encode(3, "minecraft:overworld", 9, List.of(withHist));
        ChunkData decoded = ChunkBatchCodec.decode(payload).chunks().get(0);
        assertEquals(2, decoded.blockHistogram().size());
        assertEquals(1, decoded.blockHistogram().get(0).blockId());
        assertEquals(123456789L, decoded.blockHistogram().get(0).count());
        assertEquals(65535, decoded.blockHistogram().get(1).blockId());
        assertTrue(decoded.hasBlockHistogram());
        assertEquals(ChunkData.FLAG_BLOCK_HIST, decoded.flags() & ChunkData.FLAG_BLOCK_HIST);
    }

    @Test
    void dimensionCodesMatchProductionContract() {
        // overworld=0 / the_nether=1 / the_end=2 / custom=3
        assertEquals(0, top.xuanjian.map.core.dimension.Dimensions.code("minecraft:overworld"));
        assertEquals(1, top.xuanjian.map.core.dimension.Dimensions.code("the_nether"));
        assertEquals(2, top.xuanjian.map.core.dimension.Dimensions.code("the_end"));
        assertEquals(3, top.xuanjian.map.core.dimension.Dimensions.code("minecraft:custom_void"));
    }

    @Test
    void sampleStepBoundsFollowContract() {
        // 契约 sampleStep 1..4
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkData(0, 0, 8, 2, (short) 0, (short) 0, new short[4], null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkData(0, 0, 0, 16, (short) 0, (short) 0, new short[256], null, List.of()));
    }

    @Test
    void chunkDataValidation() {
        short[] heights = new short[16 * 16];
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkData(0, 0, 3, 5, (short) 0, (short) 0, heights, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkData(0, 0, 1, 16, (short) 0, (short) 0, new short[1], null, List.of()));
    }

    @Test
    void compressionShrinksRealisticPayload() {
        // 真实高度的区块高度矩阵近似平滑，zstd/deflate 都应有明显收益
        int n = 16;
        short[] heights = new short[n * n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                heights[r * n + c] = (short) (64 + r / 2 + (c % 3));
            }
        }
        ChunkData data = new ChunkData(0, 0, 1, n, (short) 64, (short) 74,
                heights, null, List.of());
        byte[] raw = ChunkBatchCodec.encode(1, "minecraft:overworld", 1, List.of(data));
        for (top.xuanjian.map.core.compress.BlobCodec codec : List.of(
                top.xuanjian.map.core.compress.ZstdCodec.INSTANCE,
                top.xuanjian.map.core.compress.DeflateCodec.INSTANCE)) {
            byte[] compressed = codec.compress(raw);
            assertTrue(compressed.length < raw.length,
                    codec.contentEncoding() + " 压缩后应更小: raw=" + raw.length
                            + ", compressed=" + compressed.length);
            assertArrayEquals(raw, codec.decompress(compressed));
        }
    }
}
