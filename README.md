# 玄剑 Map Mod · Xuanjian GIS 客户端

Minecraft **Fabric 客户端模组**，是玄剑世界地理信息系统（Xuanjian GIS）的官方客户端：
采集 Minecraft 原始区块基础地理数据 → 本地 SQLite 缓存 → 增量/幂等/可续传上传 → HUD 与地图叠加。

> 服务端（xuanjian.top）负责世界注册、数据合并、版本审核、空间查询与网页地图；
> 本仓库只实现**客户端**，不自建账号体系、不在模组内维护管理员名单。

## 双分支

| 分支 | Minecraft | Java | Loom | Fabric API | Gradle |
| --- | --- | --- | --- | --- | --- |
| `26` | 26.2 | 25 | `net.fabricmc.fabric-loom` 1.17-SNAPSHOT | 0.156.0+26.2 | 9.5.1 |
| `1.21` | 1.21.11 | 21 | `net.fabricmc.fabric-loom-remap` 1.17-SNAPSHOT | 0.141.3+1.21.11 | 9.5.1 |

版本严格沿用 `xuanjian-mod` 两条既有分支，不擅自升级；版本差异集中在
`client/.../compat/` 适配层与 `build.gradle`/`gradle.properties`。

## 功能

- **世界识别与绑定**：`GET /api/gis/client/bootstrap` → `POST /api/gis/client/resolve-world`；
  未注册服务器/世界只允许本地地图，禁止上传；客户端不能指定他人世界。
- **本地模式**：服务器/世界未注册、世界归档（ARCHIVED）、无 ACTIVE 世界或未登录 OAuth 时，
  采集/HUD/Overlay 照常可用，**上传与同步暂停并显示明确原因**；退避避免轰炸接口，
  恢复 ACTIVE 世界后自动继续上传。
- **本地区块采集**：chunk 坐标、高度矩阵、biome、方块类型直方图、结构（仅 Minecraft 原始数据，
  **不推断玩家建筑/道路/城区/商店**）；缺失高度保留 `-32768` 缺口。
- **本地缓存**：SQLite（索引/元数据/对象/上传状态）+ 压缩二进制 blob（**zstd**，纯 Java 实现，失败降级 deflate）。
- **上传队列**：plan 增量 → 二进制批次（`XJGIS` 格式）→ 幂等键、断点续传、指数退避、
  网络恢复续传、令牌桶限速、失败不丢数据；压缩/网络/重 SQLite 全部在后台线程。
- **HUD**：默认常驻，`=` 键展开/收起；显示当前城区/道路/X-Y-Z/Dimension；本地
  Point-in-Polygon 与道路宽度缓冲区判定（空间索引 + 节流，不每帧重算），不上传玩家实时位置。
- **地图叠加**：优先研究 Xaero 官方 API（结论见 `docs/xaero-integration.md`：目标版本无官方 API），
  因此提供自有 GIS Overlay Renderer（全图层世界地图 + HUD 附近对象小地图），不触碰 Xaero 缓存。
- **设置界面**：服务器/世界状态、上传开关与限速、缓存清理、图层开关、调试信息、OAuth 登录。

## 构建与测试（全部在 GitHub Actions）

本地**禁止**执行任何 Gradle 命令。CI 工作流：

- `.github/workflows/build-26.yml` / `build-121.yml`：编译 + 单元测试 + mock 契约集成测试 + jar/报告 artifact
- `.github/workflows/gui-gametest-26.yml` / `gui-gametest-121.yml`：
  xvfb 启动客户端 → 建测试世界 → 采集/缓存/HUD/叠加（注入本地 mock 对象）→ 逐页截图 artifact（10 张）

最近一次双版本全绿：
[26 Build](https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625114831) ·
[26 GameTest](https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625114826) ·
[1.21 Build](https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625151516) ·
[1.21 GameTest](https://github.com/northland-studio/xuanjian-map-mod/actions/runs/43625151522)

## 文档

- `docs/final-report.md`：**交付报告**（矩阵/Actions/Artifacts/功能/API 差异/本地模式/未完成项）
- `docs/architecture.md`：架构与线程模型
- `docs/api-contract.md`：与 xuanjian.top 的契约对应实现、本地模式行为
- `docs/database.md` + `docs/database/schema-v1.sql`：SQLite 表结构与 blob 格式
- `docs/xaero-integration.md`：Xaero 集成可行性结论
- `docs/testing.md`：CI 测试矩阵、截图清单、mock 覆盖
- `docs/install.md`：安装与使用说明

## 许可

MIT © Northland Studio
