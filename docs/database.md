# 本地缓存（SQLite）与二进制格式

缓存目录：`<game dir>/config/xuanjianmap/gis-cache.db`（默认，见 `GisClient`）。
若 SQLite native 不可用，自动降级为内存存储，并在「数据状态」页显示 `backend=memory`。

## 表结构（schema v1）

```sql
meta(k TEXT PRIMARY KEY, v TEXT)                 -- schema_version / batch_seq / last_upload_at
chunks(                                          -- 本地采集的区块
  world_id INTEGER, dimension TEXT, chunk_x INTEGER, chunk_z INTEGER,   -- PK
  state TEXT,                                    -- CAPTURED / QUEUED / UPLOADED / FAILED
  payload BLOB,                                  -- 契约 chunk body 字节（未压缩）
  sample_step, sample_size, min_y, max_y, flags INTEGER,
  captured_at INTEGER, attempts INTEGER, last_error TEXT, next_attempt_at INTEGER
)
upload_jobs(                                     -- 上传批次（幂等键唯一）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  world_id, dimension, batch_seq, idempotency_key TEXT UNIQUE,
  payload BLOB,                                  -- XJGIS 批次字节（未压缩，发送前 deflate）
  chunk_count INTEGER, chunk_keys TEXT,          -- "x,z;x,z"
  state TEXT,                                    -- PENDING/IN_FLIGHT/RETRY/DONE/FAILED
  attempts, next_attempt_at, last_error, created_at, updated_at
)
gis_objects(                                     -- 服务端 Published 对象缓存
  world_id, dimension, object_id, kind, name, revision,
  geometry_json TEXT, props_json TEXT,
  min_x, min_z, max_x, max_z REAL, updated_at, deleted INTEGER,
  PRIMARY KEY(world_id, dimension, object_id)
)
sync_cursor(world_id, dimension, cursor, updated_at)   -- 增量 sync 游标
worlds(world_id PK, server_key, world_key, status, is_current, mc_version, dimensions, updated_at)
```

索引：`idx_chunks_state(state)`、`idx_jobs_state(state)`、`idx_objects_kind(kind)`。
PRAGMA：`journal_mode=WAL`、`synchronous=NORMAL`、`busy_timeout=5000`。

## 为什么 payload 存「未压缩」

1. 上传前每次发送都要重新压缩（deflate 很快），避免持久化编解码版本不一致；
2. 断点续传时 payload 已是最终字节，直接重试；
3. 压缩后尺寸由 `BlobCodec` 与契约 4MiB 上限在发送路径统一校验。

## 缺口语义

`heightMatrix` 的 `-32768` 表示**该采样点无数据**，客户端采集、存储、上传全程保留，
绝不填 0（规范第十四节：不能把未采集区域当成平坦地形）。`biomeMatrix` 的 `0xFFFF` 同理。

## 清理策略

- 上传成功：`chunks.state=UPLOADED`（payload 保留，供本地地图/复传）
- 用户点「清理缓存」：删除 chunks/upload_jobs/gis_objects/sync_cursor 并 VACUUM（世界注册信息保留）
- 从未自动删除失败数据
