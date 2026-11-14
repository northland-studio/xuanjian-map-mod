package top.xuanjian.map.core.upload;

import top.xuanjian.map.core.chunk.ChunkBatchCodec;
import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.compress.BlobCodec;
import top.xuanjian.map.core.compress.DeflateCodec;
import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.net.ApiException;
import top.xuanjian.map.core.net.GisApi;
import top.xuanjian.map.core.storage.GisStore;
import top.xuanjian.map.core.util.GisLog;
import top.xuanjian.map.core.util.Hashes;
import top.xuanjian.map.core.util.TimeSource;
import top.xuanjian.map.core.world.WorldSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 增量上传引擎（规范第八节 + Lead 定稿契约）。
 *
 * <p>能力：
 * <ul>
 *   <li><b>增量</b>：先 plan，只传服务端 missing；</li>
 *   <li><b>断点续传/网络恢复</b>：任务与 chunk 状态落 SQLite，重启/断网后按 nextAttemptAt 继续；</li>
 *   <li><b>失败重试</b>：指数退避 1s→60s，429 遵守 Retry-After；</li>
 *   <li><b>幂等</b>：每个批次固定 Idempotency-Key，重试复用同一 key；</li>
 *   <li><b>限速</b>：令牌桶，默认 60 次/分钟；</li>
 *   <li><b>失败不丢数据</b>：只改状态，不删 chunk 数据。</li>
 * </ul>
 *
 * <p>线程：本类方法全部阻塞，由客户端的上传线程调用，绝不在渲染线程执行。
 */
public final class Uploader {

    private static final String TAG = "upload";
    private static final int MAX_JOBS_PER_TICK = 2;

    private final GisStore store;
    private final GisApi api;
    private final GisConfig config;
    private final Limits limits;
    private final RetryPolicy retryPolicy;
    private final RateLimiter rateLimiter;
    private final TimeSource time;
    private final BlobCodec codec;

    public Uploader(GisStore store, GisApi api, GisConfig config, Limits limits,
                    TimeSource time, BlobCodec codec) {
        this.store = store;
        this.api = api;
        this.config = config;
        this.limits = limits == null ? Limits.DEFAULT : limits;
        this.time = time;
        this.codec = codec == null ? DeflateCodec.INSTANCE : codec;
        this.retryPolicy = RetryPolicy.DEFAULT;
        this.rateLimiter = new RateLimiter(config.uploadRatePerMinute(), time);
    }

    /** 一轮工作结果（UI/测试断言用）。 */
    public record TickResult(int planned, int uploadedJobs, int uploadedChunks,
                             int failedJobs, int retriedJobs, String lastError) {
        public static final TickResult NONE = new TickResult(0, 0, 0, 0, 0, "");
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * 执行一轮：先放行到期的重试，再上传至多若干批；没有待传任务时尝试 plan 新批次。
     * 幂等可重复调用（客户端每若干秒调一次）。
     */
    public TickResult tick(WorldSession session) {
        if (session == null || !session.canUpload()) {
            return TickResult.NONE;
        }
        long now = time.millis();
        int retried = releaseDueRetries(now);
        int uploadedJobs = 0;
        int uploadedChunks = 0;
        int failed = 0;
        String lastError = "";
        for (int i = 0; i < MAX_JOBS_PER_TICK; i++) {
            if (!rateLimiter.tryAcquire()) {
                break;
            }
            JobOutcome outcome = uploadOne(session);
            if (outcome == null) {
                // 没有待上传任务：尝试生成新批次
                break;
            }
            switch (outcome.kind) {
                case OK -> {
                    uploadedJobs++;
                    uploadedChunks += outcome.chunks;
                }
                case FAILED -> {
                    failed++;
                    lastError = outcome.error;
                }
                case RETRY -> lastError = outcome.error;
            }
        }

        int planned = 0;
        GisStore.JobStats jobStats = store.jobStats();
        boolean hasActiveJobs = jobStats.pending() > 0 || jobStats.inFlight() > 0 || jobStats.retry() > 0;
        if (uploadedJobs == 0 && !hasActiveJobs) {
            planned = buildJobs(session);
        }
        return new TickResult(planned, uploadedJobs, uploadedChunks, failed, retried, lastError);
    }

    /** 放行到期的 RETRY 任务（网络恢复/重启后继续）。 */
    public int releaseDueRetries(long now) {
        int released = 0;
        for (GisStore.JobRow job : store.jobsByState(GisStore.JobState.RETRY, 256)) {
            if (job.nextAttemptAt() <= now) {
                store.updateJob(job.id(), GisStore.JobState.PENDING, job.attempts(), 0, job.lastError(), now);
                released++;
            }
        }
        return released;
    }

    /**
     * 生成待上传批次：pending chunks → plan → missing → 分批 → 落任务队列。
     *
     * @return 新建任务数
     */
    public int buildJobs(WorldSession session) {
        List<GisStore.PendingChunk> pending =
                store.pendingChunks(session.worldId(), session.dimension(), limits.maxPlanChunks());
        if (pending.isEmpty()) {
            return 0;
        }
        // 已在队列/重试中的 chunk 不得重复建任务（否则同一 chunk 会被多次上传）
        Set<Long> covered = new HashSet<>();
        for (GisStore.JobState active : new GisStore.JobState[]{
                GisStore.JobState.PENDING, GisStore.JobState.IN_FLIGHT, GisStore.JobState.RETRY}) {
            for (GisStore.JobRow job : store.jobsByState(active, 512)) {
                for (ChunkKey key : decodeKeys(job.worldId(), job.dimension(), job.chunkKeys())) {
                    covered.add(coordKey(key.chunkX(), key.chunkZ()));
                }
            }
        }
        if (!covered.isEmpty()) {
            pending = pending.stream()
                    .filter(c -> !covered.contains(coordKey(c.key().chunkX(), c.key().chunkZ())))
                    .toList();
        }
        if (pending.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (List<GisStore.PendingChunk> requestChunks :
                UploadPlanner.splitPlanRequests(pending, limits.maxPlanChunks())) {
            List<int[]> coords = new ArrayList<>(requestChunks.size());
            Map<Long, GisStore.PendingChunk> byCoord = new HashMap<>();
            for (GisStore.PendingChunk chunk : requestChunks) {
                coords.add(new int[]{chunk.key().chunkX(), chunk.key().chunkZ()});
                byCoord.put(coordKey(chunk.key().chunkX(), chunk.key().chunkZ()), chunk);
            }
            GisApi.PlanResult plan;
            try {
                plan = api.planChunks(session.worldId(), session.dimension(), coords);
            } catch (ApiException e) {
                GisLog.warn(TAG, "plan 失败: " + e.describe());
                return created;
            }
            List<GisStore.PendingChunk> missing = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (int[] c : plan.missing()) {
                GisStore.PendingChunk chunk = byCoord.get(coordKey(c[0], c[1]));
                if (chunk != null && seen.add(coordKey(c[0], c[1]))) {
                    missing.add(chunk);
                }
            }
            // 服务端说 have 的 chunk 直接标记已上传，避免下次重复 plan
            List<ChunkKey> haveKeys = new ArrayList<>();
            for (GisStore.PendingChunk chunk : requestChunks) {
                if (!seen.contains(coordKey(chunk.key().chunkX(), chunk.key().chunkZ()))) {
                    haveKeys.add(chunk.key());
                }
            }
            if (!haveKeys.isEmpty()) {
                store.setChunkState(haveKeys, ChunkState.UPLOADED, 0, null);
            }
            if (missing.isEmpty()) {
                continue;
            }
            for (List<GisStore.PendingChunk> batch :
                    UploadPlanner.splitBatches(missing, limits, c -> c.payload().length)) {
                if (enqueueBatch(session, batch)) {
                    created++;
                }
            }
        }
        if (created > 0) {
            GisLog.info(TAG, "新增上传任务 " + created + " 个（world=" + session.worldId()
                    + ", dim=" + session.dimension() + "）");
        }
        return created;
    }

    private boolean enqueueBatch(WorldSession session, List<GisStore.PendingChunk> batch) {
        long batchSeq = store.nextBatchSeq(session.worldId(), session.dimension());
        List<byte[]> bodies = new ArrayList<>(batch.size());
        List<ChunkKey> keys = new ArrayList<>(batch.size());
        StringBuilder keyText = new StringBuilder();
        for (GisStore.PendingChunk chunk : batch) {
            bodies.add(chunk.payload());
            keys.add(chunk.key());
            if (keyText.length() > 0) {
                keyText.append(';');
            }
            keyText.append(chunk.key().chunkX()).append(',').append(chunk.key().chunkZ());
        }
        long coordHash = 0;
        for (GisStore.PendingChunk chunk : batch) {
            coordHash = coordHash * 1_000_003L + chunk.key().chunkX() * 31L + chunk.key().chunkZ();
        }
        String idempotencyKey = Hashes.idempotencyKey(
                session.worldId(), session.dimension(), batchSeq, coordHash);
        byte[] raw = ChunkBatchCodec.encodeBodies(session.worldId(), session.dimension(), batchSeq, bodies);
        long now = time.millis();
        store.insertJob(new GisStore.JobRow(0, session.worldId(), session.dimension(), batchSeq,
                idempotencyKey, raw, batch.size(), keyText.toString(),
                GisStore.JobState.PENDING, 0, 0, null, now, now));
        store.setChunkState(keys, ChunkState.QUEUED, 0, null);
        return true;
    }

    private enum OutcomeKind { OK, RETRY, FAILED }

    private record JobOutcome(OutcomeKind kind, int chunks, String error) {
    }

    private JobOutcome uploadOne(WorldSession session) {
        List<GisStore.JobRow> pending = store.jobsByState(GisStore.JobState.PENDING, 1);
        if (pending.isEmpty()) {
            return null;
        }
        GisStore.JobRow job = pending.get(0);
        int attempts = job.attempts() + 1;
        long now = time.millis();
        store.updateJob(job.id(), GisStore.JobState.IN_FLIGHT, attempts, 0, null, now);
        List<ChunkKey> keys = decodeKeys(session.worldId(), job.dimension(), job.chunkKeys());
        byte[] compressed;
        try {
            compressed = codec.compress(job.payload());
        } catch (RuntimeException e) {
            return fail(job, keys, attempts, "compress_failed: " + e.getMessage());
        }
        if (compressed.length > limits.maxCompressedBytes()) {
            return fail(job, keys, attempts,
                    "payload_too_large: " + compressed.length + " > " + limits.maxCompressedBytes());
        }
        try {
            GisApi.UploadResult result = api.uploadChunks(
                    session.worldId(), job.dimension(), job.idempotencyKey(),
                    codec.contentEncoding(), compressed);
            store.setChunkState(keys, ChunkState.UPLOADED, 0, null);
            store.updateJob(job.id(), GisStore.JobState.DONE, attempts, 0, null, time.millis());
            store.setLastUploadAt(time.millis());
            GisLog.info(TAG, "批次 #" + job.id() + " 上传成功"
                    + (result.duplicate() ? "（服务端判定幂等重放）" : "")
                    + "，chunks=" + job.chunkCount());
            return new JobOutcome(OutcomeKind.OK, job.chunkCount(), "");
        } catch (ApiException e) {
            return handleUploadError(job, keys, attempts, e);
        }
    }

    private JobOutcome handleUploadError(GisStore.JobRow job, List<ChunkKey> keys,
                                         int attempts, ApiException e) {
        long now = time.millis();
        String error = e.describe();
        if (e.isRateLimited()) {
            long pause = time.millis() + retryPolicy.delayForRateLimited(e.retryAfterSeconds());
            rateLimiter.pauseUntil(pause);
            store.updateJob(job.id(), GisStore.JobState.RETRY, attempts, pause, error, now);
            return new JobOutcome(OutcomeKind.RETRY, 0, error);
        }
        if (e.isPermanent()) {
            store.updateJob(job.id(), GisStore.JobState.FAILED, attempts, 0, error, now);
            store.setChunkState(keys, ChunkState.FAILED, 0, error);
            GisLog.warn(TAG, "批次 #" + job.id() + " 永久失败（本地数据保留）: " + error);
            return new JobOutcome(OutcomeKind.FAILED, 0, error);
        }
        if (retryPolicy.exhausted(attempts)) {
            store.updateJob(job.id(), GisStore.JobState.FAILED, attempts, 0, error, now);
            store.setChunkState(keys, ChunkState.FAILED, 0, error);
            GisLog.warn(TAG, "批次 #" + job.id() + " 重试耗尽（本地数据保留）: " + error);
            return new JobOutcome(OutcomeKind.FAILED, 0, error);
        }
        long next = now + retryPolicy.delayMsForAttempt(attempts);
        store.updateJob(job.id(), GisStore.JobState.RETRY, attempts, next, error, now);
        GisLog.debug(TAG, "批次 #" + job.id() + " 失败，将重试: " + error);
        return new JobOutcome(OutcomeKind.RETRY, 0, error);
    }

    private JobOutcome fail(GisStore.JobRow job, List<ChunkKey> keys, int attempts, String error) {
        long now = time.millis();
        store.updateJob(job.id(), GisStore.JobState.FAILED, attempts, 0, error, now);
        store.setChunkState(keys, ChunkState.FAILED, 0, error);
        return new JobOutcome(OutcomeKind.FAILED, 0, error);
    }

    /** 手动重试：把所有 FAILED 任务和 chunk 重置回待上传。 */
    public int retryFailed() {
        int count = 0;
        long now = time.millis();
        for (GisStore.JobRow job : store.jobsByState(GisStore.JobState.FAILED, 256)) {
            store.updateJob(job.id(), GisStore.JobState.PENDING, 0, 0, null, now);
            store.setChunkState(decodeKeys(job.worldId(), job.dimension(), job.chunkKeys()),
                    ChunkState.QUEUED, 0, null);
            count++;
        }
        return count;
    }

    /* ==================== chunkKeys 编解码 ==================== */

    static String encodeKeys(List<ChunkKey> keys) {
        StringBuilder sb = new StringBuilder();
        for (ChunkKey k : keys) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(k.chunkX()).append(',').append(k.chunkZ());
        }
        return sb.toString();
    }

    static List<ChunkKey> decodeKeys(long worldId, String dimension, String text) {
        List<ChunkKey> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(";")) {
            int idx = part.indexOf(',');
            if (idx <= 0) {
                continue;
            }
            try {
                int x = Integer.parseInt(part.substring(0, idx).trim());
                int z = Integer.parseInt(part.substring(idx + 1).trim());
                out.add(new ChunkKey(worldId, dimension, x, z));
            } catch (NumberFormatException ignored) {
                // 跳过坏数据
            }
        }
        return out;
    }

    private static long coordKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
