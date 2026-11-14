package top.xuanjian.map.core.storage;

import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.model.RegisteredWorld;

import java.util.List;
import java.util.Locale;

/**
 * 本地缓存存储接口（SQLite 为主实现；内存实现用于无 native 环境的降级与单元测试）。
 *
 * <p>线程约定：<b>所有方法都是阻塞 IO</b>，只能在专用 DB 线程（{@code DbExecutor}）调用，
 * 绝不在 Minecraft 主线程/渲染线程调用（规范第八节硬性要求）。
 *
 * <p>数据安全约定：上传失败只改状态，不删 chunk 数据；只有用户主动清缓存才删。
 */
public interface GisStore extends AutoCloseable {

    /** 上传任务状态。 */
    enum JobState {
        PENDING, IN_FLIGHT, RETRY, DONE, FAILED;

        public static JobState fromWire(String wire) {
            if (wire == null) {
                return PENDING;
            }
            try {
                return valueOf(wire.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }

        public String labelZh() {
            return switch (this) {
                case PENDING -> "待上传";
                case IN_FLIGHT -> "上传中";
                case RETRY -> "等待重试";
                case DONE -> "已完成";
                case FAILED -> "失败";
            };
        }
    }

    /** 一个本地 chunk 行。payload 即契约 chunk 段字节（未压缩）。 */
    record ChunkRow(ChunkKey key, ChunkState state, byte[] payload,
                    int sampleStep, int sampleSize, short minY, short maxY, int flags,
                    long capturedAt, int attempts, String lastError, long nextAttemptAt) {
    }

    /** 一个上传任务行。 */
    record JobRow(long id, long worldId, String dimension, long batchSeq, String idempotencyKey,
                  byte[] payload, int chunkCount, String chunkKeys, JobState state,
                  int attempts, long nextAttemptAt, String lastError,
                  long createdAt, long updatedAt) {
    }

    /** 待上传的本地 chunk（用于 plan 请求与打包）。 */
    record PendingChunk(ChunkKey key, byte[] payload, int sampleStep, int sampleSize,
                        short minY, short maxY, int flags, int attempts) {
    }

    /** 本地缓存对象行（geometry/props 已序列化为 JSON 字符串，存储层不解析）。 */
    record StoredObject(long worldId, String dimension, String objectId, String kind, String name,
                        int revision, String geometryJson, String propsJson,
                        double minX, double minZ, double maxX, double maxZ,
                        long updatedAt, boolean deleted) {
    }

    /** 统计（数据状态页用）。 */
    record ChunkStats(long total, long captured, long queued, long uploaded, long failed, long payloadBytes) {
        public static final ChunkStats EMPTY = new ChunkStats(0, 0, 0, 0, 0, 0);
    }

    record JobStats(long pending, long inFlight, long retry, long done, long failed) {
        public static final JobStats EMPTY = new JobStats(0, 0, 0, 0, 0);
    }

    record CacheStats(ChunkStats chunks, JobStats jobs, long objectCount, long dbBytes,
                      String backend, long lastUploadAt, String lastError) {
        public static final CacheStats EMPTY =
                new CacheStats(ChunkStats.EMPTY, JobStats.EMPTY, 0, 0, "unavailable", 0, "");
    }

    /* ---------------- 生命周期 ---------------- */

    void init() throws Exception;

    String backend();

    @Override
    void close();

    /* ---------------- chunks ---------------- */

    /** 幂等 upsert：同一 (worldId,dimension,x,z) 覆盖内容；已 UPLOADED 的行保持 UPLOADED。 */
    void putChunk(ChunkRow row);

    ChunkRow getChunk(ChunkKey key);

    /** 某个世界/维度下待处理（CAPTURED/QUEUED/FAILED）的 chunk，按 (x,z) 排序。 */
    List<PendingChunk> pendingChunks(long worldId, String dimension, int limit);

    List<PendingChunk> pendingChunks(long worldId, String dimension, int limit, boolean includeFailed);

    ChunkStats chunkStats();

    void setChunkState(List<ChunkKey> keys, ChunkState state, long nextAttemptAt, String error);

    void deleteChunks(long worldId, String dimension);

    /* ---------------- 上传任务 ---------------- */

    long insertJob(JobRow job);

    List<JobRow> jobsByState(JobState state, int limit);

    void updateJob(long id, JobState state, int attempts, long nextAttemptAt, String error, long updatedAt);

    void deleteJobsByState(JobState state);

    JobStats jobStats();

    /* ---------------- GIS 对象与增量同步 ---------------- */

    void upsertObjects(List<StoredObject> objects);

    void markObjectsDeleted(long worldId, String dimension, String objectId, int revision);

    List<StoredObject> loadObjects(long worldId, String dimension, boolean includeDeleted);

    String syncCursor(long worldId, String dimension);

    void setSyncCursor(long worldId, String dimension, String cursor);

    /* ---------------- 世界注册信息 ---------------- */

    void putWorlds(String serverKey, List<RegisteredWorld> worlds);

    List<RegisteredWorld> worlds();

    /* ---------------- meta ---------------- */

    long nextBatchSeq(long worldId, String dimension);

    String meta(String key, String def);

    void setMeta(String key, String value);

    long lastUploadAt();

    void setLastUploadAt(long millis);

    /* ---------------- 维护 ---------------- */

    long dbBytes();

    void clearCache();
}
