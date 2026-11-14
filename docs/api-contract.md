# GIS 客户端 ↔ xuanjian.top 契约对应实现

契约来源（**生产已上线**）：`GET https://xuanjian.top/api/gis/client/contract`（contract:1）。
客户端严格按此实现，不自行改协议；服务端尚未上线的部分用本地 Mock 服务覆盖（见 `docs/testing.md`）。

生产环境现状（2026-11-14 核对）：`/api/gis/worlds` 返回 world `id=1, worldKey=yl-main, serverKey=yl,
serverName=樱梨`，三个维度 chunkCount=0（无地形数据）。**本地集成测试不得向生产灌数据**，
只在 mock/临时库验证。

## 认证

- `Authorization: Bearer <OAuth2 access_token>`；OAuth2 **PKCE 公共客户端**
  `/api/oauth/authorize` → `/api/oauth/token`，scope `read:user read:member`
- 权限：`gis.access / gis.submit / gis.road.edit / gis.district.edit / gis.object.review / gis.admin`，
  全部由服务端判定；客户端不维护管理员名单
- access_token 2h、refresh_token 30d 一次性轮换（`PkceOAuth` 刷新后立刻写回）
- 实测 `GET /api/gis/client/bootstrap` 无 Bearer 返回 **401** → 未登录时客户端进入本地模式
  （采集/HUD/叠加可用，不上传）
- `X-GIS-Server-Token`：服务端确认**客户端不需要**；配置项仅作可选（默认空、默认不发），
  上传一律用 `Authorization: Bearer <access_token>`，不作为上传必要条件

## 世界归档 / 无 ACTIVE 世界时的本地模式（生产已确认会发生）

站方会把旧世界（`yl-main`、`樱梨-main`）置为 `ARCHIVED`、`is_current=0`，真实服务器重新注册前
生产 bootstrap 可能**没有 ACTIVE 世界**。客户端行为：

| 场景 | 客户端行为 |
| --- | --- |
| bootstrap 有 ACTIVE 世界 | 正常绑定 → 采集 + 上传 + 同步 |
| bootstrap 全部 ARCHIVED / 无世界 | 进入**本地模式**：采集、HUD、Overlay 全部可用；上传队列暂停，UI 显示「服务器世界已归档（无 ACTIVE 世界），上传暂停；官方恢复后自动继续」 |
| resolve-world 返回 `world_archived` (409) | 同上，本地模式；该错误按永久错误处理，不无限重试 |
| `server_not_registered` / `world_not_registered` / `unauthorized` | 本地模式，显示对应原因 |
| 恢复 ACTIVE 世界 | 下次 bootstrap（退避最长 5 分钟）、手动「立即同步/重新绑定」、或重进世界后自动恢复上传 |

避免接口轰炸：绑定失败时按失败次数退避（60s→最多 300s 一次），手动刷新会重置退避；
上传与同步只在 `session.canUpload()` 时执行，本地模式下完全不发请求。

## 世界绑定

| 契约 | 客户端实现 |
| --- | --- |
| `GET /api/gis/client/bootstrap?serverKey=` | `HttpGisApi.bootstrap` → `BootstrapInfo`；解析 `dimensions[]` 对象形式（生产格式 `{dimension,chunkCount,bbox,updatedAt}`） |
| `POST /api/gis/client/resolve-world` | `HttpGisApi.resolveWorld`；`server_not_registered/world_not_registered/world_archived/dimension_mismatch` → 本地模式并显示原因 |
| 未注册服务器上传 403 | `WorldSession.canUpload()` false → 上传引擎静默跳过 |
| 客户端不能指定他人世界 | worldId/worldKey 只来自 bootstrap + resolve-world 校验结果 |

## 上传（增量 + 断点续传 + 幂等）

1. `POST /api/gis/client/chunks/plan {worldId,dimension,chunks:[{x,z}]}`（≤ `planChunksMax`=4096）
   → `have`/`missing`；`have` 直接标记 UPLOADED
2. `POST /api/gis/client/chunks?worldId=&dimension=`
   - `Content-Type: application/octet-stream`
   - `Content-Encoding: zstd`（默认；契约允许 `zstd|gzip|deflate|identity`；zstd 用**纯 Java**
     aircompressor 实现，无 native 依赖，失败自动降级 deflate）
   - `Idempotency-Key`：`xj`+sha1(worldId|dimension|batchSeq|chunkHash) 前 30 位，符合
     `^[A-Za-z0-9_-]{8,64}$`，重试复用
   - `X-GIS-Contract: 1`
   - 限制：压缩后 ≤ `maxCompressedBytes`=4MiB、解压后 ≤ `maxDecompressedBytes`=8MiB、
     单批 ≤ `maxChunkBatch`=64、`sampleSize` ≤ `maxSampleSize`=32、`uploadRatePerMinute`=60
3. 二进制格式（**little endian**，与生产契约逐字段一致，v2）：

```
magic "XJGIS"(5) | version u8=1 | dimensionCode u8 | chunkCount u8 | worldId u32 | batchSeq u64
per chunk:
  chunkX i32, chunkZ i32, sampleStep u8(1..4), sampleSize u8(=16/sampleStep),
  minY i16, maxY i16,
  flags u8(bit0 height / bit1 biome / bit2 blockHist / bit3 surfaceBlockMatrix)
  heightMatrix i16[n*n]        （-32768 = 无数据，客户端全程保留缺口，绝不填 0）
  [biomeMatrix u16[n*n]]       （0xFFFF = 未知）
  [blockHist: u8 n + n*(u16 blockId, u32 count)]
  [surfaceBlockMatrix u16[n*n]]（v2 可选，紧跟 blockHist 之后；0xFFFF = 无数据/空列）
```

### v2 地表方块矩阵（surfaceBlockMatrix）

用途：浏览器端实时渲染「Minecraft 方块级彩色地图」（服务端只出数据 + palette，不生成图片）。

- **已确认向量（服务端 v2 对齐）**：grass_block=0xD637(54839)、water=0x23E4(9188)、
  stone=0x3214(12820)、sand=0xFC48(64584)、oak_planks=0x0782(1922)；
  标准自检 CRC32("123456789")==0xCBF43926；服务端 GET /api/gis/map/palette 的 locks
  键即合法 hash16（lockNames 可反查），可作 CI 对账依据。
- **hash16 算法**：`CRC32(方块资源名) & 0xFFFF`，例如
  `CRC32("minecraft:grass_block") & 0xFFFF == 54839`；与站点 `GET /api/gis/map/palette`
  的 `blocks[hash16]` 使用同一算法（Windows .NET/Java 均验证过一致性）。
- **地表方块定义**：该列**顶层非空气方块**；若顶层是水，记录**水方块本身**（不是水下方块）；
  无数据/空列记 `0xFFFF`。
- **采样网格**：与 `heightMatrix` 完全一致（相同 `sampleStep/sampleSize`，逐列一次方块读取，
  复用 blockHist 已读取的方块对象，渲染线程外无额外开销）。
- **开关**：`capture.surfaceBlocks`（当前**默认 false**）。等服务端 `decodeBatch` v2 + palette
  上线并回报后，把默认值改为 true 即可（一行配置变更，无需发版逻辑改动）。
- **旧区块补传**：本地缓存中 `flags & 8 == 0` 的旧区块会被标记待补采；玩家靠近时客户端
  重采并重传（服务端按 `(worldId,dimension,chunkX,chunkZ)` upsert 覆盖为 v2），用户无需重新跑图。
  生产 `hetao-main`（id=3）已有 51 个旧格式 chunk，服务端上线后由该机制自动补传。
- **兼容**：不带 bit3 的旧载荷服务端照常接收，网页端用「高度 + 生物群系」兜底着色；客户端解析
  也同时兼容 v1/v2 两种载荷（`ChunkBodyCodec` 有旧格式回归测试）。

4. dimensionCode（生产契约明确）：`overworld=0, nether=1, end=2, custom=3`
5. 幂等：同 `(worldId,dimension,chunkX,chunkZ)` upsert；同 Idempotency-Key 重放 → 客户端按成功处理
6. 重试：1s/2s/4s…上限 60s；429 读 `retryAfter`；网络恢复按 `next_attempt_at` 续传；
   失败只改状态、不删本地数据
7. 本地缓存 `chunks.flags` 同步记录 bit3，用于判断「已上传但缺 surface」并补采重传

## 查询

| 契约 | 客户端实现 |
| --- | --- |
| `GET /api/gis/client/sync?since=` | 对象 upsert + tombstone 删除 + cursor 落库 |
| `GET /api/gis/objects?bbox=…` | 客户端前置校验 bbox（必填、面积 ≤16,000,000），`limit` ≤ `maxObjectsPerPage`=1000 |
| `GET /api/gis/terrain/coverage` | 数据状态页展示覆盖度与缺口；不把未采集区域当平地 |

## 数据模型对齐

- Marker 分类按生产契约：`public_building / housing / shop / industry / agriculture /
  transport / entertainment / government / other`（兼容旧 `residential`）
- 道路用 LineString + 宽度属性做缓冲区判定（不用中心点半径）
- 城区用 Polygon + Point-in-Polygon
- 只同步 Published Revision（服务端负责审核/版本）

## 错误处理

`ApiException` 保留 `code/httpStatus/retryAfter/chunkIndex`：
`unauthorized` → 尝试刷新 token 失败则本地模式；`forbidden(required)` → UI 显示缺的权限；
`rate_limited` → 按 retryAfter 暂停令牌桶；`server_not_registered/world_*` → 永久失败不再重试；
`invalid_payload(chunkIndex)` → 批次永久失败、本地保留。统一响应 `{ok:false,error,message}`。

## 与服务端后续对齐事项（不阻塞当前实现）

1. `bootstrap` 需要 OAuth；世界选择 UI 已提示「未登录 → 本地模式」。
2. `X-GIS-Server-Token` 的用途/下发方式待服务端说明（客户端已支持可选配置）。
3. 生产 `mcVersion` 为空；客户端绑定世界时以 `bootstrap.worlds[].mcVersion` 展示，不做校验阻断。
