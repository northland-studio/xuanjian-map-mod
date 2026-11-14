package top.xuanjian.map.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.xuanjian.map.client.hud.GisHudRenderer;

/**
 * HUD 预览界面（1.21.11 版）：把游戏内 HUD 的真实渲染代码画到屏幕上，供 CI 稳定截图验收。
 */
public class GisHudPreviewScreen extends Screen {

    private final Screen parent;

    public GisHudPreviewScreen(Screen parent) {
        super(Component.literal("玄剑 GIS HUD 预览"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Buttons.of(8, 8, 60, 18, "返回", this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fillGradient(0, 0, this.width, this.height / 2, 0xFF4A90D9, 0xFF8FC7F0);
        g.fill(0, this.height / 2, this.width, this.height, 0xFF5C8A3C);
        g.fill(0, this.height / 2, this.width, this.height / 2 + 2, 0xFF3F6429);

        super.render(g, mouseX, mouseY, delta);

        UiKit.text(g, this.font, "HUD 预览（游戏内由 HudElementRegistry 常驻渲染，`=` 键展开/收起）",
                10, this.height - 20, UiKit.TEXT);
        GisHudRenderer.INSTANCE.renderPreview(g, this.font, 8, 8);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
