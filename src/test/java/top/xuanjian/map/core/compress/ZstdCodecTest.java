package top.xuanjian.map.core.compress;

import org.junit.jupiter.api.Test;
import top.xuanjian.map.core.chunk.ChunkBatchCodec;
import top.xuanjian.map.core.chunk.ChunkData;
import top.xuanjian.map.testutil.TestChunks;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZstdCodecTest {

    @Test
    void roundtripSmallAndEmpty() {
        BlobCodec zstd = ZstdCodec.INSTANCE;
        assertEquals("zstd", zstd.contentEncoding());

        byte[] text = "玄剑 GIS · Xuanjian GIS zstd roundtrip".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(text, zstd.decompress(zstd.compress(text)));

        byte[] empty = new byte[0];
        assertArrayEquals(empty, zstd.decompress(zstd.compress(empty)));
    }

    @Test
    void compressesRealisticChunkPayload() {
        ChunkData chunk = TestChunks.sample(0, 0, true, true);
        byte[] raw = ChunkBatchCodec.encode(1, "minecraft:overworld", 1, List.of(chunk));
        byte[] compressed = ZstdCodec.INSTANCE.compress(raw);
        byte[] back = ZstdCodec.INSTANCE.decompress(compressed);
        assertArrayEquals(raw, back, "zstd 解压必须与原文一致");
        assertTrue(compressed.length < raw.length,
                "zstd 应有压缩收益: raw=" + raw.length + ", compressed=" + compressed.length);
        // 解出的批次仍保留缺口语义
        ChunkData decoded = ChunkBatchCodec.decode(back).chunks().get(0);
        assertEquals(ChunkData.NO_DATA, decoded.heights()[0]);
    }

    @Test
    void blobCodecsRegistryFollowsContract() {
        assertNotNull(BlobCodecs.byEncoding("zstd"));
        assertEquals("zstd", BlobCodecs.byEncoding("zstd").contentEncoding());
        assertEquals("gzip", BlobCodecs.byEncoding("gzip").contentEncoding());
        assertEquals("deflate", BlobCodecs.byEncoding("deflate").contentEncoding());
        assertEquals("identity", BlobCodecs.byEncoding("identity").contentEncoding());
        // 未知编码回退 deflate（契约允许）
        assertEquals("deflate", BlobCodecs.byEncoding("brotli").contentEncoding());
        assertTrue(BlobCodecs.zstdAvailable());
    }

    @Test
    void startupSelfCheckKeepsOrFallsBack() {
        BlobCodecs.verifyAtStartup();
        assertNotNull(BlobCodecs.byEncoding("zstd"));
    }

    @Test
    void identityAndGzipRoundtrip() {
        byte[] data = "hello 玄剑".getBytes(StandardCharsets.UTF_8);
        for (BlobCodec codec : List.of(IdentityCodec.INSTANCE, GzipCodec.INSTANCE, DeflateCodec.INSTANCE)) {
            assertArrayEquals(data, codec.decompress(codec.compress(data)), codec.contentEncoding());
        }
    }
}
