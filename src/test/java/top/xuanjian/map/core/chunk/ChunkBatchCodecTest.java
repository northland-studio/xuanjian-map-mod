package top.xuanjian.map.core.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void surfaceBlockMatrixLayoutFollowsContractV2() {
        // v2：flags bit3 + surfaceBlockMatrix u16[n*n]，紧跟 blockHist 之后
        ChunkData base = sample(3, -4, 1, true, false);
        int n = base.cells();
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) {
            surface[i] = switch (i % 3) {
                case 0 -> top.xuanjian.map.core.chunk.BlockHash.hash16("minecraft:grass_block");
                case 1 -> top.xuanjian.map.core.chunk.BlockHash.hash16("minecraft:water");
                default -> top.xuanjian.map.core.chunk.BlockHash.hash16("minecraft:stone");
            };
        }
        surface[7] = top.xuanjian.map.core.chunk.BlockHash.NO_SURFACE;
        ChunkData withSurface = new ChunkData(base.chunkX(), base.chunkZ(), base.sampleStep(),
                base.sampleSize(), base.minY(), base.maxY(), base.heights(), base.biomes(),
                base.blockHistogram(), surface);

        byte[] legacy = ChunkBatchCodec.encode(1, "minecraft:overworld", 1, List.of(base));
        byte[] v2 = ChunkBatchCodec.encode(1, "minecraft:overworld", 1, List.of(withSurface));

        // 1) 含 bit3 时长度正好多出 n*n*2 字节
        assertEquals(n * 2, v2.length - legacy.length,
                "surfaceBlockMatrix 应只增加 u16[n*n] 字节");
        // 2) 严格按契约布局：批次头 20 + chunk 基础 15 + height 2n + biome 2n + blockHist + surface 2n
        int expected = 20 + 15 + n * 2 + n * 2
                + (1 + base.blockHistogram().size() * 6) + n * 2;
        assertEquals(expected, v2.length, "v2 载荷总长度应符合契约布局");

        // 3) flags 与解码内容
        ChunkData decoded = ChunkBatchCodec.decode(v2).chunks().get(0);
        assertEquals(ChunkData.FLAG_SURFACE_BLOCKS,
                decoded.flags() & ChunkData.FLAG_SURFACE_BLOCKS, "必须置 flags bit3");
        assertArrayEquals(surface, decoded.surfaceBlocks(), "地表方块 hash16 矩阵需原样往返");
        assertEquals(top.xuanjian.map.core.chunk.BlockHash.NO_SURFACE,
                decoded.surfaceBlocks()[7], "0xFFFF 无数据必须保留，不能填 0");

        // 4) 逐字段验证「紧跟 blockHist 之后」：手工按契约顺序解析
        top.xuanjian.map.core.codec.ByteReader r = new top.xuanjian.map.core.codec.ByteReader(v2);
        r.ascii(5);
        r.u8();
        r.u8();
        r.u8();
        r.u32();
        r.i64();
        r.i32();
        r.i32();
        int step = r.u8();
        int size = r.u8();
        assertEquals(1, step);
        assertEquals(16, size);
        r.i16();
        r.i16();
        int flags = r.u8();
        assertEquals(ChunkData.FLAG_SURFACE_BLOCKS, flags & ChunkData.FLAG_SURFACE_BLOCKS);
        for (int i = 0; i < n; i++) {
            r.i16();
        }
        for (int i = 0; i < n; i++) {
            r.u16();
        }
        int entries = r.u8();
        assertEquals(base.blockHistogram().size(), entries);
        for (int i = 0; i < entries; i++) {
            r.u16();
            r.u32();
        }
        int[] parsedSurface = new int[n];
        for (int i = 0; i < n; i++) {
            parsedSurface[i] = r.u16();
        }
        assertFalse(r.hasRemaining(), "surfaceBlockMatrix 应是 chunk 段的最后一段数据");
        assertArrayEquals(surface, parsedSurface, "surfaceBlockMatrix 必须紧跟 blockHist 之后");
    }

    @Test
    void legacyPayloadWithoutSurfaceStillParses() {
        ChunkData legacyChunk = sample(0, 0, 1, true, true);
        byte[] legacy = ChunkBatchCodec.encode(7, "minecraft:the_nether", 3, List.of(legacyChunk));
        ChunkData decoded = ChunkBatchCodec.decode(legacy).chunks().get(0);
        assertFalse(decoded.hasSurfaceBlocks(), "旧格式不应带 surfaceBlockMatrix");
        assertEquals(0, decoded.flags() & ChunkData.FLAG_SURFACE_BLOCKS);
        assertEquals(ChunkData.NO_DATA, decoded.heights()[0], "旧格式的缺口仍需保留");
        // 旧格式长度：头 20 + 基础 15 + height 2n + biome 2n（无 blockHist 段）
        int n = decoded.cells();
        assertEquals(20 + 15 + n * 2 + n * 2, legacy.length);
    }

    @Test
    void surfaceMatrixMustMatchSampleGridSize() {
        ChunkData base = sample(0, 0, 1, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkData(base.chunkX(), base.chunkZ(), base.sampleStep(),
                        base.sampleSize(), base.minY(), base.maxY(), base.heights(),
                        base.biomes(), base.blockHistogram(), new int[3]));
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
