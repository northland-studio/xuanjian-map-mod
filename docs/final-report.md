# 玄剑 Map Mod · 开发报告（阶段交付）

- 仓库：https://github.com/northland-studio/xuanjian-map-mod （PUBLIC，默认分支 `26`）
- 服务端契约来源：`GET https://xuanjian.top/api/gis/client/contract`（contract:1）
- 本报告对应提交：`26` 分支 `dbd6e0f` 之后、`1.21` 分支 `a29a270` 之后（mock 对象注入 + 文档）
- **本机未执行任何 Gradle 命令**：全部构建/单元测试/GameTest/GUI 截图在 GitHub Actions 完成

## 1. 双版本矩阵（全部 Actions 绿）

| 分支 | Minecraft | Java | Loom | Fabric API | Gradle | Build | Client GameTest |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `26` | 26.2 | 25 | `net.fabricmc.fabric-loom` 1.17-SNAPSHOT | 0.156.0+26.2 | 9.5.1（wrapper） | ✅ | ✅ 12 张截图 |
| `1.21` | 1.21.11 | 21 | `net.fabricmc.fabric-loom-remap` 1.17-SNAPSHOT | 0.141.3+1.21.11 | 9.5.1（wrapper） | ✅ | ✅ 12 张截图 |

版本未做任何“顺手升级”，与 `xuanjian-mod` 现有两条分支一致；版本差异集中在
`src/client/.../compat|gui|hud` 适配层，`core/`、`net/`、采集、上传、SQLite、zstd 两分支完全共享。

### Actions 运行链接（最近一次全绿）

- 26 / Build：https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625114831
- 26 / 客户端 gametest：https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625114826
- 1.21 / Build：https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625151516
- 1.21 / 客户端 gametest：https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625151522

## 2. Artifacts 清单

| 工作流 | Artifact | 内容 |
| --- | --- | --- |
| build-26 | `xuanjianmap-26-jars` | 模组 jar（含 sqlite-jdbc/aircompressor jar-in-jar） |
| build-26 | `xuanjianmap-26-test-reports` | JUnit XML + HTML 报告 |
| build-26 | `xuanjianmap-26-build-log` | 完整构建日志（失败时含关键错误行） |
| gui-gametest-26 | `gis-gametest-screenshots-26` | **12 张 PNG 截图** |
| gui-gametest-26 | `gis-gametest-logs-26` | gametest.log + run/logs + 报告 |
| build-121 | `xuanjianmap-121-jars` / `-test-reports` / `-build-log` | 同上（1.21） |
| gui-gametest-121 | `gis-gametest-screenshots-121` / `-logs-121` | 同上（1.21） |

截图（两分支一致，`build/run/clientGameTest/screenshots/`）：
`1-gis-overview-empty`（空数据态）/ `2-layers` / `3-worlds` / `4-data-status` / `5-settings` /
`6-overlay-map`（自有叠加渲染）/ `7-hud-preview`（HUD 展开+无道路紧凑态）/
**`5b-settings-oauth-unlogged`（设置页 OAuth 未登录按钮）** /
**`5c-settings-oauth-link`（点「登录 OAuth」后链接已复制到剪贴板 + 操作提示）** /
**`8-inworld-hud`（真实单人世界内 HUD）** / **`9-inworld-overlay`（世界内 Overlay 图层）** / `10-inworld-data-status`。

`8-inworld-hud` 中可见：城区=青云城区、道路=中央大道、坐标、维度=主世界(overworld)、附近 Marker、
上传「暂停（本地模式）」原因，以及聊天栏「进入 青云城区 · 中央大道」位置变化提示；
`9-inworld-overlay` 中可见：城区多边形填充、按真实宽度绘制道路、Marker、玩家标记与图层统计。

## 3. 客户端功能清单

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| World ID / Server ID 绑定校验 | ✅ | bootstrap → resolve-world；客户端不能指定他人世界；未注册/归档 → 本地模式 |
| 本地区块采集 | ✅ | chunk 坐标、高度矩阵、biome、表面方块直方图、结构、block entity（只采 Minecraft 原生数据，**不推断建筑/道路/城区/商店**） |
| 地表方块矩阵（v2，方块彩色地图） | ✅ | 每列顶层非空气方块 → hash16 = CRC32(资源名)&0xFFFF（水面记录水本身），flags bit3 追加 surfaceBlockMatrix u16[n*n]；capture.surfaceBlocks 当前默认关闭（服务端 decodeBatch v2 上线后改默认值一行开启）；旧区块自动标记补采并同 key upsert 重传 |
| 采集正确性保护 | ✅ | 未加载/接口不可用 → `-32768` 缺口保留，不伪造、不上传 |
| SQLite 本地缓存 | ✅ | chunks / upload_jobs / gis_objects / sync_cursor / worlds / chunk_locals，WAL，后台单线程 |
| 压缩二进制 blob | ✅ | 契约 `XJGIS` v1 小端格式；上传用 **zstd**（纯 Java aircompressor，jar-in-jar），失败自动降级 deflate |
| 上传队列 | ✅ | plan 增量、单批 ≤64、压缩后 ≤4MiB/解压 ≤8MiB 前置校验、幂等键复用、指数退避 1s→60s、429 Retry-After、断网续传、失败不丢数据、令牌桶限速 60/min |
| 增量同步 | ✅ | `sync?since=` 对象 upsert + tombstone 删除 + cursor 落库；失败时仍用本地缓存 |
| HUD | ✅ | 默认常驻；`=` 展开/收起；城区/道路/X-Y-Z/Dimension；无道路严格 `当前城区 · 无道路 · X/Y/Z`；进入新区/道路本地提示；不上传实时位置；空间索引 + 250ms 节流 |
| 自有 Overlay Renderer | ✅ | 城区多边形 / 道路（真实宽度）/ Marker / 其他；图层开关；按钮缩放居中；独立于 Xaero |
| GIS 设置页 | ✅ | API/服务器 Key、上传与限速、采集间隔/半径/步长、HUD、图层开关、缓存清理、重试上传、OAuth 登录/退出、调试 |
| OAuth2 PKCE | ✅ | **游戏内闭环**：设置页「登录 OAuth」自动复制授权链接到剪贴板 → 浏览器登录拿 code →「完成登录（读取剪贴板 code）」；code 容错（纯 code/code=/回调 URL）；错误中文化（invalid_grant 等）；昵称显示与持久化；refresh 一次性轮换；RFC 7636 向量单测 |
| 权限 | ✅ | 客户端不做管理员名单；写权限全部由服务端 `gis.submit` 判定，403 显示缺少的权限名 |

## 4. 契约对齐与 API 差异（以生产 contract 为准）

| 项 | 生产契约 | 客户端实现 |
| --- | --- | --- |
| dimensionCode | overworld 0 / nether 1 / end 2 / custom 3 | 一致（`Dimensions.code/fromCode`） |
| sampleStep | 1..4，sampleSize=16/step | 一致（采集归一化 1/2/4） |
| blockHist | `u8 n + n*(u16 blockId,u32 count)` | 一致（默认开启；单测覆盖 123456789 这种大 count 的 u32） |
| 缺口语义 | `-32768` 保留、biome `0xFFFF` | 一致（采集/存储/上传/测试全链路保留） |
| limits | planChunksMax / maxChunkBatch / maxCompressedBytes / maxDecompressedBytes / uploadRatePerMinute / maxObjectsPerPage | 解析兼容生产命名与转述命名 |
| worlds.dimensions | 对象数组 `{dimension,chunkCount,bbox,updatedAt}` | 兼容对象与字符串两种形式 |
| 认证头 | `Authorization: Bearer` + `X-GIS-Contract` | 一致；`X-GIS-Server-Token` 可选、默认不发（服务端确认不需要） |
| 错误码 | 401/403(+required)/400/409/413/429(retryAfter)/500… | 全部映射为 `ApiException`（永久/可重试/限速/鉴权分类） |
| 幂等 | 同 key 重放返回 duplicate:true | 客户端按成功处理，重试复用同一 key |

## 5. 本地模式（世界归档 / 无 ACTIVE 世界）

站方已确认可能把世界全部归档。客户端行为（mock 测试 + gametest 双重覆盖）：

1. bootstrap 无 ACTIVE 世界 / 全部 ARCHIVED、resolve-world `world_archived(409)`、
   `server_not_registered`、`world_not_registered`、`unauthorized` → **本地模式**；
2. 本地模式下采集/HUD/Overlay 全部可用，上传/同步零请求，UI 明确显示原因
   （如「服务器世界已归档（无 ACTIVE 世界），上传暂停；官方恢复后自动继续」）；
3. 不崩溃；绑定失败按次数退避（60s 起，最长 300s 一次），避免轰炸接口；手动刷新重置退避；
4. 恢复 ACTIVE 世界后，下次 bootstrap / 手动「立即同步/重新绑定」/ 重进世界自动恢复上传。

## 6. Xaero 集成结论

目标版本 Xaero 无公开渲染 API，规范又禁止篡改其缓存或以其私有格式作为上传标准。
因此：**自有 GIS Overlay Renderer 兜底**（World Map 全图层 + HUD 附近对象小地图），
独立 Screen/HUD 元素，不注入 Xaero 管线、不读写其缓存；`XaeroCompat` 仅检测是否加载用于状态展示。
详见 `docs/xaero-integration.md`。CI 没有 Xaero 环境，截图验收的是自有渲染方案。

## 7. 测试

- 单元测试 + mock 契约集成测试：两分支均通过（JUnit 报告见 Artifacts）
  - 几何/空间索引/HUD 文案（含无道路规范格式）、XJGIS 编解码（小端/缺口/blockHist）、zstd/deflate、
    SQLite CRUD/对象/游标、上传器（增量/幂等/退避/429/永久失败保留数据/未绑定不上传）、
    config、PKCE、维度归一化与 ResourceKey 清洗
  - mock 服务端（`com.sun.net.httpserver`）：bootstrap/resolve/plan/upload（真实二进制往返）/sync/objects/coverage、
    错误码映射、**无 ACTIVE 世界**、**world_archived 409**
- 客户端 gametest（xvfb）：真实建测试世界 → 采集落库断言 → 维度 overworld 断言 → 本地模式与上传暂停断言 →
  9 张截图（非纯色断言）→ 回标题界面

## 8. 未完成事项与原因

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 真实 OAuth 登录 + 生产上传联调 | 未做 | 需要浏览器完成 PKCE；生产世界当前全部归档、且要求不灌测试数据；待世界恢复 ACTIVE 后做一次端到端联调 |
| 服务端接收 blockHist 的验收 | 未验证 | 客户端按生产 contract 实现并单测；服务端接口尚无数据可验证（不向生产灌数据） |
| Web GIS 编辑器 / 地形分析 / 历史地图 / Wiki / 集市 | 不在本仓库 | 属 xuanjian.top 侧（另由 gis-platform/前端负责）；客户端只消费 Published Revision 与 terrain/coverage |
| Overlay 拖拽平移 | 未做 | 首版用按钮居中/缩放，避免与 Xaero 交互冲突；后续可加受控拖拽 |
| 1.21 与 26 截图逐像素比对 | 未做 | 两版本渲染 API 不同，采用「程序断言 + 人工看图」验收；如需视觉回归可加 template 对比 |
| GameTest 真实 Xaero 环境 | 未做 | 无 Xaero 依赖且其许可/稳定性不适合作为 CI 依赖 |

## 9. 本地开发约束（已遵守）

- 本机未运行 `./gradlew` / `gradle` / IDE 构建 / 子进程 Gradle；
- Gradle Wrapper 已入库，CI 用 `./gradlew`；
- 允许的操作仅：编辑源码、静态检查、git、读取 Actions 日志与 Artifacts、看截图。
