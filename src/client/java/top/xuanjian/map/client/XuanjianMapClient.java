package top.xuanjian.map.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
 * 客户端入口（1.21.11 版）：
 * 初始化 GIS 服务、注册 HUD 元素与按键（`=` 展开/收起、`G` 打开界面）、驱动每 tick 调度。
 *
 * <p>1.21 适配点：{@code KeyBindingHelper.registerKeyBinding}、HUD 回调使用 {@code GuiGraphics}、
 * 切界面用 {@code Minecraft.setScreen}、聊天用 {@code player.displayClientMessage}。
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
        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xuanjianmap.hud_toggle", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_EQUAL, category));
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
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
                if (client.screen == null) {
                    client.setScreen(new GisMainScreen());
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

        LOGGER.info("[{}] 客户端 GIS 初始化完成（1.21.11；HUD=常驻，`=` 展开/收起，`G` 打开界面）",
                XuanjianMap.MOD_ID);
    }
}
