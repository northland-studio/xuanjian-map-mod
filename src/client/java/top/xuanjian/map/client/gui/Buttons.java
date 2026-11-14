package top.xuanjian.map.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 1.21 分支统一用原版 {@link Button}（不自定义 widget，避免 AbstractWidget 渲染/点击签名差异）。
 */
public final class Buttons {

    private Buttons() {
    }

    public static Button of(int x, int y, int width, int height, String label, Runnable action) {
        return Button.builder(Component.literal(label == null ? "" : label), b -> action.run())
                .bounds(x, y, width, height)
                .build();
    }

    public static Button of(int x, int y, String label, Runnable action) {
        return of(x, y, 80, UiKit.BTN_H, label, action);
    }
}
