package top.xuanjian.map.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuanjian.map.XuanjianMap;
import top.xuanjian.map.client.gui.GisMainScreen;
import top.xuanjian.map.client.hud.GisHudRenderer;
import top.xuanjian.map.core.util.GisLog;

/**
 * 客户端入口：
 * <ul>
 *   <li>初始化 GIS 客户端服务（配置 / SQLite / 上传 / 同步）；</li>
 *   <li>注册 HUD 元素（HudElementRegistry，常驻）与按键（`=` 展开/收起、`G` 打开 GIS 界面）；</li>
 *   <li>每 tick 驱动采集/上传/同步调度（真正工作都在后台线程）。</li>
 * </ul>
 */
public class XuanjianMapClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(XuanjianMap.MOD_ID);

    public static KeyMapping hudToggleKey;
    public static KeyMapping openGuiKey;

    @Override
    public void onInitializeClient() {
        GisLog.setSink((level, tag, message, error) -> {
            switch (level) {
                case DEBUG -> LOGGER.debug("[{}] {}", tag, message);
                case INFO -> LOGGER.info("[{}] {}", tag, message);
                case WARN -> {
                    if (error != null) {
                        LOGGER.warn("[{}] {}", tag, message, error);
                    } else {
                        LOGGER.warn("[{}] {}", tag, message);
                    }
                }
                case ERROR -> LOGGER.error("[{}] {}", tag, message, error);
            }
        });

        GisClient.initialize();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(XuanjianMap.MOD_ID, "gis"));
        // `=`：HUD 展开/收起（规范要求）
        hudToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.xuanjianmap.hud_toggle", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_EQUAL, category));
        // `G`：打开 GIS 界面
        openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.xuanjianmap.open_gui", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, category));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(XuanjianMap.MOD_ID, "gis_hud"),
                (graphics, deltaTracker) -> GisHudRenderer.INSTANCE.render(graphics));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            GisClient gis = GisClient.get();
            if (gis == null) {
                return;
            }
            while (hudToggleKey != null && hudToggleKey.consumeClick()) {
                gis.toggleHudExpanded();
            }
            while (openGuiKey != null && openGuiKey.consumeClick()) {
                if (client.gui != null && client.gui.screen() == null) {
                    client.gui.setScreen(new GisMainScreen());
                }
            }
            gis.tick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GisClient gis = GisClient.get();
            if (gis != null) {
                gis.rebind();
            }
        });

        LOGGER.info("[{}] 客户端 GIS 初始化完成（HUD=常驻，`=` 展开/收起，`G` 打开界面）",
                XuanjianMap.MOD_ID);
    }
}
