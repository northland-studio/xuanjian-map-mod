# 测试与 CI（全部在 GitHub Actions）

> 硬性规范：本地禁止执行任何 Gradle 命令（`./gradlew`、`gradle build/test/runClient` 等，
> 也不得通过 IDE/脚本/子进程间接调用）。所有构建、单元测试、GameTest、客户端启动与截图
> 只能通过 GitHub Actions 执行。Gradle Wrapper 保留在仓库中供 CI 使用。

## 1. 工作流

| 工作流 | 分支 | 内容 | Artifact |
| --- | --- | --- | --- |
| `build-26.yml` | `26` | JDK 25 + Gradle 9.5.1（wrapper）→ `./gradlew build`（编译 + 单元测试 + mock 契约测试） | jar、测试报告（junit xml + html）、构建日志 |
| `gui-gametest-26.yml` | `26` | xvfb 虚拟显示 → `runClientGameTest` → 真实启动客户端、建测试世界、采集/缓存/HUD/叠加、逐页截图 | 截图、gametest 日志、测试报告 |
| `build-121.yml` | `1.21` | JDK 21 + `fabric-loom-remap` 同上 | jar、测试报告 |
| `gui-gametest-121.yml` | `1.21` | 同上 | 截图、日志 |

## 2. 单元测试覆盖（`src/test/java`）

| 测试 | 覆盖点 |
| --- | --- |
| `GeoMathTest` | Point-in-Polygon（含凹多边形）、点到线段/折线距离、道路宽度缓冲区 |
| `GisLocatorTest` | 城区选择（最小包含多边形）、道路缓冲判定、图层过滤、附近 Marker 排序、5000 对象下定位性能 |
| `HudTextTest` | **无道路时严格 `当前城区 · 无道路 · X/Y/Z`**、展开态四要素、进入城区/道路提示 |
| `ChunkBatchCodecTest` | XJGIS 头/小端布局、`-32768` 缺口保留、biome 0xFFFF、坏包拒绝、压缩收益 |
| `UploaderTest` | 增量只传 missing、幂等键复用、指数退避、429 Retry-After、永久失败保留数据、未绑定世界不上传、手动重试 |
| `HttpGisApiMockTest` | **mock 服务端契约集成测试**：真实 HTTP 往返，校验 Bearer/`X-GIS-Contract`/`Content-Encoding`/`Idempotency-Key` 字符集、二进制可被服务端按契约解出（含 blockHist/-32768 缺口）、重复幂等重放 `duplicate:true`、错误码映射（429/403+required/400+chunkIndex/409 world_archived）、**无 ACTIVE 世界（全部 ARCHIVED）→ 客户端本地模式**、sync 对象+tombstone+游标、bbox/coverage |
| `DimensionsTest` | 维度归一化、生产契约 code（overworld0/nether1/end2/custom3）、`ResourceKey.toString()` 清洗（HUD 维度 bug 回归） |
| `SqliteGisStoreTest` | SQLite CRUD/统计/任务状态/对象与 tombstone/cursor/世界注册/清缓存、降级到内存存储 |
| `GisConfigTest` | 默认值、保存/读取、范围夹取、坏配置回退、重置 |
| `PkceOAuthTest` | RFC 7636 官方测试向量、verifier 字符集、authorize URL 参数、token 过期判断 |

## 3. 客户端 gametest（截图验收）

`src/gametest/java/top/xuanjian/map/gametest/GisClientGameTest.java`：

1. 把 `apiBase` 指向不可达地址 → 进入**本地模式**（CI 不依赖 xuanjian.top）；
2. 打开 GIS 主界面，断言有 widget，截图 `1-gis-overview-empty`（空数据态）；
3. 逐页切换并截图：`2-layers`、`3-worlds`、`4-data-status`、`5-settings`；
4. 打开自有 Overlay 地图截图 `6-overlay-map`（Xaero 不在测试环境，用自有渲染方案验收）；
5. HUD 预览截图 `7-hud-preview`（含展开态与「无道路」紧凑态）；
6. **真实创建单人测试世界**：等待区块渲染 → 断言采集 chunk 数 > 0（落 SQLite）、
   维度解析为 overworld、会话处于本地模式（CI 无生产 API）且上传文案含「暂停」；
7. 注入本地 mock GIS 对象（district/road/marker）→ 断言 HUD 识别城区（Point-in-Polygon）
   与道路（宽度缓冲区）→ 截图 `8-inworld-hud`（游戏内真实 HUD + 位置变化提示）、
   `9-inworld-overlay`（世界内自有 Overlay 图层）、`10-inworld-data-status`；
   另在设置页截图 `5b-settings-oauth-unlogged`（未登录 OAuth 按钮）与
   `5c-settings-oauth-link`（点「登录 OAuth」：链接复制到剪贴板 + 提示；断言 URL 含 PKCE 参数）；
8. 断言每张截图不是纯色（采样色数 ≥ 8）、结束时回到标题界面、截图数 ≥ 12。

缺数据的语义在 gametest 与单元测试中都被保护：未加载区块/高度不可用 → 不可上传伪造数据。
世界归档/无 ACTIVE 世界的行为在 mock 契约测试与 gametest 双重覆盖（本地模式不崩溃、不轰炸接口）。

## 4. 失败排查

- 构建失败：artifact `xuanjianmap-26-build-log`（含关键错误行尾随打印）
- gametest 失败：artifact `gis-gametest-logs-26`（gametest.log + run/logs + 报告），
  并会在 job 摘要里打印 `error:|gametest]|AssertionError|FAILED` 关键行
- 截图：artifact `gis-gametest-screenshots-26`

## 5. 本地允许/禁止

允许：编辑源码、静态代码检查（不触发 Gradle）、git、读取 Actions 日志与 artifact、看截图。
禁止：任何 Gradle 调用（含 IDE/脚本/子进程包装）。
