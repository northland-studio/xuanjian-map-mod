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

## 3. 登录与上传（OAuth2 PKCE，游戏内闭环）

上传需要 xuanjian.top 统一账号登录。**全程在游戏内完成，不需要命令行**：

1. 打开 GIS 界面 →「设置」页（未登录时显示 OAuth 按钮 + code 输入框）。
2. 点 **「生成授权链接（复制）」**：
   - 自动生成 PKCE 授权链接，并依次尝试 GLFW → KeyboardHandler → AWT 写入系统剪贴板；
   - 若本机剪贴板受限（沙箱/远程桌面/部分 Linux 桌面等），链接会**自动写入文件**
     `config/xuanjianmap/oauth-authorize-url.txt`，提示里会给出完整路径，打开该文件复制即可；
   - 链接同时打印在 `logs/latest.log`。
   - 把链接粘贴到浏览器打开 → 登录并授权。
3. 授权后浏览器停在回调页 `https://xuanjian.top/api/oauth/mod-callback`，
   页面上直接显示 `code` 与 `state`（code **5 分钟一次性有效**）。
4. 复制页面上的 `code`（直接复制 code、`code=xxx`、或整段回调 URL 都可以，客户端会自动提取）。
5. 回到游戏 →「设置」页，两种完成方式（任选其一）：
   - **「完成登录（剪贴板）」**：从剪贴板读取 code 并登录；
   - **「登录（输入框）」**：把 code 粘贴到输入框（剪贴板不可用时也能用，可靠兜底）；
   - 剪贴板彻底不可用时，也可把 code 写进 `config/xuanjianmap/oauth-code.txt` 再点「完成登录（剪贴板）」。
   - 成功：提示「已登录：<昵称>」，设置页显示 OAuth 已登录（昵称）；
   - 失败：提示中文原因，例如「授权码无效或已过期（code 5 分钟一次性，请重新生成授权链接）」、
     「请求不合法（检查 redirect_uri / code_verifier）」、「网络错误：无法连接 …」，
     并附带服务端 `error_description` 便于排查。
6. 已登录后按钮变为「退出登录」/「重新登录（生成链接）」。

说明：
- token 安全存到 `config/xuanjianmap-oauth.properties`（权限 600，不入库）；access_token 2h、
  refresh_token 30d 一次性轮换，过期自动刷新。
- 上传还需要服务端 `gis.submit` 权限；被拒时「数据/概览」页会显示 `forbidden` 与缺少的权限名。
- 未登录 / 世界未注册 / 世界归档时进入**本地模式**：HUD、地图、采集缓存都可用，**不上传**。
- 生产世界恢复 ACTIVE 后，客户端会自动恢复上传（或点「立即同步/重新绑定」立即恢复）。
- 兼容性：客户端不依赖剪贴板权限；OAuth 只需要浏览器能打开链接、用户能看到/复制 code。

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
