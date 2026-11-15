package top.xuanjian.map.core.storage;

import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.model.WorldStatus;
import top.xuanjian.map.core.util.GisLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 本地缓存实现（规范第八节：索引/元数据/对象/上传状态；原始区块数据是压缩二进制 blob）。
 *
 * <p>单连接 + 专用 DB 线程：SQLite 写操作串行化最简单也最稳；WAL 提升读写并发。
 * 数据体（chunk payload）以 BLOB 存，压缩在调用方（{@code BlobCodec}）完成，存储层只负责字节。
 */
public final class SqliteGisStore implements GisStore {

    private static final String TAG = "sqlite";
    private static final int SCHEMA_VERSION = 1;

    private final Path dbFile;
    private Connection connection;

    public SqliteGisStore(Path dbFile) {
        this.dbFile = dbFile;
    }

    @Override
    public void init() throws Exception {
        Files.createDirectories(dbFile.getParent());
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("缺少 org.xerial:sqlite-jdbc 依赖（应随模组 jar 内嵌）", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS meta (
                      k TEXT PRIMARY KEY,
                      v TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chunks (
                      world_id INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      chunk_x INTEGER NOT NULL,
                      chunk_z INTEGER NOT NULL,
                      state TEXT NOT NULL,
                      payload BLOB NOT NULL,
                      sample_step INTEGER NOT NULL,
                      sample_size INTEGER NOT NULL,
                      min_y INTEGER NOT NULL,
                      max_y INTEGER NOT NULL,
                      flags INTEGER NOT NULL,
                      captured_at INTEGER NOT NULL,
                      attempts INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT,
                      next_attempt_at INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (world_id, dimension, chunk_x, chunk_z)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_state ON chunks(state)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chunk_locals (
                      world_id INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      chunk_x INTEGER NOT NULL,
                      chunk_z INTEGER NOT NULL,
                      structures_json TEXT NOT NULL,
                      block_entities_json TEXT NOT NULL,
                      block_palette_json TEXT NOT NULL,
                      PRIMARY KEY (world_id, dimension, chunk_x, chunk_z)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS upload_jobs (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      world_id INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      batch_seq INTEGER NOT NULL,
                      idempotency_key TEXT NOT NULL UNIQUE,
                      payload BLOB NOT NULL,
                      chunk_count INTEGER NOT NULL,
                      chunk_keys TEXT NOT NULL,
                      state TEXT NOT NULL,
                      attempts INTEGER NOT NULL DEFAULT 0,
                      next_attempt_at INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_state ON upload_jobs(state)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS gis_objects (
                      world_id INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      object_id TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      name TEXT,
                      revision INTEGER NOT NULL,
                      geometry_json TEXT NOT NULL,
                      props_json TEXT NOT NULL,
                      min_x REAL NOT NULL,
                      min_z REAL NOT NULL,
                      max_x REAL NOT NULL,
                      max_z REAL NOT NULL,
                      updated_at INTEGER NOT NULL,
                      deleted INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (world_id, dimension, object_id)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_objects_kind ON gis_objects(kind)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sync_cursor (
                      world_id INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      cursor TEXT NOT NULL,
                      updated_at INTEGER NOT NULL,
                      PRIMARY KEY (world_id, dimension)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS worlds (
                      world_id INTEGER PRIMARY KEY,
                      server_key TEXT NOT NULL,
                      world_key TEXT NOT NULL,
                      status TEXT NOT NULL,
                      is_current INTEGER NOT NULL,
                      mc_version TEXT,
                      dimensions TEXT,
                      updated_at INTEGER NOT NULL
                    )""");
            st.execute("INSERT OR REPLACE INTO meta(k, v) VALUES('schema_version', '"
                    + SCHEMA_VERSION + "')");
        }
        GisLog.info(TAG, "SQLite 缓存就绪: " + dbFile.toAbsolutePath());
    }

    @Override
    public String backend() {
        return "sqlite";
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                GisLog.warn(TAG, "关闭 SQLite 连接失败", e);
            }
            connection = null;
        }
    }

    /* ==================== chunks ==================== */

    @Override
    public void putChunk(ChunkRow row) {
        String sql = """
                INSERT INTO chunks(world_id, dimension, chunk_x, chunk_z, state, payload,
                  sample_step, sample_size, min_y, max_y, flags, captured_at, attempts,
                  last_error, next_attempt_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(world_id, dimension, chunk_x, chunk_z) DO UPDATE SET
                  payload=excluded.payload,
                  sample_step=excluded.sample_step,
                  sample_size=excluded.sample_size,
                  min_y=excluded.min_y,
                  max_y=excluded.max_y,
                  flags=excluded.flags,
                  captured_at=excluded.captured_at,
                  state=CASE WHEN chunks.state='UPLOADED'
                       AND (excluded.flags & 8) = (chunks.flags & 8)
                      THEN 'UPLOADED' ELSE excluded.state END
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ChunkKey k = row.key();
            ps.setLong(1, k.worldId());
            ps.setString(2, k.dimension());
            ps.setInt(3, k.chunkX());
            ps.setInt(4, k.chunkZ());
            ps.setString(5, row.state().name());
            ps.setBytes(6, row.payload());
            ps.setInt(7, row.sampleStep());
            ps.setInt(8, row.sampleSize());
            ps.setInt(9, row.minY());
            ps.setInt(10, row.maxY());
            ps.setInt(11, row.flags());
            ps.setLong(12, row.capturedAt());
            ps.setInt(13, row.attempts());
            ps.setString(14, row.lastError());
            ps.setLong(15, row.nextAttemptAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("写入 chunk 失败 " + row.key(), e);
        }
    }

    @Override
    public ChunkRow getChunk(ChunkKey key) {
        String sql = "SELECT * FROM chunks WHERE world_id=? AND dimension=? AND chunk_x=? AND chunk_z=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, key.worldId());
            ps.setString(2, key.dimension());
            ps.setInt(3, key.chunkX());
            ps.setInt(4, key.chunkZ());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readChunkRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new StoreException("读取 chunk 失败 " + key, e);
        }
    }

    @Override
    public List<PendingChunk> pendingChunks(long worldId, String dimension, int limit) {
        return pendingChunks(worldId, dimension, limit, true);
    }

    @Override
    public List<PendingChunk> pendingChunks(long worldId, String dimension, int limit, boolean includeFailed) {
        String states = includeFailed ? "('CAPTURED','QUEUED','FAILED')" : "('CAPTURED','QUEUED')";
        String sql = "SELECT world_id, dimension, chunk_x, chunk_z, payload, sample_step, sample_size,"
                + " min_y, max_y, flags, attempts FROM chunks"
                + " WHERE world_id=? AND dimension=? AND state IN " + states
                + " ORDER BY chunk_x, chunk_z LIMIT ?";
        List<PendingChunk> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PendingChunk(
                            new ChunkKey(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                            rs.getBytes(5), rs.getInt(6), rs.getInt(7),
                            (short) rs.getInt(8), (short) rs.getInt(9), rs.getInt(10), rs.getInt(11)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("查询待上传 chunk 失败", e);
        }
        return out;
    }

    @Override
    public ChunkStats chunkStats() {
        String sql = """
                SELECT COUNT(*),
                       SUM(CASE WHEN state='CAPTURED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='QUEUED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='UPLOADED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),
                       COALESCE(SUM(LENGTH(payload)), 0)
                FROM chunks
                """;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new ChunkStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6));
            }
        } catch (SQLException e) {
            throw new StoreException("统计 chunk 失败", e);
        }
        return ChunkStats.EMPTY;
    }

    @Override
    public void setChunkState(List<ChunkKey> keys, ChunkState state, long nextAttemptAt, String error) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        String sql = "UPDATE chunks SET state=?, next_attempt_at=?, last_error=?,"
                + " attempts=attempts+1 WHERE world_id=? AND dimension=? AND chunk_x=? AND chunk_z=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (ChunkKey k : keys) {
                ps.setString(1, state.name());
                ps.setLong(2, nextAttemptAt);
                ps.setString(3, error);
                ps.setLong(4, k.worldId());
                ps.setString(5, k.dimension());
                ps.setInt(6, k.chunkX());
                ps.setInt(7, k.chunkZ());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new StoreException("更新 chunk 状态失败", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public void deleteChunks(long worldId, String dimension) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM chunks WHERE world_id=? AND dimension=?")) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("删除 chunk 失败", e);
        }
    }

    @Override
    public List<int[]> chunksNeedingSurface(long worldId, String dimension, int limit) {
        String sql = "SELECT chunk_x, chunk_z FROM chunks"
                + " WHERE world_id=? AND dimension=? AND (flags & 8)=0"
                + " AND state IN ('CAPTURED','QUEUED','UPLOADED')"
                + " ORDER BY captured_at DESC, chunk_x, chunk_z LIMIT ?";
        List<int[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new int[]{rs.getInt(1), rs.getInt(2)});
                }
            }
        } catch (SQLException e) {
            throw new StoreException("查询待补采区块失败", e);
        }
        return out;
    }
    @Override
    public List<int[]> chunksForSurfaceRefresh(long worldId, String dimension, int limit) {
        String sql = "SELECT chunk_x, chunk_z FROM chunks"
                + " WHERE world_id=? AND dimension=? AND state IN ('CAPTURED','QUEUED','UPLOADED')"
                + " ORDER BY captured_at DESC, chunk_x, chunk_z LIMIT ?";
        List<int[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new int[]{rs.getInt(1), rs.getInt(2)});
                }
            }
        } catch (SQLException e) {
            throw new StoreException("查询待重采区块失败", e);
        }
        return out;
    }

    @Override
    public void requeueChunk(ChunkKey key) {
        String sql = "UPDATE chunks SET state='CAPTURED', next_attempt_at=0, attempts=0, last_error=NULL "
                + "WHERE world_id=? AND dimension=? AND chunk_x=? AND chunk_z=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, key.worldId());
            ps.setString(2, key.dimension());
            ps.setInt(3, key.chunkX());
            ps.setInt(4, key.chunkZ());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("重新排队区块失败", e);
        }
    }
    /* ==================== chunk locals（仅本地，不上传） ==================== */

    @Override
    public void putChunkLocal(ChunkLocal local) {
        String sql = """
                INSERT INTO chunk_locals(world_id, dimension, chunk_x, chunk_z,
                  structures_json, block_entities_json, block_palette_json)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(world_id, dimension, chunk_x, chunk_z) DO UPDATE SET
                  structures_json=excluded.structures_json,
                  block_entities_json=excluded.block_entities_json,
                  block_palette_json=excluded.block_palette_json
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, local.worldId());
            ps.setString(2, local.dimension());
            ps.setInt(3, local.chunkX());
            ps.setInt(4, local.chunkZ());
            ps.setString(5, local.structuresJson());
            ps.setString(6, local.blockEntitiesJson());
            ps.setString(7, local.blockPaletteJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("写入区块本地扩展失败", e);
        }
    }

    @Override
    public List<ChunkLocal> loadChunkLocals(long worldId, String dimension, int limit) {
        String sql = "SELECT * FROM chunk_locals WHERE world_id=? AND dimension=?"
                + " ORDER BY chunk_x, chunk_z LIMIT ?";
        List<ChunkLocal> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChunkLocal(rs.getLong("world_id"), rs.getString("dimension"),
                            rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                            rs.getString("structures_json"), rs.getString("block_entities_json"),
                            rs.getString("block_palette_json")));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读取区块本地扩展失败", e);
        }
        return out;
    }

    /* ==================== upload_jobs ==================== */


    @Override
    public long insertJob(JobRow job) {
        String sql = """
                INSERT INTO upload_jobs(world_id, dimension, batch_seq, idempotency_key, payload,
                  chunk_count, chunk_keys, state, attempts, next_attempt_at, last_error,
                  created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, job.worldId());
            ps.setString(2, job.dimension());
            ps.setLong(3, job.batchSeq());
            ps.setString(4, job.idempotencyKey());
            ps.setBytes(5, job.payload());
            ps.setInt(6, job.chunkCount());
            ps.setString(7, job.chunkKeys());
            ps.setString(8, job.state().name());
            ps.setInt(9, job.attempts());
            ps.setLong(10, job.nextAttemptAt());
            ps.setString(11, job.lastError());
            ps.setLong(12, job.createdAt());
            ps.setLong(13, job.updatedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new StoreException("创建上传任务失败", e);
        }
        throw new StoreException("创建上传任务未返回自增 ID");
    }

    @Override
    public List<JobRow> jobsByState(JobState state, int limit) {
        String sql = "SELECT * FROM upload_jobs WHERE state=? ORDER BY id LIMIT ?";
        List<JobRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readJobRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("查询上传任务失败", e);
        }
        return out;
    }

    @Override
    public void updateJob(long id, JobState state, int attempts, long nextAttemptAt,
                          String error, long updatedAt) {
        String sql = "UPDATE upload_jobs SET state=?, attempts=?, next_attempt_at=?, last_error=?,"
                + " updated_at=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setInt(2, attempts);
            ps.setLong(3, nextAttemptAt);
            ps.setString(4, error);
            ps.setLong(5, updatedAt);
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("更新上传任务失败 id=" + id, e);
        }
    }

    @Override
    public void deleteJobsByState(JobState state) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM upload_jobs WHERE state=?")) {
            ps.setString(1, state.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("删除上传任务失败", e);
        }
    }

    @Override
    public JobStats jobStats() {
        String sql = """
                SELECT SUM(CASE WHEN state='PENDING' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='IN_FLIGHT' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='RETRY' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='DONE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END)
                FROM upload_jobs
                """;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new JobStats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5));
            }
        } catch (SQLException e) {
            throw new StoreException("统计上传任务失败", e);
        }
        return JobStats.EMPTY;
    }

    /* ==================== objects ==================== */

    @Override
    public void upsertObjects(List<StoredObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO gis_objects(world_id, dimension, object_id, kind, name, revision,
                  geometry_json, props_json, min_x, min_z, max_x, max_z, updated_at, deleted)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                ON CONFLICT(world_id, dimension, object_id) DO UPDATE SET
                  kind=excluded.kind, name=excluded.name, revision=excluded.revision,
                  geometry_json=excluded.geometry_json, props_json=excluded.props_json,
                  min_x=excluded.min_x, min_z=excluded.min_z, max_x=excluded.max_x,
                  max_z=excluded.max_z, updated_at=excluded.updated_at, deleted=0
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (StoredObject o : objects) {
                ps.setLong(1, o.worldId());
                ps.setString(2, o.dimension());
                ps.setString(3, o.objectId());
                ps.setString(4, o.kind());
                ps.setString(5, o.name());
                ps.setInt(6, o.revision());
                ps.setString(7, o.geometryJson());
                ps.setString(8, o.propsJson());
                ps.setDouble(9, o.minX());
                ps.setDouble(10, o.minZ());
                ps.setDouble(11, o.maxX());
                ps.setDouble(12, o.maxZ());
                ps.setLong(13, o.updatedAt());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new StoreException("写入 GIS 对象失败", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public void markObjectsDeleted(long worldId, String dimension, String objectId, int revision) {
        String sql = "UPDATE gis_objects SET deleted=1, revision=?, updated_at=? "
                + "WHERE world_id=? AND dimension=? AND object_id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, revision);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, worldId);
            ps.setString(4, dimension);
            ps.setString(5, objectId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("标记 GIS 对象删除失败 id=" + objectId, e);
        }
    }

    @Override
    public List<StoredObject> loadObjects(long worldId, String dimension, boolean includeDeleted) {
        String sql = "SELECT * FROM gis_objects WHERE world_id=? AND dimension=?"
                + (includeDeleted ? "" : " AND deleted=0") + " ORDER BY kind, object_id";
        List<StoredObject> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredObject(
                            rs.getLong("world_id"), rs.getString("dimension"),
                            rs.getString("object_id"), rs.getString("kind"), rs.getString("name"),
                            rs.getInt("revision"), rs.getString("geometry_json"),
                            rs.getString("props_json"), rs.getDouble("min_x"), rs.getDouble("min_z"),
                            rs.getDouble("max_x"), rs.getDouble("max_z"),
                            rs.getLong("updated_at"), rs.getInt("deleted") != 0));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读取 GIS 对象失败", e);
        }
        return out;
    }

    @Override
    public String syncCursor(long worldId, String dimension) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cursor FROM sync_cursor WHERE world_id=? AND dimension=?")) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            throw new StoreException("读取 sync 游标失败", e);
        }
    }

    @Override
    public void setSyncCursor(long worldId, String dimension, String cursor) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO sync_cursor(world_id, dimension, cursor, updated_at)"
                        + " VALUES(?,?,?,?)")) {
            ps.setLong(1, worldId);
            ps.setString(2, dimension);
            ps.setString(3, cursor == null ? "" : cursor);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("写入 sync 游标失败", e);
        }
    }

    /* ==================== worlds ==================== */

    @Override
    public void putWorlds(String serverKey, List<RegisteredWorld> worlds) {
        String sql = "INSERT OR REPLACE INTO worlds(world_id, server_key, world_key, status,"
                + " is_current, mc_version, dimensions, updated_at) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (RegisteredWorld w : worlds) {
                ps.setLong(1, w.id());
                ps.setString(2, serverKey);
                ps.setString(3, w.worldKey());
                ps.setString(4, w.status().name());
                ps.setInt(5, w.current() ? 1 : 0);
                ps.setString(6, w.mcVersion());
                ps.setString(7, String.join(",", w.dimensions()));
                ps.setLong(8, System.currentTimeMillis());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new StoreException("写入世界注册信息失败", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public List<RegisteredWorld> worlds() {
        List<RegisteredWorld> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM worlds ORDER BY is_current DESC, world_id")) {
            while (rs.next()) {
                String dims = rs.getString("dimensions");
                List<String> dimensions = dims == null || dims.isBlank()
                        ? List.of() : List.of(dims.split(","));
                out.add(new RegisteredWorld(
                        rs.getLong("world_id"), rs.getString("world_key"),
                        WorldStatus.fromWire(rs.getString("status")),
                        rs.getInt("is_current") != 0, rs.getString("mc_version"), dimensions));
            }
        } catch (SQLException e) {
            throw new StoreException("读取世界注册信息失败", e);
        }
        return out;
    }

    /* ==================== meta ==================== */

    @Override
    public long nextBatchSeq(long worldId, String dimension) {
        String key = "batch_seq:" + worldId + ":" + dimension;
        String current = meta(key, "0");
        long next;
        try {
            next = Long.parseLong(current) + 1;
        } catch (NumberFormatException e) {
            next = 1;
        }
        setMeta(key, String.valueOf(next));
        return next;
    }

    @Override
    public String meta(String key, String def) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        } catch (SQLException e) {
            throw new StoreException("读取 meta 失败 key=" + key, e);
        }
    }

    @Override
    public void setMeta(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO meta(k, v) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("写入 meta 失败 key=" + key, e);
        }
    }

    @Override
    public long lastUploadAt() {
        try {
            return Long.parseLong(meta("last_upload_at", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void setLastUploadAt(long millis) {
        setMeta("last_upload_at", String.valueOf(millis));
    }

    /* ==================== 维护 ==================== */

    @Override
    public long dbBytes() {
        long total = 0;
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path p = Path.of(dbFile.toAbsolutePath() + suffix);
            try {
                if (Files.exists(p)) {
                    total += Files.size(p);
                }
            } catch (Exception ignored) {
                // 统计失败不影响功能
            }
        }
        return total;
    }

    @Override
    public void clearCache() {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM chunks");
            st.executeUpdate("DELETE FROM chunk_locals");
            st.executeUpdate("DELETE FROM upload_jobs");
            st.executeUpdate("DELETE FROM gis_objects");
            st.executeUpdate("DELETE FROM sync_cursor");
            st.execute("VACUUM");
        } catch (SQLException e) {
            throw new StoreException("清理缓存失败", e);
        }
        GisLog.info(TAG, "本地 GIS 缓存已清理");
    }

    /* ==================== 内部 ==================== */

    private ChunkRow readChunkRow(ResultSet rs) throws SQLException {
        return new ChunkRow(
                new ChunkKey(rs.getLong("world_id"), rs.getString("dimension"),
                        rs.getInt("chunk_x"), rs.getInt("chunk_z")),
                ChunkState.fromWire(rs.getString("state")),
                rs.getBytes("payload"),
                rs.getInt("sample_step"), rs.getInt("sample_size"),
                (short) rs.getInt("min_y"), (short) rs.getInt("max_y"),
                rs.getInt("flags"), rs.getLong("captured_at"),
                rs.getInt("attempts"), rs.getString("last_error"), rs.getLong("next_attempt_at"));
    }

    private JobRow readJobRow(ResultSet rs) throws SQLException {
        return new JobRow(
                rs.getLong("id"), rs.getLong("world_id"), rs.getString("dimension"),
                rs.getLong("batch_seq"), rs.getString("idempotency_key"), rs.getBytes("payload"),
                rs.getInt("chunk_count"), rs.getString("chunk_keys"),
                JobState.fromWire(rs.getString("state")), rs.getInt("attempts"),
                rs.getLong("next_attempt_at"), rs.getString("last_error"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 已是自动提交或连接已断
        }
    }

    private void setAutoCommitQuietly(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // 忽略
        }
    }
}
