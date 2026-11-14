# GIS 客户端 ↔ xuanjian.top 契约对应实现

契约由 Lead 定稿（机器可读版 `GET /api/gis/client/contract`），客户端**严格按此实现，不自行改协议**。
所有可配置项在 `config/xuanjianmap.properties`（见 `GisConfig`）。

## 认证

- `Authorization: Bearer <OAuth2 access_token>`
- 复用现有 OAuth2 **PKCE 公共客户端**：`/api/oauth/authorize` → `/api/oauth/token`
- scope：`read:user read:member`（不新增 scope）
- GIS 写权限由服务端 `gis.submit` 判定；**客户端没有管理员名单**
- access_token 2h、refresh_token 30d 一次性轮换（`PkceOAuth` 刷新后立刻写回新 refresh）
- token 存放：`config/xuanjianmap-oauth.properties`（权限 600，绝不入库）

## 世界绑定

| 契约 | 客户端实现 |
| --- | --- |
| `GET /api/gis/client/bootstrap?serverKey=` | `HttpGisApi.bootstrap` → `BootstrapInfo`（worlds/limits/sync） |
| `POST /api/gis/client/resolve-world` | `HttpGisApi.resolveWorld`；失败码 `server_not_registered` / `world_not_registered` / `world_archived` / `dimension_mismatch` 全部映射为本地「未绑定/禁止上传」状态并显示原因 |
| 未注册服务器上传 403 | `WorldSession.canUpload()` 为 false 时上传引擎直接静默跳过（本地功能照常） |

serverKey 默认 `yl`，可在设置页修改。

## 上传

1. `POST /api/gis/client/chunks/plan`（≤4096）→ `have` / `missing`；`have` 直接标记 UPLOADED
2. `POST /api/gis/client/chunks?worldId=&dimension=`：
   - `Content-Type: application/octet-stream`
   - `Content-Encoding: deflate`（契约允许 zstd/gzip/deflate/identity；客户端选 JDK 原生 deflate，零 native 依赖）
   - `Idempotency-Key`：`xj` + sha1(worldId|dimension|batchSeq|chunkHash) 前 30 位，满足 `[A-Za-z0-9_-]{8,64}`，重试复用
   - `X-GIS-Contract: 1`
   - 限制（客户端前置校验）：压缩后 ≤4MiB、解压后 ≤8MiB、单批 1–64、sampleSize ≤32、60 次/分钟
3. 二进制格式（LE）严格按契约：

```
"XJGIS"(5) | version u8=1 | dimensionCode u8 | chunkCount u8 | worldId u32 | batchSeq u64
per chunk: chunkX i32, chunkZ i32, sampleStep u8, sampleSize u8, minY i16, maxY i16,
           flags u8(bit0 高度/bit1 biome/bit2 blockHist),
           heightMatrix i16[n*n]（-32768 = 无数据，客户端保留缺口，绝不填 0）,
           [biomeMatrix u16[n*n]]（0xFFFF = 未知）,
           [blockHist]
```
4. 幂等：同 `(worldId,dimension,chunkX,chunkZ)` upsert + 同 Idempotency-Key 重放 → 客户端按成功处理
5. 重试：1s/2s/4s…上限 60s；429 读 `retryAfter`；网络恢复后由 `next_attempt_at` 续传

## 查询

| 契约 | 客户端实现 |
| --- | --- |
| `GET /api/gis/objects?bbox=…` | `HttpGisApi.objects`（limit 夹在 1..200；只取 Published） |
| `GET /api/gis/client/sync?since=` | `HttpGisApi.sync` → 对象 upsert + tombstone 标记删除 + cursor 落库 |
| `GET /api/gis/terrain/coverage` | `HttpGisApi.coverage`（数据状态页展示覆盖度；缺失不伪造） |

## 待服务端确认的 3 个实现细节（客户端已按保守方案实现）

1. **dimensionCode 映射**：契约二进制头只有 `dimensionCode u8`，未给出映射表。
   客户端采用 `overworld=0 / the_nether=1 / the_end=2 / 其他=255`。
2. **biomeMatrix 语义**：u16 无 palette 段，客户端写入 Minecraft 注册表数字 ID
   （世界记录带 mcVersion，服务端可按版本还原）；若服务端期望字符串调色板方案，需要新增 palette 段。
3. **blockHist 字节布局**：契约标记为可选但未定义布局。客户端默认 **不上传**
   （`upload.blockHistogram=false`），本地仍采集方块类型用于自有渲染。
   若服务端确认，建议布局：`u8 layoutVersion, u8 paletteCount, [u8 len + UTF-8 blockId], u16[n*n] 下标(0xFFFF=未知)`。

这三项不阻塞上传主链路（bit1/bit2 可关闭），但请服务端在实现时明确，以便打开完整数据。

## 错误处理

`ApiException` 保留 `code/httpStatus/retryAfter/chunkIndex`：

- `unauthorized`：尝试刷新 token，失败则进入「未登录」本地模式
- `forbidden(required)`：UI 显示缺少的权限名（权限判断永远以服务端为准）
- `rate_limited`：按 retryAfter 暂停令牌桶
- `server_not_registered/world_*`：永久失败，不再重试，并提示世界绑定问题
- `invalid_payload(chunkIndex)`：批次永久失败，本地数据保留，便于排查
