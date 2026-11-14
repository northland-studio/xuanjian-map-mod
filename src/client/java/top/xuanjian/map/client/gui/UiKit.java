package top.xuanjian.map.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 玄剑 GIS 界面主题与绘制工具（Minecraft 1.21.11：{@link GuiGraphics}，颜色 8 位 ARGB）。
 *
 * <p>与 26 分支视觉一致，但渲染入口/文本 API 使用 1.21 版本；圆角描边用 4 条 fill 实现，
 * 避免依赖不同版本的 outline 签名。
 */
public final class UiKit {

    private UiKit() {
    }

    /* ==================== 配色 ==================== */
    public static final int PANEL_BG = 0xE6111A2B;
    public static final int PANEL_BORDER = 0x3D7EA8FF;
    public static final int CARD_BG = 0x24FFFFFF;
    public static final int CARD_BG_HOVER = 0x40FFFFFF;
    public static final int CARD_BG_ACTIVE = 0x552563EB;
    public static final int CARD_BORDER = 0x22FFFFFF;
    public static final int HEADER_TOP = 0xFF1D4ED8;
    public static final int HEADER_BOTTOM = 0xFF1E3A8A;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFC3CEDF;
    public static final int TEXT_MUTED = 0xFF8496AE;
    public static final int TEXT_DISABLED = 0xFF5A6B80;
    public static final int ACCENT = 0xFF7CB0FF;
    public static final int SUCCESS = 0xFF4ADE80;
    public static final int WARNING = 0xFFFBBF24;
    public static final int DANGER = 0xFFF87171;
    public static final int NAV_ACTIVE = 0xFF2563EB;
    public static final int NAV_HOVER = 0x2EFFFFFF;
    public static final int NAV_BG = 0x33000000;
    public static final int DIVIDER = 0x24FFFFFF;
    public static final int ROAD = 0xFFFBBF24;
    public static final int DISTRICT_FILL = 0x332563EB;
    public static final int DISTRICT_BORDER = 0xFF7CB0FF;
    public static final int MARKER = 0xFF4ADE80;

    /* ==================== 尺寸 ==================== */
    public static final int NAV_W = 108;
    public static final int NAV_ITEM_H = 24;
    public static final int BTN_H = 20;
    public static final int HEADER_H = 28;

    /* ==================== 绘制 ==================== */

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        roundedFill(g, x, y, w, h, PANEL_BG);
        roundedOutline(g, x, y, w, h, PANEL_BORDER);
    }

    public static void headerBar(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, HEADER_TOP, HEADER_BOTTOM);
    }

    public static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        roundedFill(g, x, y, w, h, hover ? CARD_BG_HOVER : CARD_BG);
        roundedOutline(g, x, y, w, h, CARD_BORDER);
    }

    public static void divider(GuiGraphics g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, DIVIDER);
    }

    public static void roundedFill(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w <= 2 || h <= 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    public static void roundedOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 2 || h <= 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, double ratio, int color) {
        roundedFill(g, x, y, w, h, 0x33FFFFFF);
        int fillW = (int) Math.max(0, Math.min(1, ratio) * w);
        if (fillW > 0) {
            roundedFill(g, x, y, fillW, h, color);
        }
    }

    public static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int thickness) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            g.fill(x1, y1, x1 + thickness, y1 + thickness, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x, y, x + thickness, y + thickness, color);
        }
    }

    public static void polygon(GuiGraphics g, int[] xs, int[] zs, int fillColor,
                               int borderColor, int thickness) {
        if (xs.length < 3) {
            return;
        }
        if (fillColor != 0) {
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int z : zs) {
                minY = Math.min(minY, z);
                maxY = Math.max(maxY, z);
            }
            for (int y = Math.max(0, minY); y <= Math.min(100_000, maxY); y++) {
                int start = Integer.MAX_VALUE;
                int end = Integer.MIN_VALUE;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    int yi = zs[i];
                    int yj = zs[j];
                    if ((yi > y) != (yj > y)) {
                        int x = xs[i] + (int) ((long) (xs[j] - xs[i]) * (y - yi) / (double) (yj - yi));
                        start = Math.min(start, x);
                        end = Math.max(end, x);
                    }
                }
                if (start <= end) {
                    g.fill(start, y, end + 1, y + 1, fillColor);
                }
            }
        }
        for (int i = 1; i < xs.length; i++) {
            line(g, xs[i - 1], zs[i - 1], xs[i], zs[i], borderColor, thickness);
        }
        line(g, xs[xs.length - 1], zs[zs.length - 1], xs[0], zs[0], borderColor, thickness);
    }

    /* ==================== 文本 ==================== */

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s == null ? "" : s, x, y, color, true);
    }

    public static void textCenter(GuiGraphics g, Font font, String s, int centerX, int y, int color) {
        g.drawCenteredString(font, s == null ? "" : s, centerX, y, color);
    }

    public static void textRight(GuiGraphics g, Font font, String s, int rightX, int y, int color) {
        String value = s == null ? "" : s;
        g.drawString(font, value, rightX - font.width(value), y, color, true);
    }

    public static int vCenter(Font font, int top, int h) {
        return top + (h - font.lineHeight) / 2 + 1;
    }

    public static String ellipsis(Font font, String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int ellipsisWidth = font.width("…");
        for (int i = 0; i < s.length(); i++) {
            if (font.width(sb.toString() + s.charAt(i)) + ellipsisWidth > maxWidth) {
                break;
            }
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    public static int parseColor(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) {
            return fallback;
        }
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
