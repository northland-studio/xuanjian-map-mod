package top.xuanjian.map.core.chunk;

import java.util.List;

/** 解码后的上传批次（mock/测试与排障用）。 */
public record DecodedBatch(int version, String dimension, long worldId, long batchSeq,
                           List<ChunkData> chunks) {

    public DecodedBatch {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
