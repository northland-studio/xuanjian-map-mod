# Xaero World Map / Minimap 集成结论

## 结论（目标版本：MC 26.2 / 1.21.11）

**Xaero 在目标版本没有可用的官方公开渲染 API**：

- Xaero's World Map / Minimap 未发布供第三方绘图用的官方 API 文档或稳定接口；
- 其小地图/世界地图绘制流程、缓存格式均为内部实现，随版本变化；
- 规范明确禁止：篡改 Xaero 内部缓存文件、以其私有格式作为上传数据标准、破坏其原有缩放/拖动/缓存/定位。

因此采用规范允许的**兜底方案：自有 GIS Overlay Renderer**，并把「Xaero 是否存在」作为
可选集成点（`XaeroCompat`）检测，仅用于状态显示与未来接入判断，不做任何侵入式修改。

## 自有渲染方案

| 能力 | 实现 |
| --- | --- |
| World Map 全图层 | `GisMapScreen`：可拖动/缩放，绘制城区填充多边形、道路折线（按真实宽度）、Marker、其他对象；图层可开关 |
| Minimap 附近对象 | HUD 展开态绘制附近 Marker 相对方位点（仅附近 64 格，非全图） |
| 图层管理 | `GisLayersScreen`（城区/道路/Marker/其他）联同设置页开关 |
| 不破坏 Xaero | 独立 Screen + 独立 HUD 元素（`HudElementRegistry.attachElementBefore`），不注入 Xaero 的渲染管线 |
| 数据来源 | 只读本地 SQLite 中的 Published 对象缓存，绝不读 Xaero 缓存 |

## 可选：未来接入 Xaero 的路径

如果后续 Xaero 提供公开 API（或允许通过其扩展点注册渲染层），接入点是：

1. `XaeroCompat.detected()` 已能检测 `xaeroworldmap` / `xaerominimap` 模组是否加载；
2. 只需把 `GisMapScreen` 的绘制逻辑抽成 `GisOverlayPainter`（已按此结构组织）后适配到其渲染回调；
3. 由于许可与稳定性未确认，首版**不作为依赖**，也不会因此让模组无法启动。

## 为什么不当依赖打包

- 许可：Xaero 为专有许可，不宜作为强制依赖再分发；
- 稳定性：其内部 API 无兼容承诺，客户端升级会连带崩溃；
- 验收：规范要求「Xaero Overlay 能正常显示**或有可工作的自有渲染方案**」，本仓库选择后者并截图验收。
