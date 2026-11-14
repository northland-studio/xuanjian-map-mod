package top.xuanjian.map.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 玄剑风格自绘按钮（26.2：重写 extractWidgetRenderState / onClick(MouseButtonEvent, boolean)）。
 */
public class XButton extends AbstractWidget {

    public enum Style { PRIMARY, SECONDARY, GHOST, NAV, DANGER }

    private final Style style;
    private final Runnable action;
    private String label;
    private boolean selected;

    public XButton(int x, int y, int width, int height, String label, Style style, Runnable action) {
        super(x, y, width, height, Component.literal(label == null ? "" : label));
        this.label = label == null ? "" : label;
        this.style = style;
        this.action = action;
    }

    public static XButton of(int x, int y, int width, String label, Style style, Runnable action) {
        return new XButton(x, y, width, UiKit.BTN_H, label, style, action);
    }

    public XButton selected(boolean value) {
        this.selected = value;
        return this;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
        setMessage(Component.literal(this.label));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (!this.visible) {
            return;
        }
        boolean hover = this.isHoveredOrFocused() && this.active;
        int x = getX();
        int y = getY();
        int w = this.width;
        int h = this.height;
        int bg;
        int border;
        int textColor;
        switch (style) {
            case PRIMARY -> {
                bg = this.active ? (hover ? 0xFF3B82F6 : UiKit.NAV_ACTIVE) : 0x552B3A55;
                border = this.active ? 0x807CB0FF : 0x33FFFFFF;
                textColor = this.active ? UiKit.TEXT : UiKit.TEXT_DISABLED;
            }
            case DANGER -> {
                bg = hover && this.active ? 0x40FF5555 : 0x22FF5555;
                border = 0x80F87171;
                textColor = this.active ? UiKit.DANGER : UiKit.TEXT_DISABLED;
            }
            case GHOST -> {
                bg = hover && this.active ? 0x22FFFFFF : 0x00000000;
                border = 0x00000000;
                textColor = this.active ? UiKit.TEXT_DIM : UiKit.TEXT_DISABLED;
            }
            case NAV -> {
                bg = selected ? UiKit.NAV_ACTIVE : (hover ? UiKit.NAV_HOVER : 0x00000000);
                border = selected ? 0x807CB0FF : 0x00000000;
                textColor = selected ? UiKit.TEXT : (hover ? UiKit.TEXT : UiKit.TEXT_DIM);
            }
            default -> {
                bg = hover && this.active ? UiKit.CARD_BG_HOVER : UiKit.CARD_BG;
                border = hover && this.active ? 0x66FFFFFF : UiKit.CARD_BORDER;
                textColor = this.active ? UiKit.TEXT : UiKit.TEXT_DISABLED;
            }
        }
        if (bg != 0x00000000) {
            UiKit.roundedFill(g, x, y, w, h, bg);
        }
        if (border != 0x00000000) {
            UiKit.roundedOutline(g, x, y, w, h, border);
        }
        var font = Minecraft.getInstance().font;
        UiKit.textCenter(g, font, UiKit.ellipsis(font, label, w - 6), x + w / 2,
                UiKit.vCenter(font, y, h), textColor);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!this.active) {
            return;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        if (action != null) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
