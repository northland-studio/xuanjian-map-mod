package top.xuanjian.map.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.xuanjian.map.client.hud.GisHudRenderer;

/**
 * HUD 预览界面：把游戏内 HUD 的真实渲染代码画到屏幕上，用于 CI 稳定截图验收。
 *
 * <p>HUD 本身由 {@code HudElementRegistry} 常驻渲染；但无头 CI 里要截到 HUD 需要进入世界，
 * 本界面提供同一套绘制逻辑的确定性预览（含「无道路」规范格式），保证验收截图可复现。
 */
public class GisHudPreviewScreen extends Screen {

    private final Screen parent;

    public GisHudPreviewScreen(Screen parent) {
        super(Component.literal("玄剑 GIS HUD 预览"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(new XButton(8, 8, 60, 18, "返回", XButton.Style.GHOST, this::onClose));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // 模拟游戏画面背景（渐变 + 天空/地面色块），便于确认 HUD 的可读性
        g.fillGradient(0, 0, this.width, this.height / 2, 0xFF4A90D9, 0xFF8FC7F0);
        g.fill(0, this.height / 2, this.width, this.height, 0xFF5C8A3C);
        g.fill(0, this.height / 2, this.width, this.height / 2 + 2, 0xFF3F6429);

        super.extractRenderState(g, mouseX, mouseY, delta);

        UiKit.text(g, this.font, "HUD 预览（游戏内由 HudElementRegistry 常驻渲染，`=` 键展开/收起）",
                10, this.height - 20, UiKit.TEXT);
        GisHudRenderer.INSTANCE.renderPreview(g, this.font, 8, 8);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
