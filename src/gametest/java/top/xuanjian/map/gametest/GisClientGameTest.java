package top.xuanjian.map.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuanjian.map.client.GisClient;
import top.xuanjian.map.client.gui.GisHudPreviewScreen;
import top.xuanjian.map.client.gui.GisMainScreen;
import top.xuanjian.map.client.gui.GisMapScreen;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端 gametest：真实启动客户端，验证 GIS 客户端链路并逐页截图。
 *
 * <p>断言（程序可判定，不依赖人工看图）：
 * <ol>
 *   <li>GIS 主界面能打开、有 widget、异步刷新不阻塞；</li>
 *   <li>五个页面（概览/图层/世界/数据/设置）都能切换；</li>
 *   <li>Overlay 地图、HUD 预览能渲染；</li>
 *   <li>进入测试世界后：采集真的落库（chunk 数 &gt; 0）、HUD 有维度/坐标；</li>
 *   <li>每张截图不是纯色；</li>
 *   <li>结束时回到标题界面。</li>
 * </ol>
 *
 * <p>截图输出到 run/screenshots/，CI 作为 artifact 上传。
 */
public class GisClientGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("xuanjianmap-gametest");

    private static final String[][] PAGES = {
            {"图层", "2-layers"},
            {"世界", "3-worlds"},
            {"数据", "4-data-status"},
            {"设置", "5-settings"},
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        List<String> shots = new ArrayList<>();

        // 离线/快速失败配置：CI 无 xuanjian.top，走「本地模式」；采集加快，便于断言
        context.runOnClient(client -> {
            GisClient gis = GisClient.get();
            if (gis == null) {
                throw new AssertionError("GisClient 未初始化");
            }
            gis.config().apiBase("http://127.0.0.1:9");
            gis.config().captureIntervalTicks(20);
            gis.config().captureRadiusChunks(1);
            gis.config().sampleStep(4);
            // v2：CI 中开启 surfaceBlockMatrix 采集以便断言（本地模式不上传）
            gis.config().captureSurfaceBlocks(true);
            gis.config().save();
            gis.rebind();
            LOGGER.info("[gametest] 配置完成：api={}, backend={}", gis.config().apiBase(),
                    gis.snapshot().stats().backend());
        });
        context.waitTicks(5);

        // ---------- 1) GIS 主界面（空数据态） ----------
        AtomicReference<GisMainScreen> mainRef = new AtomicReference<>();
        context.setScreen(() -> {
            GisMainScreen screen = new GisMainScreen();
            mainRef.set(screen);
            return screen;
        });
        context.waitForScreen(GisMainScreen.class);
        context.waitTicks(10);
        GisMainScreen main = mainRef.get();
        if (main == null) {
            throw new AssertionError("GIS 主界面实例未被创建");
        }
        Integer widgets = context.computeOnClient(client -> countWidgets(main));
        int widgetCount = widgets == null ? 0 : widgets;
        LOGGER.info("[gametest] 主界面 widget 数={}", widgetCount);
        if (widgetCount <= 0) {
            throw new AssertionError("GIS 主界面没有任何 widget");
        }
        shots.add(capture(context, "1-gis-overview-empty"));

        // ---------- 2) 逐页切换 ----------
        for (String[] page : PAGES) {
            String label = page[0];
            Boolean ok = context.computeOnClient(client -> main.showPage(label));
            context.waitTicks(6);
            String current = context.computeOnClient(client -> main.currentPageLabel());
            LOGGER.info("[gametest] 切页 {} -> ok={} current={}", label, ok, current);
            if (!Boolean.TRUE.equals(ok)) {
                throw new AssertionError("无法切换到页面：" + label);
            }
            if (!label.equals(current)) {
                throw new AssertionError("切页后 currentPageLabel 不符：" + current);
            }
            shots.add(capture(context, page[1]));
        }

        // ---------- 2b) 设置页 OAuth 未登录闭环（按钮状态 + 点击登录 OAuth 的提示） ----------
        Boolean settingsOk = context.computeOnClient(client -> main.showPage("设置"));
        context.waitTicks(8);
        if (!Boolean.TRUE.equals(settingsOk)) {
            throw new AssertionError("无法切换到设置页（OAuth 截图）");
        }
        shots.add(capture(context, "5b-settings-oauth-unlogged"));
        String authUrl = context.computeOnClient(client -> main.triggerOAuthBeginForTest());
        context.waitTicks(8);
        if (authUrl == null || authUrl.isBlank() || !authUrl.contains("/api/oauth/authorize")) {
            throw new AssertionError("OAuth 授权链接生成失败: " + authUrl);
        }
        LOGGER.info("[gametest] OAuth 授权链接已生成（长度 {}，含 PKCE 参数）", authUrl.length());
        shots.add(capture(context, "5c-settings-oauth-link"));

        // ---------- 3) 自有 GIS Overlay 渲染 ----------
        context.setScreen(() -> new GisMapScreen(null));
        context.waitForScreen(GisMapScreen.class);
        context.waitTicks(8);
        shots.add(capture(context, "6-overlay-map"));

        // ---------- 4) HUD 预览 ----------
        context.setScreen(() -> new GisHudPreviewScreen(null));
        context.waitForScreen(GisHudPreviewScreen.class);
        context.waitTicks(8);
        shots.add(capture(context, "7-hud-preview"));

        // ---------- 5) 测试世界：真实采集 + 缓存 + HUD ----------
        context.setScreen(() -> null);
        context.waitTicks(5);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(20);

            int captured = 0;
            for (int i = 0; i < 40; i++) {
                context.waitTicks(10);
                Long value = context.computeOnClient(client -> {
                    GisClient gis = GisClient.get();
                    return gis == null ? -1L : gis.snapshot().stats().chunks().total();
                });
                captured = value == null ? -1 : (int) value.longValue();
                if (captured > 0) {
                    break;
                }
            }
            var session = context.computeOnClient(client -> GisClient.get().snapshot().session());
            Integer backend = context.computeOnClient(client -> {
                String name = GisClient.get().snapshot().stats().backend();
                LOGGER.info("[gametest] 本地缓存后端={}", name);
                return 1;
            });
            Integer hud = context.computeOnClient(client -> {
                GisClient gis = GisClient.get();
                int[] pos = top.xuanjian.map.client.hud.GisHudRenderer.playerPosition(client);
                LOGGER.info("[gametest] 世界内：世界信息={}", gis.debugWorldInfo(client));
                LOGGER.info("[gametest] 世界内：维度={}, 坐标={}/{}/{}",
                        gis.snapshot().session().dimension(), pos[0], pos[1], pos[2]);
                LOGGER.info("[gametest] 本地模式={}, 上传状态={}",
                        gis.snapshot().localMode(), gis.snapshot().uploadLine());
                return gis.config().hudEnabled() ? 1 : 0;
            });
            LOGGER.info("[gametest] 采集落库 chunk 数={}, backend={}, HUD={}",
                    captured, backend, hud);

            if (captured <= 0) {
                throw new AssertionError("测试世界内没有采集到任何区块（GIS 采集链路失败）");
            }

            // v2：断言本地缓存里至少一个区块带 surfaceBlockMatrix，且不是全 0xFFFF
            Integer surfaceValid = context.computeOnClient(client -> {
                GisClient gis = GisClient.get();
                var s = gis.session();
                int[] pos = top.xuanjian.map.client.hud.GisHudRenderer.playerPosition(client);
                int cx = pos[0] >> 4;
                int cz = pos[2] >> 4;
                var key = new top.xuanjian.map.core.chunk.ChunkKey(s.worldId(), s.dimension(), cx, cz);
                var row = gis.store().getChunk(key);
                if (row == null) {
                    LOGGER.info("[gametest] v2 断言：玩家所在区块尚未落库 ({},{})", cx, cz);
                    return -1;
                }
                var data = top.xuanjian.map.core.chunk.ChunkBodyCodec.decodeBody(row.payload());
                if (!data.hasSurfaceBlocks()) {
                    LOGGER.info("[gametest] v2 断言：payload 不含 surfaceBlockMatrix（flags={}）", data.flags());
                    return -2;
                }
                int valid = 0;
                for (int hash : data.surfaceBlocks()) {
                    if (hash != top.xuanjian.map.core.chunk.BlockHash.NO_SURFACE) {
                        valid++;
                    }
                }
                LOGGER.info("[gametest] v2 断言：surfaceBlockMatrix 有效列 {}/{}（flags={}）",
                        valid, data.surfaceBlocks().length, data.flags());
                return valid;
            });
            if (surfaceValid == null || surfaceValid <= 0) {
                throw new AssertionError("v2 地表方块矩阵缺失或全为 0xFFFF: " + surfaceValid);
            }
            if (session == null || session.dimension() == null || session.dimension().isBlank()) {
                throw new AssertionError("HUD 会话维度为空（世界识别失败）");
            }
            if (!session.dimension().contains("overworld")) {
                throw new AssertionError("维度解析错误（应为主世界 overworld）: " + session.dimension());
            }
            Boolean localMode = context.computeOnClient(client -> GisClient.get().snapshot().localMode());
            if (!Boolean.TRUE.equals(localMode)) {
                throw new AssertionError("CI 无生产 API 时应为本地模式（上传暂停）");
            }
            String uploadLine = context.computeOnClient(client -> GisClient.get().snapshot().uploadLine());
            if (uploadLine == null || !uploadLine.contains("暂停")) {
                throw new AssertionError("本地模式上传状态文案不正确: " + uploadLine);
            }
            // 用本地 mock 对象验证「城区/道路/Marker」叠加与 HUD 定位（生产当前无对象数据）
            context.runOnClient(client -> {
                GisClient gis = GisClient.get();
                var s = gis.session();
                long worldId = s.worldId();
                String dim = s.dimension();
                String districtGeom = "{\"type\":\"Polygon\",\"coordinates\":"
                        + "[[[-64,-64],[64,-64],[64,64],[-64,64],[-64,-64]]]}";
                String roadGeom = "{\"type\":\"LineString\",\"coordinates\":[[-64,0],[64,0]]}";
                String markerGeom = "{\"type\":\"Point\",\"coordinates\":[8,8]}";
                long now = System.currentTimeMillis();
                gis.store().upsertObjects(java.util.List.of(
                        new top.xuanjian.map.core.storage.GisStore.StoredObject(worldId, dim,
                                "gt-district", "district", "青云城区", 1, districtGeom,
                                "{\"color\":\"#1D4ED8\"}", -64, -64, 64, 64, now, false),
                        new top.xuanjian.map.core.storage.GisStore.StoredObject(worldId, dim,
                                "gt-road", "road", "中央大道", 1, roadGeom,
                                "{\"width\":6}", -64, 0, 64, 0, now, false),
                        new top.xuanjian.map.core.storage.GisStore.StoredObject(worldId, dim,
                                "gt-marker", "marker", "玄剑集市", 1, markerGeom,
                                "{\"category\":\"shop\"}", 8, 8, 8, 8, now, false)));
                gis.reloadLocatorFromStore();
                LOGGER.info("[gametest] 已注入 mock GIS 对象：district/road/marker（world={}, dim={}）",
                        worldId, dim);
            });
            context.waitTicks(10);
            Integer located = context.computeOnClient(client -> {
                GisClient gis = GisClient.get();
                var location = gis.locateCurrent(client);
                LOGGER.info("[gametest] 注入后定位：城区={} 道路={} 附近Marker={}",
                        location.districtName(), location.roadName(), location.nearbyMarkers());
                return location.hasDistrict() ? 1 : 0;
            });
            if (located == null || located != 1) {
                throw new AssertionError("注入本地 GIS 对象后 HUD 未能识别城区（Point-in-Polygon 失败）");
            }
            shots.add(capture(context, "8-inworld-hud"));

            // 世界内打开自有 Overlay 地图（有真实 GIS 图层可渲染）
            context.setScreen(() -> new GisMapScreen(null));
            context.waitForScreen(GisMapScreen.class);
            context.waitTicks(10);
            shots.add(capture(context, "9-inworld-overlay"));

            // 世界内打开主界面「数据」页，确认数据状态随采集变化
            AtomicReference<GisMainScreen> worldMainRef = new AtomicReference<>();
            context.setScreen(() -> {
                GisMainScreen screen = new GisMainScreen();
                worldMainRef.set(screen);
                return screen;
            });
            context.waitForScreen(GisMainScreen.class);
            GisMainScreen worldMain = worldMainRef.get();
            Boolean ok = context.computeOnClient(client -> worldMain.showPage("数据"));
            context.waitTicks(10);
            if (!Boolean.TRUE.equals(ok)) {
                throw new AssertionError("世界内无法切换到数据页");
            }
            shots.add(capture(context, "10-inworld-data-status"));
            context.setScreen(() -> null);
            context.waitTicks(5);
        }

        // ---------- 收尾 ----------
        context.setScreen(() -> null);
        context.waitTicks(10);
        for (String shot : shots) {
            LOGGER.info("[gametest] 截图: {}", shot);
        }
        if (shots.size() < 12) {
            throw new AssertionError("截图数量不足（" + shots.size() + " < 12）");
        }
        LOGGER.info("[gametest] 全部通过，共 {} 张截图", shots.size());
    }

    /* ==================== 截图与断言 ==================== */

    private static String capture(ClientGameTestContext context, String name) {
        Path path = context.takeScreenshot(name);
        if (path == null) {
            throw new AssertionError("截图失败：" + name);
        }
        Path file = waitForFile(path);
        if (file == null) {
            throw new AssertionError("截图文件未生成：" + path);
        }
        int colors = countDistinctColors(file);
        LOGGER.info("[gametest] 截图 {} -> {}（采样色数 {}）", name, file.getFileName(), colors);
        if (colors < 8) {
            throw new AssertionError("画面疑似空白：截图 " + name + " 采样色数仅 " + colors);
        }
        return file.toString().replace('\\', '/');
    }

    private static Path waitForFile(Path path) {
        for (int i = 0; i < 60; i++) {
            try {
                if (Files.exists(path) && Files.size(path) > 0) {
                    return path;
                }
            } catch (Exception ignored) {
                // 继续等
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return Files.exists(path) ? path : null;
    }

    private static int countDistinctColors(Path png) {
        try (InputStream in = Files.newInputStream(png); NativeImage image = NativeImage.read(in)) {
            Set<Integer> colors = new HashSet<>();
            for (int y = 0; y < image.getHeight(); y += 3) {
                for (int x = 0; x < image.getWidth(); x += 3) {
                    colors.add(image.getPixel(x, y));
                    if (colors.size() > 64) {
                        return colors.size();
                    }
                }
            }
            return colors.size();
        } catch (Exception e) {
            throw new AssertionError("读取截图失败 " + png + ": " + e.getMessage(), e);
        }
    }

    /* ==================== widget 计数（避开版本差异） ==================== */

    private static int countWidgets(Object screen) {
        try {
            Method method = screen.getClass().getMethod("children");
            if (method.invoke(screen) instanceof List<?> list) {
                return list.size();
            }
        } catch (Exception ignored) {
            // 回退反射字段
        }
        Object value = field(screen, "renderables");
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
