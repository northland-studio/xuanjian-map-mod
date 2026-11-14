package top.xuanjian.map.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import top.xuanjian.map.client.GisClient;
import top.xuanjian.map.client.GisSnapshot;
import top.xuanjian.map.client.compat.McReflect;
import top.xuanjian.map.client.gui.UiKit;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.geo.LocationInfo;
import top.xuanjian.map.core.hud.HudText;

import java.util.ArrayList;
import java.util.List;

/**
 * GIS HUD（1.21.11 版：HudElementRegistry 回调收到 {@link GuiGraphics}）。
 *
 * <p>显示城区/道路/X-Y-Z/Dimension；无道路时形如 {@code 当前城区 · 无道路 · X/Y/Z}；
 * 定位走本地空间索引 + 节流，不上传玩家实时位置。
 */
public final class GisHudRenderer {

    public static final GisHudRenderer INSTANCE = new GisHudRenderer();

    private static final int PANEL_BG = 0xC0101622;
    private static final int PANEL_BORDER = 0x667CB0FF;
    private static final int ACCENT = 0xFF7CB0FF;
    private static final int LINE_H = 10;
    private static final int MINIMAP_SIZE = 56;

    private GisHudRenderer() {
    }

    public void render(GuiGraphics g) {
        GisClient client = GisClient.get();
        if (client == null || g == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !client.config().hudEnabled()) {
            return;
        }
        int[] pos = playerPosition(mc);
        String dimension = client.readDimension(mc);
        LocationInfo location = client.locateCurrent(mc);
        GisSnapshot snapshot = client.snapshot();
        if (client.config().hudExpanded()) {
            List<String> lines = HudText.expanded(location, pos[0], pos[1], pos[2], dimension,
                    List.of(snapshot.cacheLine(), snapshot.uploadLine(), snapshot.syncLine()));
            lines.add("[=] 收起 HUD");
            int panelWidth = drawPanel(g, mc.font, lines, 4, 4);
            drawMinimap(g, client, dimension, pos[0], pos[2], 4 + panelWidth + 6);
        } else {
            List<String> lines = new ArrayList<>(1);
            lines.add(HudText.compact(location, pos[0], pos[1], pos[2])
                    + " · " + Dimensions.labelZh(dimension));
            drawPanel(g, mc.font, lines, 4, 4);
        }
    }

    private static int drawPanel(GuiGraphics g, Font font, List<String> lines, int x, int y) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        int w = widest + 14;
        int h = lines.size() * LINE_H + 8;
        UiKit.roundedFill(g, x, y, w, h, PANEL_BG);
        UiKit.roundedOutline(g, x, y, w, h, PANEL_BORDER);
        g.fill(x + 1, y + 1, x + 3, y + h - 1, ACCENT);
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? UiKit.TEXT : (i == lines.size() - 1 ? UiKit.TEXT_MUTED : UiKit.TEXT_DIM);
            UiKit.text(g, font, lines.get(i), x + 7, y + 5 + i * LINE_H, color);
        }
        return w;
    }

    private static void drawMinimap(GuiGraphics g, GisClient client, String dimension,
                                    double px, double pz, int x) {
        int y = 4;
        UiKit.roundedFill(g, x, y, MINIMAP_SIZE, MINIMAP_SIZE + 10, PANEL_BG);
        UiKit.roundedOutline(g, x, y, MINIMAP_SIZE, MINIMAP_SIZE + 10, PANEL_BORDER);
        Font font = Minecraft.getInstance().font;
        UiKit.text(g, font, "附近 GIS", x + 3, y + 2, UiKit.TEXT_MUTED);
        int cx = x + MINIMAP_SIZE / 2;
        int cy = y + MINIMAP_SIZE / 2 + 5;
        g.fill(cx - 1, cy, cx + 2, cy + 1, ACCENT);
        g.fill(cx, cy - 1, cx + 1, cy + 2, ACCENT);
        var session = client.session();
        if (session == null || session.dimension() == null || session.dimension().isBlank()) {
            return;
        }
        var markers = client.locator().nearbyMarkers(session.dimension(), px, pz, 64, 12);
        for (var marker : markers) {
            var point = marker.geometry() == null ? null : marker.geometry().asPoint();
            if (point == null) {
                continue;
            }
            int dx = (int) Math.round((point.x() - px) / 64.0 * (MINIMAP_SIZE / 2.0 - 4));
            int dz = (int) Math.round((point.z() - pz) / 64.0 * (MINIMAP_SIZE / 2.0 - 4));
            g.fill(cx + dx - 1, cy + dz - 1, cx + dx + 1, cy + dz + 1, UiKit.MARKER);
        }
    }

    public static int[] playerPosition(Minecraft mc) {
        if (mc == null || mc.player == null) {
            return new int[]{0, 0, 0};
        }
        Object x = McReflect.call(mc.player, new String[]{"getBlockX", "getX"});
        Object y = McReflect.call(mc.player, new String[]{"getBlockY", "getY"});
        Object z = McReflect.call(mc.player, new String[]{"getBlockZ", "getZ"});
        return new int[]{intOf(x), intOf(y), intOf(z)};
    }

    private static int intOf(Object value) {
        return value instanceof Number num ? num.intValue() : 0;
    }

    /** 截图用 HUD 预览（CI 稳定产出「HUD」验收截图，含无道路规范格式）。 */
    public void renderPreview(GuiGraphics g, Font font, int x, int y) {
        List<String> expanded = HudText.expanded(
                new LocationInfo("d1", "青云城区", "r1", "中央大道", 3),
                128, 70, -256, Dimensions.OVERWORLD,
                List.of("缓存：区块 42（已传 30 / 待传 12 / 失败 0） · 对象 17 · 96 KiB",
                        "上传队列：待传 1 · 重试 0 · 完成 5 · 失败 0",
                        "同步：8 秒前"));
        expanded.add("[=] 收起 HUD");
        drawPanel(g, font, expanded, x, y);
        List<String> compact = new ArrayList<>();
        compact.add(HudText.compact(new LocationInfo("d1", "青云城区", null, null, 0),
                466, 68, -870) + " · 主世界");
        drawPanel(g, font, compact, x, y + expanded.size() * LINE_H + 18);
    }
}
