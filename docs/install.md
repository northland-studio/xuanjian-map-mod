# 安装与使用

## 1. 安装

1. 从 GitHub Actions 产物下载对应版本 jar：
   - MC 26.2：`xuanjianmap-26-jars`（Artifact）
   - MC 1.21.11：`xuanjianmap-121-jars`（Artifact）
2. 放入客户端 `mods/` 目录（这是**客户端模组**，服务器不需要安装）。
3. 需要 Fabric Loader ≥0.19.3 与对应 Fabric API（26.2: 0.156.0+26.2；1.21.11: 0.141.3+1.21.11）。
4. 首次启动会生成 `config/xuanjianmap.properties` 与本地缓存目录 `config/xuanjianmap/`。

## 2. 基础使用

| 操作 | 说明 |
| --- | --- |
| `=` | 展开/收起 GIS HUD（默认常驻） |
| `G` | 打开 GIS 界面（世界内） |
| GIS 界面 → 概览 | 世界/绑定状态、上传队列、缓存、同步、契约信息 |
| GIS 界面 → 图层 / 设置 | 图层开关、上传开关与限速、采集参数、HUD 开关、OAuth 登录 |
| GIS 界面 → 世界 | 已注册世界列表（需先登录 OAuth 才能 bootstrap） |
| GIS 界面 → 数据 | 区块/任务统计、重试上传、强制同步、清理缓存 |
| GIS 地图 | 城区/道路/Marker 叠加渲染（自有方案，兼容 Xaero 共存） |

## 3. 登录与上传

- 上传需要 xuanjian.top 统一账号 OAuth2 登录（PKCE）。在 GIS 设置页点「登录 OAuth」，
  浏览器打开日志里的授权链接，完成后把回调 code 填入（当前 CLI/日志方式），token 会安全存到
  `config/xuanjianmap-oauth.properties`（权限 600，不入库）。
- 未登录 / 世界未注册 / 世界归档时进入**本地模式**：HUD、地图、采集缓存都可用，**不上传**。
- 生产世界恢复 ACTIVE 后，客户端会自动恢复上传（或点「立即同步/重新绑定」立即恢复）。

## 4. 本地缓存与清理

- `config/xuanjianmap/gis-cache.db`（SQLite，WAL）。
- 上传失败不删除数据；在「数据」页可「重试上传」。
- 「清理缓存」会删除区块/任务/对象/游标，保留世界注册信息。

## 5. 配置项（`config/xuanjianmap.properties`）

| key | 默认 | 说明 |
| --- | --- | --- |
| `api.base` | `https://xuanjian.top` | GIS API 地址 |
| `gis.serverKey` | `yl` | 服务器 Key（由站方分配） |
| `capture.enabled` | true | 是否采集 |
| `capture.intervalTicks` | 100 | 采集轮询间隔（tick） |
| `capture.radiusChunks` | 2 | 采集半径（区块） |
| `capture.sampleStep` | 1 | 采样步长（契约 1..4） |
| `upload.enabled` | true | 是否上传 |
| `upload.ratePerMinute` | 60 | 上传限速（与服务端对齐） |
| `upload.batchChunks` | 32 | 单批 chunk 数（≤64） |
| `upload.contentEncoding` | zstd | zstd/gzip/deflate/identity |
| `upload.blockHistogram` | true | 是否上传方块类型直方图（契约已定义布局） |
| `hud.enabled` / `hud.expanded` | true/true | HUD 开关与展开态 |
| `layer.*` | true | 城区/道路/Marker/其他图层开关 |
| `debug` | false | 调试日志 |

## 6. 排障

- 「数据状态」页若 `backend=memory`：说明 SQLite native 未能加载（已自动降级），本会话不落盘。
- 上传一直失败：看「数据」页错误与 `logs/latest.log` 中 `[upload]` 行；429 会自动按 retryAfter 暂停。
- 世界绑定失败：确认已登录 OAuth、服务器 Key 正确、世界为 ACTIVE；归档世界会明确提示「上传暂停」。
