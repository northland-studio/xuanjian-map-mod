package top.xuanjian.map.client.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Xaero 兼容性检测（只检测、不修改）。
 *
 * <p>结论（详见 docs/xaero-integration.md）：目标版本 Xaero 没有公开渲染 API，
 * 规范也禁止篡改其缓存或以其私有格式作为上传标准。因此本模组提供自有 GIS 叠加渲染，
 * 这里仅用于 UI 状态展示与未来接入点判断。
 */
public final class XaeroCompat {

    private XaeroCompat() {
    }

    public static boolean worldMapLoaded() {
        try {
            return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean minimapLoaded() {
        try {
            return FabricLoader.getInstance().isModLoaded("xaerominimap")
                    || FabricLoader.getInstance().isModLoaded("xaerominimapfair");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String mode() {
        boolean map = worldMapLoaded();
        boolean mini = minimapLoaded();
        if (map && mini) {
            return "Xaero：已检测到 World Map + Minimap（无官方 API → 自有叠加层）";
        }
        if (map) {
            return "Xaero：已检测到 World Map（无官方 API → 自有叠加层）";
        }
        if (mini) {
            return "Xaero：已检测到 Minimap（无官方 API → 自有叠加层）";
        }
        return "Xaero：未检测到（自有 GIS 叠加渲染）";
    }
}
