package top.xuanjian.map.core.upload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.xuanjian.map.core.chunk.ChunkBodyCodec;
import top.xuanjian.map.core.chunk.ChunkData;
import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.compress.DeflateCodec;
import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.net.ApiException;
import top.xuanjian.map.core.storage.GisStore;
import top.xuanjian.map.core.storage.MemoryGisStore;
import top.xuanjian.map.core.util.TimeSource;
import top.xuanjian.map.core.world.WorldSession;
import top.xuanjian.map.testutil.FakeGisApi;
import top.xuanjian.map.testutil.TestChunks;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploaderTest {

    private static final String DIM = "minecraft:overworld";
    private static final long WORLD = 1;

    @TempDir
    Path tempDir;

    private MemoryGisStore store;
    private FakeGisApi api;
    private TimeSource.Manual time;
    private Uploader uploader;

    @BeforeEach
    void setUp() {
        GisConfig config = new GisConfig(tempDir);
        store = new MemoryGisStore();
        store.init();
        api = new FakeGisApi();
        time = TimeSource.manual(1_000_000L);
        uploader = new Uploader(store, api, config, Limits.DEFAULT, time, DeflateCodec.INSTANCE);
    }

    private WorldSession session(boolean registered) {
        return new WorldSession("yl", WORLD, "main", DIM, "26.2", registered, "ACTIVE",
                time.millis(), "");
    }

    private void addChunk(int x, int z) {
        ChunkData data = TestChunks.sample(x, z, true, true);
        store.putChunk(new GisStore.ChunkRow(new ChunkKey(WORLD, DIM, x, z), ChunkState.CAPTURED,
                ChunkBodyCodec.encodeBody(data), data.sampleStep(), data.sampleSize(),
                data.minY(), data.maxY(), data.flags(), time.millis(), 0, null, 0));
    }

    @Test
    void incrementalPlanOnlyUploadsMissing() {
        addChunk(0, 0);
        addChunk(1, 0);
        addChunk(2, 0);
        api.serverHas.add(FakeGisApi.coord(1, 0));

        Uploader.TickResult first = uploader.tick(session(true));
        assertEquals(1, first.planned(), "应生成 1 个批次（2 个 missing chunk）");
        assertEquals(0, first.uploadedJobs());
        GisStore.ChunkStats stats = store.chunkStats();
        assertEquals(1, stats.uploaded(), "服务端已有的 chunk 应直接标记 UPLOADED");
        assertEquals(2, stats.queued());

        Uploader.TickResult second = uploader.tick(session(true));
        assertEquals(1, second.uploadedJobs());
        assertEquals(2, second.uploadedChunks());
        assertEquals(1, api.uploadedBatches.size());
        assertEquals(2, api.uploadedBatches.get(0).chunks().size());
        assertEquals(3, store.chunkStats().uploaded());
        assertEquals(1, store.jobStats().done());
    }

    @Test
    void uploadPreservesNoDataGapsAndUsesContractEncoding() {
        addChunk(5, -3);
        uploader.tick(session(true));
        uploader.tick(session(true));
        assertEquals(1, api.uploadedBatches.size());
        ChunkData decoded = api.uploadedBatches.get(0).chunks().get(0);
        assertEquals(ChunkData.NO_DATA, decoded.heights()[0], "无数据缺口必须保留");
        assertEquals(-1, decoded.biomes()[10]);
        assertEquals("deflate", api.contentEncodings.get(0));
        assertTrue(api.idempotencyKeys.get(0).matches("[A-Za-z0-9_-]{8,64}"),
                "Idempotency-Key 必须符合契约字符集: " + api.idempotencyKeys.get(0));
        assertEquals(WORLD, api.uploadedBatches.get(0).worldId());
    }

    @Test
    void retryWithBackoffThenSuccessReusesIdempotencyKey() {
        addChunk(0, 0);
        uploader.tick(session(true)); // plan
        api.uploadErrors.add(new ApiException("server_error", "boom", 500));
        Uploader.TickResult failedOnce = uploader.tick(session(true));
        assertEquals(0, failedOnce.uploadedJobs());
        assertTrue(failedOnce.lastError().contains("boom"));
        assertEquals(1, store.jobStats().retry());

        GisStore.JobRow retryJob = store.jobsByState(GisStore.JobState.RETRY, 1).get(0);
        assertEquals(time.millis() + 1000L, retryJob.nextAttemptAt(), "首次重试应退避 1s");
        assertEquals(1, store.chunkStats().queued(), "失败后本地数据不丢");

        time.advance(1500L);
        Uploader.TickResult recovered = uploader.tick(session(true));
        assertEquals(1, recovered.uploadedJobs());
        assertEquals(1, store.jobStats().done());
        assertEquals(2, api.idempotencyKeys.size(), "重试应复用同一批次的请求");
        assertEquals(api.idempotencyKeys.get(0), api.idempotencyKeys.get(1));
    }

    @Test
    void permanentErrorMarksFailedButKeepsLocalData() {
        addChunk(0, 0);
        uploader.tick(session(true));
        api.uploadErrors.add(new ApiException("invalid_payload", "bad chunk 0", 400, -1, 0));
        Uploader.TickResult result = uploader.tick(session(true));
        assertEquals(1, result.failedJobs());
        assertEquals(1, store.jobStats().failed());
        assertEquals(1, store.chunkStats().failed());
        List<GisStore.PendingChunk> kept = store.pendingChunks(WORLD, DIM, 10, true);
        assertEquals(1, kept.size(), "失败后本地 chunk 数据必须保留");
        assertNotNull(kept.get(0).payload());

        assertEquals(1, uploader.retryFailed());
        assertEquals(0, store.jobStats().failed());
        assertEquals(1, store.jobStats().pending());
        assertEquals(1, store.chunkStats().queued());
    }

    @Test
    void rateLimitedHonoursRetryAfter() {
        addChunk(0, 0);
        uploader.tick(session(true));
        api.uploadErrors.add(new ApiException("rate_limited", "too many", 429, 2, -1));
        uploader.tick(session(true));
        GisStore.JobRow row = store.jobsByState(GisStore.JobState.RETRY, 1).get(0);
        assertTrue(row.nextAttemptAt() >= time.millis() + 2000L);
        assertTrue(uploader.rateLimiter().pausedUntil() > time.millis());
    }

    @Test
    void unregisteredWorldNeverUploads() {
        addChunk(0, 0);
        Uploader.TickResult result = uploader.tick(session(false));
        assertEquals(Uploader.TickResult.NONE, result);
        assertTrue(api.calls.isEmpty(), "未绑定世界时不得调用任何上传 API");
        assertEquals(1, store.chunkStats().captured());
    }

    @Test
    void planFailureKeepsChunksCaptured() {
        addChunk(0, 0);
        api.planErrors.add(new ApiException("server_error", "plan down", 500));
        int created = uploader.buildJobs(session(true));
        assertEquals(0, created);
        assertEquals(1, store.chunkStats().captured(), "plan 失败不得丢本地数据");
    }

    @Test
    void retryPolicyDelays() {
        RetryPolicy policy = RetryPolicy.DEFAULT;
        assertEquals(1000, policy.delayMsForAttempt(1));
        assertEquals(2000, policy.delayMsForAttempt(2));
        assertEquals(4000, policy.delayMsForAttempt(3));
        assertEquals(60_000, policy.delayMsForAttempt(20));
        assertTrue(policy.exhausted(8));
        assertFalse(policy.exhausted(7));
        assertEquals(3000, policy.delayForRateLimited(3));
    }
}
