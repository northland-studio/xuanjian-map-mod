-- 玄剑 GIS 客户端本地缓存 schema v1（SQLite）
-- 说明：模组启动时由 SqliteGisStore#init 自动建表（IF NOT EXISTS），本文件是等价参考脚本，
--       便于 DBA / 排障 / 后续迁移审阅。运行时 DB：<game dir>/config/xuanjianmap/gis-cache.db
-- 版本记录：meta.schema_version = 1

PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=5000;

CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

-- 本地采集的区块（payload = 契约 chunk 段字节，未压缩；-32768 缺口原样保留）
CREATE TABLE IF NOT EXISTS chunks (
  world_id INTEGER NOT NULL,
  dimension TEXT NOT NULL,
  chunk_x INTEGER NOT NULL,
  chunk_z INTEGER NOT NULL,
  state TEXT NOT NULL,                 -- CAPTURED / QUEUED / UPLOADED / FAILED
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
);
CREATE INDEX IF NOT EXISTS idx_chunks_state ON chunks(state);

-- 区块本地扩展：结构 / block entity / 方块调色板（仅本地，不上传）
CREATE TABLE IF NOT EXISTS chunk_locals (
  world_id INTEGER NOT NULL,
  dimension TEXT NOT NULL,
  chunk_x INTEGER NOT NULL,
  chunk_z INTEGER NOT NULL,
  structures_json TEXT NOT NULL,
  block_entities_json TEXT NOT NULL,
  block_palette_json TEXT NOT NULL,
  PRIMARY KEY (world_id, dimension, chunk_x, chunk_z)
);

-- 上传批次（payload = XJGIS 批次字节，未压缩；发送前 zstd 压缩）
CREATE TABLE IF NOT EXISTS upload_jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  world_id INTEGER NOT NULL,
  dimension TEXT NOT NULL,
  batch_seq INTEGER NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE,
  payload BLOB NOT NULL,
  chunk_count INTEGER NOT NULL,
  chunk_keys TEXT NOT NULL,            -- "x,z;x,z"
  state TEXT NOT NULL,                 -- PENDING / IN_FLIGHT / RETRY / DONE / FAILED
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jobs_state ON upload_jobs(state);

-- 服务端 Published Revision 对象缓存（tombstone 用 deleted=1）
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
);
CREATE INDEX IF NOT EXISTS idx_objects_kind ON gis_objects(kind);

-- 增量同步游标
CREATE TABLE IF NOT EXISTS sync_cursor (
  world_id INTEGER NOT NULL,
  dimension TEXT NOT NULL,
  cursor TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (world_id, dimension)
);

-- 世界注册信息（bootstrap 结果缓存）
CREATE TABLE IF NOT EXISTS worlds (
  world_id INTEGER PRIMARY KEY,
  server_key TEXT NOT NULL,
  world_key TEXT NOT NULL,
  status TEXT NOT NULL,
  is_current INTEGER NOT NULL,
  mc_version TEXT,
  dimensions TEXT,
  updated_at INTEGER NOT NULL
);

INSERT OR REPLACE INTO meta(k, v) VALUES('schema_version', '1');
