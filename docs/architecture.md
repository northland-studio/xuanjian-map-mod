# GIS 客户端架构

## 1. 分层

```
Minecraft 客户端（渲染线程）
  └─ client/            ← 版本相关：Screen/HUD/KeyMapping/区块读取（26 与 1.21 各自实现）
        ├─ gui/         GIS 主界面/图层/世界/数据/设置（截图验收对象）
        ├─ hud/         HudElementRegistry 元素、`=` 键切换、节流定位
        ├─ overlay/     自有 GIS 叠加渲染（世界地图全图层 + HUD 附近对象）
        ├─ capture/     区块采样（反射适配 MC 版本 API）
        └─ GisClient    客户端服务装配（配置/SQLite/上传/同步/定位）

core/（main source set，纯 Java，无 Minecraft 依赖，单元测试覆盖）
  ├─ config/    GisConfig（properties）
  ├─ model/     BBox / Geometry / GisObject / RegisteredWorld / Limits
  ├─ geo/       GeoMath（point-in-polygon、点到折线距离）、SpatialIndex、GisLocator
  ├─ hud/       HudText（HUD 文案与位置变化提示，纯函数）
  ├─ chunk/     ChunkData / ChunkBodyCodec / ChunkBatchCodec（XJGIS 二进制）
  ├─ compress/  BlobCodec：deflate / gzip / identity
  ├─ storage/   GisStore 接口 + SqliteGisStore + MemoryGisStore(降级/测试)
  ├─ upload/    Uploader / UploadPlanner / RetryPolicy / RateLimiter
  ├─ net/       GisApi 接口 + HttpGisApi + PkceOAuth + TokenStore + ObjectJson
  └─ world/     BootstrapInfo / WorldSession
```

核心层不依赖 Minecraft 的原因：

1. 单元测试不需要启动游戏（CI 快、稳定）；
2. 双版本（26 / 1.21）共享 90% 逻辑，版本差异只落在 `client/`；
3. 规范要求「网络/压缩/重 SQLite 不在主线程」——把逻辑放在线程无关的核心层更容易保证。

## 2. 线程模型

| 工作 | 线程 | 说明 |
| --- | --- | --- |
| 区块采样（读 `ClientLevel`） | 渲染线程，**限量预算** | 每 tick 最多采样 1–2 个 chunk，读完立刻把纯数据交出去 |
| 编码/压缩/写 SQLite | `xjm-db` 单线程 | 所有 `GisStore` 调用串行化，避免 SQLite 并发写 |
| 上传（plan/upload） | `xjm-upload` 单线程 | 令牌桶限速 + 退避调度；断网时按 nextAttemptAt 继续 |
| 增量同步（sync） | `xjm-sync` 单线程 | 按服务端建议间隔（默认 60s）拉取对象与 tombstone |
| HUD 定位 | 渲染线程，节流 250ms | 只查空间索引（少量候选）+ 精确几何；缓存上一次结果 |
| HUD 绘制 / 地图叠加 | 渲染线程 | 只读快照，不做 IO、不做网络 |

「失败不丢数据」：上传失败只改 `chunks.state` / `upload_jobs.state`，从不删除 payload；
清缓存只能由用户在 GIS → 数据状态页主动触发。

## 3. 数据链路

```
进入世界
  → 读取 serverKey/dimension（客户端侧） 
  → bootstrap（世界列表 + limits）
  → resolve-world（校验 serverKey/worldId/worldKey/dimension）
  → 绑定成功才允许上传；否则本地模式（HUD/叠加仍可用）
  → 采样 chunk → ChunkData → 契约 body 字节 → SQLite（blob）
  → Uploader.tick：plan(missing) → 批次（XJGIS）→ deflate → POST chunks（幂等键）
  → 成功：chunks=UPLOADED；失败：RETRY（退避）/ FAILED（保留数据，可手动重试）
  → 同步：sync(since) → 对象/tombstone → SQLite → GisLocator 重建空间索引
  → HUD/叠加层读 GisLocator
```

## 4. 为什么不用 Xaero 私有格式

见 `docs/xaero-integration.md`：目标版本 Xaero 无公开 API，且规范禁止篡改其缓存或以其私有
格式作为上传标准。因此采集只来自 Minecraft 原生数据，叠加层是自有渲染。

## 5. 双版本适配

| 差异点 | 26 | 1.21.11 |
| --- | --- | --- |
| Loom 插件 | `net.fabricmc.fabric-loom` | `net.fabricmc.fabric-loom-remap` |
| mappings | 无（loom 直连官方映射） | `loom.officialMojangMappings()` |
| Java | 25 | 21 |
| Screen 渲染 | `extractRenderState(GuiGraphicsExtractor,...)` | `render(GuiGraphics,...)` |
| 切屏 | `Minecraft.getInstance().gui.setScreen(...)` | `Minecraft.getInstance().setScreen(...)` |
| 聊天消息 | `player.sendSystemMessage(...)` | `player.displayClientMessage(..., false)` |
| 区块/生物群系读取 | 反射适配（方法名候选集） | 反射适配（同一套） |

区块读取刻意用反射适配层：MC 小版本经常重命名内部方法，反射 + 候选方法名 + 明确日志
可以在两个分支共用一套采样器，并把失败原因写进 CI 日志，而不是编译期直接炸。
