package top.xuanjian.map.client.capture;

import net.minecraft.client.Minecraft;
import top.xuanjian.map.client.GisClient;
import top.xuanjian.map.client.compat.McReflect;
import top.xuanjian.map.core.chunk.ChunkBodyCodec;
import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.storage.GisStore;
import top.xuanjian.map.core.util.GisLog;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地区块采集服务。
 *
 * <p>线程：采样本身必须在渲染线程读 {@code ClientLevel}（Minecraft 世界数据不是线程安全的），
 * 但每 tick 有严格预算（默认最多 2 个 chunk），编码/写 SQLite 全部交给后台 DB 线程。
 */
public final class ChunkCaptureService {

    private static final String TAG = "capture";
    private static final int MAX_CHUNKS_PER_TICK = 2;

    private final GisClient client;
    /** world|dimension|chunkKey 去重，避免同一位置反复采样。 */
    private final Set<String> captured = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private int tickCounter;
    private String sessionKey = "";

    public ChunkCaptureService(GisClient client) {
        this.client = client;
    }

    public void tick(Minecraft mc) {
        GisClient c = client;
        if (mc == null || mc.player == null) {
            return;
        }
        if (!c.config().captureEnabled()
                || c.session().dimension() == null || c.session().dimension().isBlank()) {
            return;
        }
        String currentSession = c.session().worldId() + "|" + c.session().dimension();
        if (!currentSession.equals(sessionKey)) {
            sessionKey = currentSession;
            captured.clear();
            failures.clear();
            tickCounter = 0;
        }
        if (++tickCounter < c.config().captureIntervalTicks()) {
            return;
        }
        tickCounter = 0;

        Object level = McReflect.field(mc, "level");
        if (level == null) {
            level = McReflect.call(mc, new String[]{"level", "getLevel"});
        }
        if (level == null) {
            return;
        }
        int centerX = blockCoord(mc, "getBlockX") >> 4;
        int centerZ = blockCoord(mc, "getBlockZ") >> 4;
        int radius = c.config().captureRadiusChunks();
        int budget = MAX_CHUNKS_PER_TICK;
        for (int dx = -radius; dx <= radius && budget > 0; dx++) {
            for (int dz = -radius; dz <= radius && budget > 0; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                String key = cx + "," + cz;
                // v2 补采：旧区块（缺 surfaceBlockMatrix）即使本会话采集过，也要重采一次
                boolean needSurface = c.needsSurfaceRecapture(cx, cz);
                if (captured.contains(key) && !needSurface) {
                    continue;
                }
                captured.add(key);
                budget--;
                captureOne(level, cx, cz);
            }
        }
    }

    private void captureOne(Object level, int chunkX, int chunkZ) {
        GisClient c = client;
        boolean wantSurface = c.config().captureSurfaceBlocks();
        ChunkSampler.Result result = ChunkSampler.sample(level, chunkX, chunkZ,
                c.config().sampleStep(), c.config().uploadBlockHistogram(), wantSurface);
        if (!result.ok()) {
            String countKey = result.error();
            int count = failures.merge(countKey, 1, Integer::sum);
            if (count == 1 || count % 50 == 0) {
                GisLog.warn(TAG, "区块采样失败(" + chunkX + "," + chunkZ + "): "
                        + result.error() + " （累计 " + count + " 次）");
            }
            return;
        }
        ChunkSampler.Capture capture = result.capture();
        for (String warning : capture.warnings()) {
            if (failures.merge(warning, 1, Integer::sum) == 1) {
                GisLog.debug(TAG, "采样告警: " + warning);
            }
        }
        byte[] payload = ChunkBodyCodec.encodeBody(capture.data());
        ChunkKey key = new ChunkKey(c.session().worldId(), c.session().dimension(), chunkX, chunkZ);
        GisStore.ChunkRow row = new GisStore.ChunkRow(key, ChunkState.CAPTURED, payload,
                capture.data().sampleStep(), capture.data().sampleSize(),
                capture.data().minY(), capture.data().maxY(), capture.data().flags(),
                System.currentTimeMillis(), 0, null, 0);
        GisStore.ChunkLocal local = new GisStore.ChunkLocal(key.worldId(), key.dimension(),
                chunkX, chunkZ, capture.structuresJson(), capture.blockEntitiesJson(),
                capture.blockPaletteJson());
        c.submitDb(() -> {
            c.store().putChunk(row);
            c.store().putChunkLocal(local);
            c.incrementCaptured();
            // 补采完成后从待补采集合移除（上传由 Uploader 自动接管）
            c.markSurfaceRecaptured(chunkX, chunkZ);
        });
    }

    private static int blockCoord(Minecraft mc, String method) {
        Object value = McReflect.call(mc.player, new String[]{method});
        return value instanceof Number num ? num.intValue() : 0;
    }

    /** 清空去重集合（清缓存后重新采集）。 */
    public void reset() {
        captured.clear();
        failures.clear();
        sessionKey = "";
    }

    public void shutdown() {
        captured.clear();
        failures.clear();
    }

    /** 已采集去重的 chunk 数（调试信息用）。 */
    public int capturedCount() {
        return captured.size();
    }

    public Iterator<String> recentFailures() {
        return failures.keySet().iterator();
    }
}
