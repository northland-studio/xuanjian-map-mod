package top.xuanjian.map.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.xuanjian.map.client.GisClient;
import top.xuanjian.map.client.GisSnapshot;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.storage.GisStore;

import java.util.List;

/**
 * 玄剑 GIS 主界面：左侧导航 + 右侧内容，逐页覆盖
 * 概览 / 图层管理 / 世界选择 / 地图数据状态 / GIS 设置。
 *
 * <p>所有数据来自 {@link GisSnapshot}（后台线程更新的不可变快照），渲染线程不做 IO。
 * 26.2 约定：{@code extractRenderState(GuiGraphicsExtractor, int, int, float)}，
 * 切界面用 {@code Minecraft.getInstance().gui.setScreen(...)}，颜色 8 位 ARGB。
 */
public class GisMainScreen extends Screen {

    private enum Page {
        OVERVIEW("概览", "GIS 状态总览"),
        LAYERS("图层", "图层管理"),
        WORLDS("世界", "世界选择"),
        DATA("数据", "地图数据状态"),
        SETTINGS("设置", "GIS 设置");

        final String label;
        final String title;

        Page(String label, String title) {
            this.label = label;
            this.title = title;
        }
    }

    private final Screen parent;
    private Page page = Page.OVERVIEW;
    private GisSnapshot snapshot = GisSnapshot.EMPTY;

    private int px;
    private int py;
    private int pw;
    private int ph;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    private String toast = "";
    private int toastColor = UiKit.SUCCESS;
    private long toastUntil;

    /* 设置页输入框 */
    private EditBox apiBaseBox;
    private EditBox serverKeyBox;
    private EditBox rateBox;
    private EditBox batchBox;
    private EditBox intervalBox;
    private EditBox radiusBox;
    private EditBox stepBox;

    public GisMainScreen() {
        this(null);
    }

    public GisMainScreen(Screen parent) {
        super(Component.literal("玄剑 GIS"));
        this.parent = parent;
    }

    /* ==================== 布局与 widget ==================== */

    @Override
    protected void init() {
        computeLayout();
        buildWidgets();
        refreshData();
    }

    private void computeLayout() {
        pw = Math.max(420, Math.min(this.width - 24, 620));
        ph = Math.max(280, Math.min(this.height - 24, 380));
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        contentX = px + 8 + UiKit.NAV_W + 8;
        contentY = py + UiKit.HEADER_H + 8;
        contentW = pw - (contentX - px) - 8;
        contentH = ph - UiKit.HEADER_H - 16;
    }

    private void buildWidgets() {
        this.clearWidgets();
        computeLayout();

        int navX = px + 8;
        int navY = py + UiKit.HEADER_H + 10;
        for (Page p : Page.values()) {
            this.addRenderableWidget(new XButton(navX + 4, navY, UiKit.NAV_W - 8, UiKit.NAV_ITEM_H,
                    p.label, XButton.Style.NAV, () -> {
                page = p;
                toast = "";
                buildWidgets();
            }).selected(page == p));
            navY += UiKit.NAV_ITEM_H + 2;
        }

        int btnY = py + 4;
        int right = px + pw - 6;
        this.addRenderableWidget(new XButton(right - 40, btnY, 36, 20, "关闭", XButton.Style.GHOST, this::onClose));
        right -= 42;
        this.addRenderableWidget(new XButton(right - 56, btnY, 52, 20, "GIS 地图", XButton.Style.GHOST,
                () -> this.minecraft.gui.setScreen(new GisMapScreen(this))));
        right -= 58;
        this.addRenderableWidget(new XButton(right - 36, btnY, 32, 20, "刷新", XButton.Style.GHOST, this::refreshData));

        switch (page) {
            case OVERVIEW -> buildOverview();
            case LAYERS -> buildLayers();
            case WORLDS -> buildWorlds();
            case DATA -> buildData();
            case SETTINGS -> buildSettings();
        }
    }

    private void refreshData() {
        GisClient client = GisClient.get();
        if (client == null) {
            snapshot = GisSnapshot.EMPTY;
            return;
        }
        snapshot = client.snapshot();
        client.refreshSnapshotSoon();
    }

    /* ==================== 各页 widget ==================== */

    private void buildOverview() {
        int y = py + ph - 26;
        this.addRenderableWidget(new XButton(contentX, y, 90, UiKit.BTN_H, "立即同步",
                XButton.Style.PRIMARY, () -> {
            GisClient client = GisClient.get();
            if (client != null) {
                client.forceSync();
                client.rebind();
            }
            note("已触发同步与重新绑定");
        }));
        this.addRenderableWidget(new XButton(contentX + 96, y, 90, UiKit.BTN_H, "打开地图",
                XButton.Style.SECONDARY, () -> this.minecraft.gui.setScreen(new GisMapScreen(this))));
        this.addRenderableWidget(new XButton(contentX + 192, y, 90, UiKit.BTN_H, "设置",
                XButton.Style.SECONDARY, () -> {
            page = Page.SETTINGS;
            buildWidgets();
        }));
    }

    private void buildLayers() {
        int y = contentY + 4;
        String[] labels = {"城区边界", "道路", "建筑 Marker", "其他 GIS 实体"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            this.addRenderableWidget(new XButton(contentX + contentW - 76, y + i * 26, 76, 18,
                    layerOn(i) ? "已开启" : "已关闭", XButton.Style.SECONDARY, () -> {
                GisClient client = GisClient.get();
                if (client != null) {
                    boolean value = client.toggleLayer(index);
                    note(labels[index] + (value ? " 已开启" : " 已关闭"));
                }
                buildWidgets();
            }));
        }
        int bottom = py + ph - 26;
        this.addRenderableWidget(new XButton(contentX, bottom, 90, UiKit.BTN_H, "打开地图",
                XButton.Style.PRIMARY, () -> this.minecraft.gui.setScreen(new GisMapScreen(this))));
    }

    private boolean layerOn(int index) {
        GisClient client = GisClient.get();
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

    private void buildWorlds() {
        int bottom = py + ph - 26;
        this.addRenderableWidget(new XButton(contentX, bottom, 90, UiKit.BTN_H, "重新绑定",
                XButton.Style.PRIMARY, () -> {
            GisClient client = GisClient.get();
            if (client != null) {
                client.rebind();
            }
            note("已请求重新绑定世界");
        }));
        this.addRenderableWidget(new XButton(contentX + 96, bottom, 90, UiKit.BTN_H, "刷新",
                XButton.Style.SECONDARY, this::refreshData));
    }

    private void buildData() {
        int bottom = py + ph - 26;
        this.addRenderableWidget(new XButton(contentX, bottom, 96, UiKit.BTN_H, "重试上传",
                XButton.Style.PRIMARY, () -> {
            GisClient client = GisClient.get();
            if (client != null) {
                client.retryFailedUploads();
            }
            note("已重置失败任务，等待重传");
        }));
        this.addRenderableWidget(new XButton(contentX + 102, bottom, 96, UiKit.BTN_H, "强制同步",
                XButton.Style.SECONDARY, () -> {
            GisClient client = GisClient.get();
            if (client != null) {
                client.forceSync();
            }
            note("已触发增量同步");
        }));
        this.addRenderableWidget(new XButton(contentX + 204, bottom, 96, UiKit.BTN_H, "清理缓存",
                XButton.Style.DANGER, () -> {
            GisClient client = GisClient.get();
            if (client != null) {
                client.clearCache();
            }
            note("已清理本地缓存（世界注册保留）");
        }));
    }

    private void buildSettings() {
        GisClient client = GisClient.get();
        if (client == null) {
            return;
        }
        var config = client.config();
        int fieldX = contentX + 78;
        int y = contentY + 2;
        int fieldW = Math.max(120, contentW - 90);
        int row = 22;

        apiBaseBox = addField(fieldX, y, fieldW, config.apiBase());
        y += row;
        serverKeyBox = addField(fieldX, y, fieldW, config.serverKey());
        y += row;
        rateBox = addField(fieldX, y, 60, String.valueOf(config.uploadRatePerMinute()));
        batchBox = addField(fieldX + 70, y, 60, String.valueOf(config.uploadBatchChunks()));
        y += row;
        intervalBox = addField(fieldX, y, 60, String.valueOf(config.captureIntervalTicks()));
        radiusBox = addField(fieldX + 70, y, 60, String.valueOf(config.captureRadiusChunks()));
        stepBox = addField(fieldX + 140, y, 60, String.valueOf(config.sampleStep()));
        y += row + 2;

        int toggleW = Math.max(66, (contentW - 8) / 3 - 4);
        this.addRenderableWidget(new XButton(contentX, y, toggleW, 18,
                config.uploadEnabled() ? "上传：开" : "上传：关", XButton.Style.SECONDARY, () -> {
            toggleClient(c -> c.toggleUploadEnabled());
        }));
        this.addRenderableWidget(new XButton(contentX + toggleW + 4, y, toggleW, 18,
                config.captureEnabled() ? "采集：开" : "采集：关", XButton.Style.SECONDARY, () -> {
            toggleClient(c -> c.toggleCaptureEnabled());
        }));
        this.addRenderableWidget(new XButton(contentX + (toggleW + 4) * 2, y, toggleW, 18,
                config.hudEnabled() ? "HUD：开" : "HUD：关", XButton.Style.SECONDARY, () -> {
            toggleClient(c -> c.toggleHudEnabled());
        }));

        int bottom = py + ph - 26;
        this.addRenderableWidget(new XButton(contentX, bottom, 76, UiKit.BTN_H, "保存",
                XButton.Style.PRIMARY, this::saveSettings));
        this.addRenderableWidget(new XButton(contentX + 82, bottom, 76, UiKit.BTN_H, "重置",
                XButton.Style.SECONDARY, () -> {
            GisClient c = GisClient.get();
            if (c != null) {
                c.config().resetDefaults();
                c.saveConfig();
            }
            buildWidgets();
            note("已重置为默认配置并保存");
        }));
        this.addRenderableWidget(new XButton(contentX + 164, bottom, 100, UiKit.BTN_H,
                client.tokenStore().hasAccessToken() ? "退出登录" : "登录 OAuth",
                XButton.Style.SECONDARY, () -> {
            GisClient c = GisClient.get();
            if (c == null) {
                return;
            }
            if (c.tokenStore().hasAccessToken()) {
                c.logout();
                note("已退出 OAuth 登录");
            } else {
                String url = c.beginLogin();
                note("请在浏览器打开授权链接（已打印到日志）");
                top.xuanjian.map.core.util.GisLog.info("oauth", "授权链接: " + url);
            }
        }));
    }

    private void toggleClient(java.util.function.Consumer<GisClient> action) {
        GisClient client = GisClient.get();
        if (client != null) {
            action.accept(client);
        }
        buildWidgets();
    }

    private EditBox addField(int x, int y, int width, String value) {
        EditBox box = new EditBox(this.font, x, y, width, 18, Component.literal(""));
        box.setMaxLength(200);
        box.setValue(value == null ? "" : value);
        this.addRenderableWidget(box);
        return box;
    }

    private void saveSettings() {
        GisClient client = GisClient.get();
        if (client == null) {
            note("GIS 客户端未初始化", UiKit.DANGER);
            return;
        }
        var config = client.config();
        config.apiBase(apiBaseBox == null ? config.apiBase() : apiBaseBox.getValue().trim());
        config.serverKey(serverKeyBox == null ? config.serverKey() : serverKeyBox.getValue().trim());
        config.uploadRatePerMinute(parse(rateBox, config.uploadRatePerMinute()));
        config.uploadBatchChunks(parse(batchBox, config.uploadBatchChunks()));
        config.captureIntervalTicks(parse(intervalBox, config.captureIntervalTicks()));
        config.captureRadiusChunks(parse(radiusBox, config.captureRadiusChunks()));
        config.sampleStep(parse(stepBox, config.sampleStep()));
        client.saveConfig();
        note("已保存并重新绑定世界");
        buildWidgets();
    }

    private static int parse(EditBox box, int def) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void note(String message) {
        note(message, UiKit.SUCCESS);
    }

    private void note(String message, int color) {
        toast = message == null ? "" : message;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 5000;
        refreshData();
    }

    /* ==================== 渲染 ==================== */

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        computeLayout();
        UiKit.panel(g, px, py, pw, ph);
        UiKit.headerBar(g, px + 1, py + 1, pw - 2, UiKit.HEADER_H - 1);
        UiKit.roundedFill(g, px + 8, py + UiKit.HEADER_H + 8, UiKit.NAV_W, contentH, UiKit.NAV_BG);

        // widget（按钮、输入框）
        super.extractRenderState(g, mouseX, mouseY, delta);

        // 前景文字
        UiKit.text(g, this.font, "玄剑 GIS", px + 10, UiKit.vCenter(this.font, py, UiKit.HEADER_H), UiKit.TEXT);
        UiKit.text(g, this.font, page.title, contentX, py + 9, UiKit.TEXT_DIM);
        UiKit.divider(g, px + 8, px + pw - 8, py + UiKit.HEADER_H + 4);

        switch (page) {
            case OVERVIEW -> renderOverview(g);
            case LAYERS -> renderLayers(g);
            case WORLDS -> renderWorlds(g);
            case DATA -> renderData(g);
            case SETTINGS -> renderSettings(g);
        }

        if (!toast.isEmpty() && System.currentTimeMillis() < toastUntil) {
            UiKit.roundedFill(g, contentX, py + ph - 17, contentW, 13, 0xCC000000);
            UiKit.text(g, this.font, UiKit.ellipsis(this.font, toast, contentW - 8),
                    contentX + 4, py + ph - 14, toastColor);
        }
    }

    private void renderOverview(GuiGraphicsExtractor g) {
        int y = contentY + 2;
        line(g, snapshot.worldLine(), y, UiKit.TEXT);
        y += 12;
        line(g, snapshot.uploadLine(), y, UiKit.TEXT_DIM);
        y += 12;
        line(g, snapshot.cacheLine(), y, UiKit.TEXT_DIM);
        y += 12;
        line(g, snapshot.syncLine(), y, UiKit.TEXT_DIM);
        y += 12;
        line(g, snapshot.tokenState(), y, UiKit.TEXT_MUTED);
        y += 12;
        line(g, snapshot.contractHint(), y, UiKit.TEXT_MUTED);
        y += 12;
        line(g, snapshot.xaeroMode(), y, UiKit.TEXT_MUTED);
        y += 12;
        line(g, snapshot.statusLine(), y,
                snapshot.lastError() == null || snapshot.lastError().isBlank()
                        ? UiKit.SUCCESS : UiKit.WARNING);
        y += 14;
        // 缓存占比条：已上传比例
        GisStore.ChunkStats chunks = snapshot.stats().chunks();
        double ratio = chunks.total() == 0 ? 0 : chunks.uploaded() / (double) chunks.total();
        UiKit.bar(g, contentX, y, contentW - 4, 8, ratio, UiKit.SUCCESS);
        y += 12;
        line(g, "本地缓存后端：" + snapshot.stats().backend()
                + " · 会话内采集 " + snapshot.capturedThisSession() + " 区块", y, UiKit.TEXT_MUTED);
        if (pageYOverflow(y)) {
            line(g, "（内容超出，见数据/设置页）", contentY + contentH - 14, UiKit.TEXT_MUTED);
        }
    }

    private void renderLayers(GuiGraphicsExtractor g) {
        int y = contentY + 4;
        int[] counts = new int[]{0, 0, 0, 0};
        GisClient client = GisClient.get();
        if (client != null && client.session() != null && client.session().registered()) {
            counts = client.locator().layerCounts(client.session().dimension());
        }
        String[] labels = {"城区边界", "道路", "建筑 Marker", "其他 GIS 实体"};
        int[] countValues = {counts[0], counts[1], counts[2], counts[3]};
        for (int i = 0; i < labels.length; i++) {
            UiKit.card(g, contentX, y + i * 26, contentW - 84, 22, false);
            UiKit.text(g, this.font, labels[i], contentX + 6, y + i * 26 + 7, UiKit.TEXT);
            UiKit.textRight(g, this.font, countValues[i] + " 个对象",
                    contentX + contentW - 92, y + i * 26 + 7, UiKit.TEXT_MUTED);
        }
        int y2 = contentY + 4 + labels.length * 26 + 6;
        line(g, "图层开关只影响本地叠加渲染，不改变上传数据；Xaero 原有功能由 Xaero 自己维护。",
                y2, UiKit.TEXT_MUTED);
        line(g, snapshot.xaeroMode(), y2 + 12, UiKit.TEXT_MUTED);
    }

    private void renderWorlds(GuiGraphicsExtractor g) {
        int y = contentY + 2;
        line(g, "当前服务器 Key：" + (snapshot.serverKey().isBlank() ? "（未配置）" : snapshot.serverKey())
                + " · API " + snapshot.apiBase(), y, UiKit.TEXT_DIM);
        y += 14;
        List<RegisteredWorld> worlds = snapshot.worlds();
        if (worlds.isEmpty()) {
            line(g, "暂无已注册世界（未连接服务器或尚未 bootstrap）。", y, UiKit.WARNING);
            line(g, "未注册服务器仍可使用本地地图/HUD，但禁止上传（规范第六节）。", y + 12, UiKit.TEXT_MUTED);
            return;
        }
        for (RegisteredWorld world : worlds) {
            if (pageYOverflow(y)) {
                break;
            }
            boolean current = snapshot.session() != null && snapshot.session().worldId() == world.id();
            UiKit.card(g, contentX, y, contentW - 4, 22, false);
            if (current) {
                UiKit.roundedOutline(g, contentX, y, contentW - 4, 22, UiKit.ACCENT);
            }
            UiKit.text(g, this.font, (current ? "▶ " : "  ") + world.worldKey() + " #" + world.id(),
                    contentX + 5, y + 7, current ? UiKit.ACCENT : UiKit.TEXT);
            UiKit.textRight(g, this.font, world.status().labelZh() + " · " + world.mcVersion()
                            + (world.dimensions().isEmpty() ? "" : " · " + world.dimensions().size() + " 维度"),
                    contentX + contentW - 12, y + 7, UiKit.TEXT_MUTED);
            y += 26;
        }
        line(g, "客户端不能指定他人服务器的世界；上传前服务端会再次校验 serverKey/worldId/dimension。",
                y + 2, UiKit.TEXT_MUTED);
    }

    private void renderData(GuiGraphicsExtractor g) {
        GisStore.CacheStats stats = snapshot.stats();
        GisStore.ChunkStats chunks = stats.chunks();
        GisStore.JobStats jobs = stats.jobs();
        int y = contentY + 2;
        line(g, "本地缓存后端：" + stats.backend() + " · 数据库 " + (stats.dbBytes() / 1024) + " KiB", y, UiKit.TEXT);
        y += 12;
        line(g, "区块：" + chunks.total() + " 总 / " + chunks.captured() + " 已采集 / "
                + chunks.queued() + " 待上传 / " + chunks.uploaded() + " 已上传 / "
                + chunks.failed() + " 失败", y, UiKit.TEXT_DIM);
        y += 12;
        line(g, "上传任务：待传 " + jobs.pending() + " · 上传中 " + jobs.inFlight()
                + " · 重试 " + jobs.retry() + " · 完成 " + jobs.done() + " · 失败 " + jobs.failed(),
                y, UiKit.TEXT_DIM);
        y += 12;
        line(g, "GIS 对象缓存：" + stats.objectCount() + " 个（只存服务端 Published Revision）", y, UiKit.TEXT_DIM);
        y += 12;
        line(g, snapshot.syncLine(), y, UiKit.TEXT_DIM);
        y += 12;
        long lastUpload = stats.lastUploadAt();
        line(g, "最近成功上传：" + (lastUpload <= 0 ? "从未" : (System.currentTimeMillis() - lastUpload) / 1000 + " 秒前"),
                y, UiKit.TEXT_MUTED);
        y += 12;
        line(g, snapshot.statusLine(), y,
                stats.lastError() == null || stats.lastError().isBlank() ? UiKit.SUCCESS : UiKit.WARNING);
        y += 14;
        line(g, "失败数据不删除：上传失败只改状态，可点「重试上传」继续；断网恢复后自动续传。",
                y, UiKit.TEXT_MUTED);
        line(g, snapshot.contractHint(), y + 12, UiKit.TEXT_MUTED);
    }

    private void renderSettings(GuiGraphicsExtractor g) {
        int fieldX = contentX + 78;
        int y = contentY + 2;
        int row = 22;
        label(g, "API 地址", fieldX - 6, y);
        y += row;
        label(g, "服务器 Key", fieldX - 6, y);
        y += row;
        label(g, "限速/批大小", fieldX - 6, y);
        line(g, "次/分钟 · chunk/批", fieldX + 140, y + 5, UiKit.TEXT_MUTED);
        y += row;
        label(g, "采集间隔/半径/步长", fieldX - 6, y);
        y += row + 20;
        line(g, "上传限速与服务端 60 次/分钟对齐；采集半径过大反而浪费 CPU 与带宽。",
                contentX, y, UiKit.TEXT_MUTED);
        y += 12;
        line(g, "OAuth 使用统一账号（PKCE），scope read:user read:member；写权限由服务端 gis.submit 判定。",
                contentX, y, UiKit.TEXT_MUTED);
    }

    private void label(GuiGraphicsExtractor g, String text, int rightX, int boxY) {
        UiKit.textRight(g, this.font, text, rightX, boxY + 5, UiKit.TEXT_DIM);
    }

    private void line(GuiGraphicsExtractor g, String text, int y, int color) {
        line(g, text, contentX, y, color);
    }

    private void line(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        UiKit.text(g, this.font, UiKit.ellipsis(this.font, text, contentW - 4), x, y, color);
    }

    private boolean pageYOverflow(int y) {
        return y > py + ph - 40;
    }

    /* ==================== gametest 钩子 ==================== */

    /** 按中文标签切页（自绘按钮不是原版 Button，gametest 用它切页）。 */
    public boolean showPage(String label) {
        for (Page p : Page.values()) {
            if (p.label.equals(label)) {
                page = p;
                toast = "";
                buildWidgets();
                refreshData();
                return true;
            }
        }
        return false;
    }

    public String currentPageLabel() {
        return page.label;
    }

    public GisSnapshot currentSnapshot() {
        return snapshot;
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
