package top.xuanjian.map.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.xuanjian.map.client.GisClient;
import top.xuanjian.map.client.GisSnapshot;
import top.xuanjian.map.client.hud.GisHudRenderer;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.geo.GisLocator;
import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.Geometry;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;

import java.util.List;

/**
 * 自有 GIS 叠加渲染器（1.21.11 版，World Map 全图层）。
 *
 * <p>Xaero 无官方 API → 自有渲染兜底；独立于 Xaero，不注入其管线、不读写其缓存。
 * 图层：城区多边形 / 道路（真实宽度）/ Marker / 其他；按钮式缩放居中，避免与 Xaero 冲突。
 */
public class GisMapScreen extends Screen {

    private static final double[] SCALES = {0.02, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0};
    private static final int[] STEP = {1024, 512, 256, 128, 64, 32, 16};

    private final Screen parent;
    private int zoom = 4;
    private double centerX;
    private double centerZ;
    private boolean centered;

    private int px;
    private int py;
    private int pw;
    private int ph;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;

    public GisMapScreen(Screen parent) {
        super(Component.literal("玄剑 GIS 地图"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        computeLayout();
        GisClient client = GisClient.get();
        if (!centered && client != null) {
            int[] pos = GisHudRenderer.playerPosition(this.minecraft);
            centerX = pos[0];
            centerZ = pos[2];
            centered = true;
        }
        buildWidgets();
    }

    private void computeLayout() {
        pw = Math.max(420, Math.min(this.width - 24, 600));
        ph = Math.max(300, Math.min(this.height - 24, 400));
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        viewX = px + 8;
        viewY = py + UiKit.HEADER_H + 8;
        viewW = pw - 16;
        viewH = ph - UiKit.HEADER_H - 34;
    }

    private void buildWidgets() {
        this.clearWidgets();
        computeLayout();
        int btnY = py + 4;
        int right = px + pw - 6;
        this.addRenderableWidget(Buttons.of(right - 40, btnY, 36, 20, "关闭", this::onClose));
        right -= 42;
        this.addRenderableWidget(Buttons.of(right - 36, btnY, 32, 20, "刷新", this::recenter));
        right -= 38;
        this.addRenderableWidget(Buttons.of(right - 32, btnY, 28, 20, "+", () -> {
            zoom = Math.min(SCALES.length - 1, zoom + 1);
        }));
        right -= 34;
        this.addRenderableWidget(Buttons.of(right - 32, btnY, 28, 20, "-", () -> {
            zoom = Math.max(0, zoom - 1);
        }));
        right -= 34;
        this.addRenderableWidget(Buttons.of(right - 56, btnY, 52, 20, "居中", () -> {
            centered = false;
            recenter();
        }));

        GisClient client = GisClient.get();
        int toggleW = Math.max(72, (viewW - 12) / 4);
        int toggleY = py + ph - 22;
        String[] labels = {"城区", "道路", "Marker", "其他"};
        for (int i = 0; i < 4; i++) {
            final int index = i;
            boolean on = layerOn(client, index);
            this.addRenderableWidget(Buttons.of(viewX + i * (toggleW + 4), toggleY, toggleW, 18,
                    labels[i] + (on ? " ✓" : " ✗"), () -> {
                        if (client != null) {
                            client.toggleLayer(index);
                        }
                        buildWidgets();
                    }));
        }
    }

    private static boolean layerOn(GisClient client, int index) {
        if (client == null) {
            return true;
        }
        return switch (index) {
            case 0 -> client.config().layerDistricts();
            case 1 -> client.config().layerRoads();
            case 2 -> client.config().layerMarkers();
            default -> client.config().layerOthers();
        };
    }

    private void recenter() {
        int[] pos = GisHudRenderer.playerPosition(this.minecraft);
        centerX = pos[0];
        centerZ = pos[2];
        GisClient client = GisClient.get();
        if (client != null) {
            client.refreshSnapshotSoon();
        }
    }

    double scale() {
        return SCALES[zoom];
    }

    private double halfWidth() {
        return viewW / 2.0 / scale();
    }

    private BBox viewBbox() {
        double halfW = halfWidth();
        double halfH = viewH / 2.0 / scale();
        return new BBox(centerX - halfW, centerZ - halfH, centerX + halfW, centerZ + halfH);
    }

    private int sx(double worldX) {
        return viewX + viewW / 2 + (int) Math.round((worldX - centerX) * scale());
    }

    private int sz(double worldZ) {
        return viewY + viewH / 2 + (int) Math.round((worldZ - centerZ) * scale());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        computeLayout();
        UiKit.panel(g, px, py, pw, ph);
        UiKit.headerBar(g, px + 1, py + 1, pw - 2, UiKit.HEADER_H - 1);
        super.render(g, mouseX, mouseY, delta);

        UiKit.text(g, this.font, "玄剑 GIS 地图", px + 10,
                UiKit.vCenter(this.font, py, UiKit.HEADER_H), UiKit.TEXT);
        UiKit.textRight(g, this.font, "自有叠加渲染 · 不修改 Xaero", px + pw - 12, py + 9, UiKit.TEXT_MUTED);
        drawMap(g);
        UiKit.text(g, this.font, "缩放 " + String.format("%.2f", scale()) + " px/block · 网格 "
                        + STEP[zoom] + " 格 · 中心 (" + (long) centerX + ", " + (long) centerZ + ")",
                viewX, py + ph - 32, UiKit.TEXT_MUTED);
    }

    private void drawMap(GuiGraphics g) {
        g.fill(viewX, viewY, viewX + viewW, viewY + viewH, 0xF00B1220);
        UiKit.roundedOutline(g, viewX, viewY, viewW, viewH, 0x557CB0FF);

        int step = STEP[zoom];
        int[] pos = GisHudRenderer.playerPosition(this.minecraft);
        int gridColor = 0x18FFFFFF;
        long startX = ((long) Math.floor((centerX - halfWidth()) / step)) * step;
        long endX = ((long) Math.ceil((centerX + halfWidth()) / step)) * step;
        for (long wx = startX; wx <= endX; wx += step) {
            int x = sx(wx);
            if (x >= viewX && x <= viewX + viewW) {
                g.fill(x, viewY, x + 1, viewY + viewH, gridColor);
            }
        }
        long startZ = ((long) Math.floor((centerZ - viewH / 2.0 / scale()) / step)) * step;
        long endZ = ((long) Math.ceil((centerZ + viewH / 2.0 / scale()) / step)) * step;
        for (long wz = startZ; wz <= endZ; wz += step) {
            int z = sz(wz);
            if (z >= viewY && z <= viewY + viewH) {
                g.fill(viewX, z, viewX + viewW, z + 1, gridColor);
            }
        }

        GisClient client = GisClient.get();
        GisSnapshot snapshot = client == null ? GisSnapshot.EMPTY : client.snapshot();
        String dimension = client == null || client.session() == null ? ""
                : client.session().dimension();
        if (dimension == null || dimension.isBlank()) {
            UiKit.textCenter(g, this.font, "未进入世界：无 GIS 数据可叠加", viewX + viewW / 2,
                    viewY + viewH / 2 - 4, UiKit.TEXT_MUTED);
            return;
        }

        GisLocator.FeatureFilter filter = new GisLocator.FeatureFilter(
                client.config().layerDistricts(), client.config().layerRoads(),
                client.config().layerMarkers(), client.config().layerOthers());
        List<GisObject> objects = client.locator().objectsInView(dimension, viewBbox(), filter);
        int drawn = 0;
        for (GisObject object : objects) {
            if (drawn++ > 400) {
                break;
            }
            if (object.kind() == GisObjectKind.DISTRICT && object.geometry() instanceof Geometry.GisPolygon polygon) {
                var ring = polygon.outer();
                int[] xs = new int[ring.xs().length];
                int[] zs = new int[ring.zs().length];
                for (int i = 0; i < xs.length; i++) {
                    xs[i] = sx(ring.xs()[i]);
                    zs[i] = sz(ring.zs()[i]);
                }
                UiKit.polygon(g, xs, zs, UiKit.DISTRICT_FILL, UiKit.DISTRICT_BORDER, 1);
                if (scale() >= 0.1) {
                    UiKit.textCenter(g, this.font, object.name(), sx(object.bbox().centerX()),
                            sz(object.bbox().centerZ()), UiKit.ACCENT);
                }
            } else if (object.kind() == GisObjectKind.ROAD && object.geometry() instanceof Geometry.GisLine line) {
                int thickness = Math.max(1, (int) Math.round(object.roadWidth() * scale()));
                double[][] points = line.points();
                for (int i = 1; i < points.length; i++) {
                    UiKit.line(g, sx(points[i - 1][0]), sz(points[i - 1][1]),
                            sx(points[i][0]), sz(points[i][1]), UiKit.ROAD, thickness);
                }
                if (scale() >= 0.25 && !object.name().isBlank()) {
                    UiKit.text(g, this.font, object.name(), sx(points[0][0]) + 2,
                            sz(points[0][1]) - 10, UiKit.ROAD);
                }
            } else if (object.kind() == GisObjectKind.MARKER) {
                var point = object.geometry().asPoint();
                int mx = sx(point.x());
                int mz = sz(point.z());
                g.fill(mx - 2, mz - 2, mx + 3, mz + 3, UiKit.MARKER);
                if (scale() >= 0.25 && !object.name().isBlank()) {
                    UiKit.text(g, this.font, object.name(), mx + 4, mz - 4, UiKit.TEXT_DIM);
                }
            } else {
                var point = object.geometry().asPoint();
                g.fill(sx(point.x()), sz(point.z()), sx(point.x()) + 2, sz(point.z()) + 2, UiKit.TEXT_MUTED);
            }
        }

        int playerX = sx(pos[0]);
        int playerZ = sz(pos[2]);
        g.fill(playerX - 3, playerZ, playerX + 4, playerZ + 1, 0xFFFFFFFF);
        g.fill(playerX, playerZ - 3, playerX + 1, playerZ + 4, 0xFFFFFFFF);
        UiKit.text(g, this.font, "玩家", playerX + 5, playerZ - 4, UiKit.TEXT);

        UiKit.text(g, this.font, "维度：" + Dimensions.labelZh(dimension)
                        + " · 屏内对象 " + objects.size() + " · 本地对象 "
                        + client.locator().objectCount(dimension),
                viewX + 4, viewY + viewH - 12, UiKit.TEXT_MUTED);
        if (objects.isEmpty() && snapshot.stats().objectCount() == 0) {
            UiKit.textCenter(g, this.font, "本地暂无 GIS 对象（等待 /api/gis/client/sync 同步）",
                    viewX + viewW / 2, viewY + viewH / 2 + 12, UiKit.WARNING);
        }
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
