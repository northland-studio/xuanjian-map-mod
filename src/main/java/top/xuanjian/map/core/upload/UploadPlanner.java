package top.xuanjian.map.core.upload;

import top.xuanjian.map.core.chunk.ChunkBatchCodec;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.storage.GisStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 上传计划与分批（契约：plan ≤4096 chunk；上传批 1..64 chunk；
 * 压缩后 ≤4MiB、解压后 ≤8MiB）。
 */
public final class UploadPlanner {

    private UploadPlanner() {
    }

    /** 把待上传 chunk 按 plan 请求上限切分（服务端一次 plan 最多 4096 个坐标）。 */
    public static List<List<GisStore.PendingChunk>> splitPlanRequests(List<GisStore.PendingChunk> pending,
                                                                      int maxPlanChunks) {
        List<List<GisStore.PendingChunk>> out = new ArrayList<>();
        if (pending == null || pending.isEmpty()) {
            return out;
        }
        int limit = Math.max(1, maxPlanChunks);
        for (int from = 0; from < pending.size(); from += limit) {
            out.add(new ArrayList<>(pending.subList(from, Math.min(pending.size(), from + limit))));
        }
        return out;
    }

    /**
     * 把「服务端 missing 的 chunk」切成上传批：同时满足 chunk 数上限与解压后字节上限。
     *
     * @param missing     服务端说缺的 chunk
     * @param limits      服务端限制
     * @param rawByteFor  取 chunk 段未压缩字节数（可用于预估）
     */
    public static List<List<GisStore.PendingChunk>> splitBatches(List<GisStore.PendingChunk> missing,
                                                                 Limits limits,
                                                                 java.util.function.ToIntFunction<GisStore.PendingChunk> rawByteFor) {
        List<List<GisStore.PendingChunk>> out = new ArrayList<>();
        if (missing == null || missing.isEmpty()) {
            return out;
        }
        int maxChunks = Math.max(1, Math.min(limits.maxBatchChunks(), ChunkBatchCodec.MAX_CHUNKS));
        long maxRaw = Math.max(1024L, limits.maxRawBytes());
        List<GisStore.PendingChunk> batch = new ArrayList<>();
        long batchBytes = ChunkBatchCodec.headerSize();
        for (GisStore.PendingChunk chunk : missing) {
            int size = rawByteFor == null ? chunk.payload().length : rawByteFor.applyAsInt(chunk);
            if (!batch.isEmpty() && (batch.size() + 1 > maxChunks || batchBytes + size > maxRaw)) {
                out.add(batch);
                batch = new ArrayList<>();
                batchBytes = ChunkBatchCodec.headerSize();
            }
            batch.add(chunk);
            batchBytes += size;
        }
        if (!batch.isEmpty()) {
            out.add(batch);
        }
        return out;
    }
}
