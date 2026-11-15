package top.xuanjian.map.core.storage;

import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.model.RegisteredWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存存储：用于单元测试，以及 SQLite 不可用（native 解压失败等）时的降级，
 * 保证模组仍能启动、HUD/地图仍能读服务端对象，但本地区块缓存不落盘。
 */
public final class MemoryGisStore implements GisStore {

    private final Map<ChunkKey, ChunkRow> chunks = new LinkedHashMap<>();
    private final Map<ChunkKey, ChunkLocal> chunkLocals = new LinkedHashMap<>();
    private final Map<Long, JobRow> jobs = new LinkedHashMap<>();
    private final Map<String, StoredObject> objects = new LinkedHashMap<>();
    private final Map<String, String> cursors = new LinkedHashMap<>();
    private final Map<Long, RegisteredWorld> worlds = new LinkedHashMap<>();
    private final Map<String, String> meta = new LinkedHashMap<>();
    private final AtomicLong jobIds = new AtomicLong(0);
    private long lastUploadAt;

    @Override
    public void init() {
        // 无需初始化
    }

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public void close() {
        // 无资源
    }

    @Override
    public synchronized void putChunk(ChunkRow row) {
        ChunkRow existing = chunks.get(row.key());
        boolean sameSurfaceFlag = existing != null
                && ((existing.flags() & 8) == (row.flags() & 8));
        if (existing != null && existing.state() == ChunkState.UPLOADED && sameSurfaceFlag) {
            chunks.put(row.key(), new ChunkRow(row.key(), ChunkState.UPLOADED, row.payload(),
                    row.sampleStep(), row.sampleSize(), row.minY(), row.maxY(), row.flags(),
                    row.capturedAt(), existing.attempts(), existing.lastError(), existing.nextAttemptAt()));
            return;
        }
        chunks.put(row.key(), row);
    }

    @Override
    public synchronized ChunkRow getChunk(ChunkKey key) {
        return chunks.get(key);
    }

    @Override
    public List<PendingChunk> pendingChunks(long worldId, String dimension, int limit) {
        return pendingChunks(worldId, dimension, limit, true);
    }

    @Override
    public synchronized List<PendingChunk> pendingChunks(long worldId, String dimension, int limit,
                                                         boolean includeFailed) {
        List<PendingChunk> out = new ArrayList<>();
        chunks.values().stream()
                .filter(r -> r.key().worldId() == worldId && r.key().dimension().equals(dimension))
                .filter(r -> r.state() == ChunkState.CAPTURED || r.state() == ChunkState.QUEUED
                        || (includeFailed && r.state() == ChunkState.FAILED))
                .sorted(Comparator.comparingInt((ChunkRow r) -> r.key().chunkX())
                        .thenComparingInt(r -> r.key().chunkZ()))
                .limit(Math.max(0, limit))
                .forEach(r -> out.add(new PendingChunk(r.key(), r.payload(), r.sampleStep(),
                        r.sampleSize(), r.minY(), r.maxY(), r.flags(), r.attempts())));
        return out;
    }

    @Override
    public synchronized ChunkStats chunkStats() {
        long captured = 0;
        long queued = 0;
        long uploaded = 0;
        long failed = 0;
        long bytes = 0;
        for (ChunkRow r : chunks.values()) {
            switch (r.state()) {
                case CAPTURED -> captured++;
                case QUEUED -> queued++;
                case UPLOADED -> uploaded++;
                case FAILED -> failed++;
            }
            bytes += r.payload().length;
        }
        return new ChunkStats(chunks.size(), captured, queued, uploaded, failed, bytes);
    }

    @Override
    public synchronized void setChunkState(List<ChunkKey> keys, ChunkState state,
                                           long nextAttemptAt, String error) {
        for (ChunkKey k : keys) {
            ChunkRow row = chunks.get(k);
            if (row == null) {
                continue;
            }
            chunks.put(k, new ChunkRow(row.key(), state, row.payload(), row.sampleStep(),
                    row.sampleSize(), row.minY(), row.maxY(), row.flags(), row.capturedAt(),
                    row.attempts() + 1, error, nextAttemptAt));
        }
    }

    @Override
    public synchronized void deleteChunks(long worldId, String dimension) {
        chunks.keySet().removeIf(k -> k.worldId() == worldId && k.dimension().equals(dimension));
    }

    @Override
    public synchronized List<int[]> chunksNeedingSurface(long worldId, String dimension, int limit) {
        List<int[]> out = new ArrayList<>();
        for (ChunkRow row : chunks.values()) {
            if (row.key().worldId() != worldId || !row.key().dimension().equals(dimension)) {
                continue;
            }
            if ((row.flags() & 8) != 0) {
                continue;
            }
            if (row.state() == ChunkState.FAILED) {
                continue;
            }
            out.add(new int[]{row.key().chunkX(), row.key().chunkZ()});
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return out;
    }
    @Override
    public synchronized List<int[]> chunksForSurfaceRefresh(long worldId, String dimension, int limit) {
        List<int[]> out = new ArrayList<>();
        for (ChunkRow row : chunks.values()) {
            if (row.key().worldId() != worldId || !row.key().dimension().equals(dimension)) {
                continue;
            }
            if (row.state() == ChunkState.FAILED) {
                continue;
            }
            out.add(new int[]{row.key().chunkX(), row.key().chunkZ()});
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return out;
    }

    @Override
    public synchronized void requeueChunk(ChunkKey key) {
        ChunkRow existing = chunks.get(key);
        if (existing != null) {
            chunks.put(key, new ChunkRow(existing.key(), ChunkState.CAPTURED, existing.payload(),
                    existing.sampleStep(), existing.sampleSize(), existing.minY(), existing.maxY(),
                    existing.flags(), existing.capturedAt(), existing.attempts(),
                    existing.lastError(), 0L));
        }
    }
    @Override
    public synchronized void putChunkLocal(ChunkLocal local) {
        chunkLocals.put(new ChunkKey(local.worldId(), local.dimension(), local.chunkX(), local.chunkZ()),
                local);
    }

    @Override
    public synchronized List<ChunkLocal> loadChunkLocals(long worldId, String dimension, int limit) {
        List<ChunkLocal> out = new ArrayList<>();
        for (ChunkLocal local : chunkLocals.values()) {
            if (local.worldId() == worldId && local.dimension().equals(dimension)
                    && out.size() < limit) {
                out.add(local);
            }
        }
        return out;
    }

    @Override
    public synchronized long insertJob(JobRow job) {
        long id = jobIds.incrementAndGet();
        jobs.put(id, new JobRow(id, job.worldId(), job.dimension(), job.batchSeq(),
                job.idempotencyKey(), job.payload(), job.chunkCount(), job.chunkKeys(), job.state(),
                job.attempts(), job.nextAttemptAt(), job.lastError(), job.createdAt(), job.updatedAt()));
        return id;
    }

    @Override
    public synchronized List<JobRow> jobsByState(JobState state, int limit) {
        List<JobRow> out = new ArrayList<>();
        for (JobRow row : jobs.values()) {
            if (row.state() == state && out.size() < limit) {
                out.add(row);
            }
        }
        return out;
    }

    @Override
    public synchronized void updateJob(long id, JobState state, int attempts, long nextAttemptAt,
                                       String error, long updatedAt) {
        JobRow row = jobs.get(id);
        if (row == null) {
            return;
        }
        jobs.put(id, new JobRow(id, row.worldId(), row.dimension(), row.batchSeq(),
                row.idempotencyKey(), row.payload(), row.chunkCount(), row.chunkKeys(), state,
                attempts, nextAttemptAt, error, row.createdAt(), updatedAt));
    }

    @Override
    public synchronized void deleteJobsByState(JobState state) {
        jobs.values().removeIf(r -> r.state() == state);
    }

    @Override
    public synchronized JobStats jobStats() {
        long pending = 0;
        long inFlight = 0;
        long retry = 0;
        long done = 0;
        long failed = 0;
        for (JobRow r : jobs.values()) {
            switch (r.state()) {
                case PENDING -> pending++;
                case IN_FLIGHT -> inFlight++;
                case RETRY -> retry++;
                case DONE -> done++;
                case FAILED -> failed++;
            }
        }
        return new JobStats(pending, inFlight, retry, done, failed);
    }

    @Override
    public synchronized void upsertObjects(List<StoredObject> list) {
        for (StoredObject o : list) {
            objects.put(key(o.worldId(), o.dimension(), o.objectId()), o);
        }
    }

    @Override
    public synchronized void markObjectsDeleted(long worldId, String dimension, String objectId,
                                                int revision) {
        String key = key(worldId, dimension, objectId);
        StoredObject o = objects.get(key);
        if (o == null) {
            return;
        }
        objects.put(key, new StoredObject(o.worldId(), o.dimension(), o.objectId(), o.kind(),
                o.name(), revision, o.geometryJson(), o.propsJson(), o.minX(), o.minZ(),
                o.maxX(), o.maxZ(), o.updatedAt(), true));
    }

    @Override
    public synchronized List<StoredObject> loadObjects(long worldId, String dimension,
                                                       boolean includeDeleted) {
        List<StoredObject> out = new ArrayList<>();
        for (StoredObject o : objects.values()) {
            if (o.worldId() == worldId && o.dimension().equals(dimension)
                    && (includeDeleted || !o.deleted())) {
                out.add(o);
            }
        }
        return out;
    }

    @Override
    public synchronized String syncCursor(long worldId, String dimension) {
        return cursors.getOrDefault(worldId + "|" + dimension, "");
    }

    @Override
    public synchronized void setSyncCursor(long worldId, String dimension, String cursor) {
        cursors.put(worldId + "|" + dimension, cursor == null ? "" : cursor);
    }

    @Override
    public synchronized void putWorlds(String serverKey, List<RegisteredWorld> list) {
        for (RegisteredWorld w : list) {
            worlds.put(w.id(), w);
        }
    }

    @Override
    public synchronized List<RegisteredWorld> worlds() {
        return new ArrayList<>(worlds.values());
    }

    @Override
    public synchronized long nextBatchSeq(long worldId, String dimension) {
        String key = "batch_seq:" + worldId + ":" + dimension;
        long next = 1;
        try {
            next = Long.parseLong(meta.getOrDefault(key, "0")) + 1;
        } catch (NumberFormatException ignored) {
            // 用 1
        }
        meta.put(key, String.valueOf(next));
        return next;
    }

    @Override
    public synchronized String meta(String key, String def) {
        return meta.getOrDefault(key, def);
    }

    @Override
    public synchronized void setMeta(String key, String value) {
        meta.put(key, value);
    }

    @Override
    public synchronized long lastUploadAt() {
        return lastUploadAt;
    }

    @Override
    public synchronized void setLastUploadAt(long millis) {
        this.lastUploadAt = millis;
    }

    @Override
    public long dbBytes() {
        return 0;
    }

    @Override
    public synchronized void clearCache() {
        chunks.clear();
        chunkLocals.clear();
        jobs.clear();
        objects.clear();
        cursors.clear();
    }

    private static String key(long worldId, String dimension, String objectId) {
        return worldId + "|" + dimension + "|" + objectId;
    }
}
