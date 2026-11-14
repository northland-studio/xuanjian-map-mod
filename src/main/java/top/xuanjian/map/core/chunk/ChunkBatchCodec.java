package top.xuanjian.map.core.chunk;

import top.xuanjian.map.core.codec.ByteReader;
import top.xuanjian.map.core.codec.ByteWriter;
import top.xuanjian.map.core.dimension.Dimensions;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传批次编解码，严格对齐 GIS 契约 {@code POST /api/gis/client/chunks}：
 *
 * <pre>
 * magic "XJGIS"(5) + version=1(u8) + dimensionCode(u8) + chunkCount(u8) + worldId(u32) + batchSeq(u64)
 * 之后依次是 chunkCount 个 {@link ChunkBodyCodec} 段
 * </pre>
 *
 * 限制：1..64 chunk；解压后总大小由调用方按 8MiB 上限校验。
 */
public final class ChunkBatchCodec {

    public static final String MAGIC = "XJGIS";
    public static final int VERSION = 1;
    public static final int MIN_CHUNKS = 1;
    public static final int MAX_CHUNKS = 64;

    private ChunkBatchCodec() {
    }

    public static byte[] encode(long worldId, String dimension, long batchSeq, List<ChunkData> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("上传批次不能为空");
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("上传批次超过 " + MAX_CHUNKS + " 个 chunk: " + chunks.size());
        }
        if (worldId < 0 || worldId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("worldId 必须是 u32: " + worldId);
        }
        ByteWriter w = new ByteWriter(16 + chunks.size() * 600);
        w.ascii(MAGIC);
        w.u8(VERSION);
        w.u8(Dimensions.code(dimension));
        w.u8(chunks.size());
        w.u32(worldId);
        w.i64(batchSeq);
        for (ChunkData chunk : chunks) {
            w.bytes(ChunkBodyCodec.encodeBody(chunk));
        }
        return w.toByteArray();
    }

    /**
     * 直接用已编码好的 chunk 段（本地缓存里的 payload）拼批次，免去 decode→encode 开销。
     */
    public static byte[] encodeBodies(long worldId, String dimension, long batchSeq,
                                      List<byte[]> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            throw new IllegalArgumentException("上传批次不能为空");
        }
        if (bodies.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("上传批次超过 " + MAX_CHUNKS + " 个 chunk: " + bodies.size());
        }
        if (worldId < 0 || worldId > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("worldId 必须是 u32: " + worldId);
        }
        int size = headerSize();
        for (byte[] body : bodies) {
            size += body.length;
        }
        ByteWriter w = new ByteWriter(size);
        w.ascii(MAGIC);
        w.u8(VERSION);
        w.u8(Dimensions.code(dimension));
        w.u8(bodies.size());
        w.u32(worldId);
        w.i64(batchSeq);
        for (byte[] body : bodies) {
            w.bytes(body);
        }
        return w.toByteArray();
    }

    public static DecodedBatch decode(byte[] payload) {        ByteReader r = new ByteReader(payload);
        if (r.remaining() < 5) {
            throw new IllegalArgumentException("批次过短");
        }
        String magic = r.ascii(5);
        if (!MAGIC.equals(magic)) {
            throw new IllegalArgumentException("magic 不匹配: " + magic);
        }
        int version = r.u8();
        if (version != VERSION) {
            throw new IllegalArgumentException("不支持的批次版本: " + version);
        }
        int dimensionCode = r.u8();
        int chunkCount = r.u8();
        if (chunkCount < MIN_CHUNKS || chunkCount > MAX_CHUNKS) {
            throw new IllegalArgumentException("chunkCount 非法: " + chunkCount);
        }
        long worldId = r.u32();
        long batchSeq = r.i64();
        List<ChunkData> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(ChunkBodyCodec.decodeBody(r));
        }
        if (r.hasRemaining()) {
            throw new IllegalArgumentException("批次尾部有多余字节: " + r.remaining());
        }
        return new DecodedBatch(version, Dimensions.fromCode(dimensionCode), worldId, batchSeq, chunks);
    }

    /** 头长度（估算用）。 */
    public static int headerSize() {
        return 5 + 1 + 1 + 1 + 4 + 8;
    }
}
