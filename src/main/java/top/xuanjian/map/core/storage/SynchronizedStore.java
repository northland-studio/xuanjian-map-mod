package top.xuanjian.map.core.storage;

import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.model.RegisteredWorld;

import java.util.List;

/**
 * 串行化存储代理：SQLite 单连接不能被多线程同时使用（采集线程/上传线程/同步线程都会访问）。
 * 所有方法在同一个锁上串行，保证「网络请求不在锁内、但存储操作绝不相交」。
 */
public final class SynchronizedStore implements GisStore {

    private final GisStore delegate;
    private final Object lock;

    public SynchronizedStore(GisStore delegate) {
        this(delegate, new Object());
    }

    public SynchronizedStore(GisStore delegate, Object lock) {
        this.delegate = delegate;
        this.lock = lock;
    }

    public GisStore delegate() {
        return delegate;
    }

    public Object lock() {
        return lock;
    }

    @Override
    public void init() throws Exception {
        synchronized (lock) {
            delegate.init();
        }
    }

    @Override
    public String backend() {
        synchronized (lock) {
            return delegate.backend();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            delegate.close();
        }
    }

    @Override
    public void putChunk(ChunkRow row) {
        synchronized (lock) {
            delegate.putChunk(row);
        }
    }

    @Override
    public ChunkRow getChunk(ChunkKey key) {
        synchronized (lock) {
            return delegate.getChunk(key);
        }
    }

    @Override
    public List<PendingChunk> pendingChunks(long worldId, String dimension, int limit) {
        synchronized (lock) {
            return delegate.pendingChunks(worldId, dimension, limit);
        }
    }

    @Override
    public List<PendingChunk> pendingChunks(long worldId, String dimension, int limit,
                                            boolean includeFailed) {
        synchronized (lock) {
            return delegate.pendingChunks(worldId, dimension, limit, includeFailed);
        }
    }

    @Override
    public ChunkStats chunkStats() {
        synchronized (lock) {
            return delegate.chunkStats();
        }
    }

    @Override
    public void setChunkState(List<ChunkKey> keys, ChunkState state, long nextAttemptAt, String error) {
        synchronized (lock) {
            delegate.setChunkState(keys, state, nextAttemptAt, error);
        }
    }

    @Override
    public void deleteChunks(long worldId, String dimension) {
        synchronized (lock) {
            delegate.deleteChunks(worldId, dimension);
        }
    }

    @Override
    public List<int[]> chunksNeedingSurface(long worldId, String dimension, int limit) {
        synchronized (lock) {
            return delegate.chunksNeedingSurface(worldId, dimension, limit);
        }
    }
    @Override
    public void putChunkLocal(ChunkLocal local) {
        synchronized (lock) {
            delegate.putChunkLocal(local);
        }
    }

    @Override
    public List<ChunkLocal> loadChunkLocals(long worldId, String dimension, int limit) {
        synchronized (lock) {
            return delegate.loadChunkLocals(worldId, dimension, limit);
        }
    }

    @Override
    public long insertJob(JobRow job) {
        synchronized (lock) {
            return delegate.insertJob(job);
        }
    }

    @Override
    public List<JobRow> jobsByState(JobState state, int limit) {
        synchronized (lock) {
            return delegate.jobsByState(state, limit);
        }
    }

    @Override
    public void updateJob(long id, JobState state, int attempts, long nextAttemptAt,
                          String error, long updatedAt) {
        synchronized (lock) {
            delegate.updateJob(id, state, attempts, nextAttemptAt, error, updatedAt);
        }
    }

    @Override
    public void deleteJobsByState(JobState state) {
        synchronized (lock) {
            delegate.deleteJobsByState(state);
        }
    }

    @Override
    public JobStats jobStats() {
        synchronized (lock) {
            return delegate.jobStats();
        }
    }

    @Override
    public void upsertObjects(List<StoredObject> objects) {
        synchronized (lock) {
            delegate.upsertObjects(objects);
        }
    }

    @Override
    public void markObjectsDeleted(long worldId, String dimension, String objectId, int revision) {
        synchronized (lock) {
            delegate.markObjectsDeleted(worldId, dimension, objectId, revision);
        }
    }

    @Override
    public List<StoredObject> loadObjects(long worldId, String dimension, boolean includeDeleted) {
        synchronized (lock) {
            return delegate.loadObjects(worldId, dimension, includeDeleted);
        }
    }

    @Override
    public String syncCursor(long worldId, String dimension) {
        synchronized (lock) {
            return delegate.syncCursor(worldId, dimension);
        }
    }

    @Override
    public void setSyncCursor(long worldId, String dimension, String cursor) {
        synchronized (lock) {
            delegate.setSyncCursor(worldId, dimension, cursor);
        }
    }

    @Override
    public void putWorlds(String serverKey, List<RegisteredWorld> worlds) {
        synchronized (lock) {
            delegate.putWorlds(serverKey, worlds);
        }
    }

    @Override
    public List<RegisteredWorld> worlds() {
        synchronized (lock) {
            return delegate.worlds();
        }
    }

    @Override
    public long nextBatchSeq(long worldId, String dimension) {
        synchronized (lock) {
            return delegate.nextBatchSeq(worldId, dimension);
        }
    }

    @Override
    public String meta(String key, String def) {
        synchronized (lock) {
            return delegate.meta(key, def);
        }
    }

    @Override
    public void setMeta(String key, String value) {
        synchronized (lock) {
            delegate.setMeta(key, value);
        }
    }

    @Override
    public long lastUploadAt() {
        synchronized (lock) {
            return delegate.lastUploadAt();
        }
    }

    @Override
    public void setLastUploadAt(long millis) {
        synchronized (lock) {
            delegate.setLastUploadAt(millis);
        }
    }

    @Override
    public long dbBytes() {
        synchronized (lock) {
            return delegate.dbBytes();
        }
    }

    @Override
    public void clearCache() {
        synchronized (lock) {
            delegate.clearCache();
        }
    }
}
