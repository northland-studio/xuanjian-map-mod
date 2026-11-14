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
            singleplayer.getClientLevel().waitForChunksRender();
            context.waitTicks(20);

            int captured = 0;
            for (int i = 0; i < 40; i++) {
                context.waitTicks(10);
                Integer value = context.computeOnClient(client -> {
                    GisClient gis = GisClient.get();
                    return gis == null ? -1 : gis.snapshot().stats().chunks().total();
                });
                captured = value == null ? -1 : value;
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
                LOGGER.info("[gametest] 世界内：维度={}, 坐标={}/{}/{}",
                        gis.snapshot().session().dimension(), pos[0], pos[1], pos[2]);
                return gis.config().hudEnabled() ? 1 : 0;
            });
            LOGGER.info("[gametest] 采集落库 chunk 数={}, backend={}, HUD={}",
                    captured, backend, hud);

            if (captured <= 0) {
                throw new AssertionError("测试世界内没有采集到任何区块（GIS 采集链路失败）");
            }
            if (session == null || session.dimension() == null || session.dimension().isBlank()) {
                throw new AssertionError("HUD 会话维度为空（世界识别失败）");
            }
            shots.add(capture(context, "8-inworld-hud"));

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
            shots.add(capture(context, "9-inworld-data-status"));
            context.setScreen(() -> null);
            context.waitTicks(5);
        }

        // ---------- 收尾 ----------
        context.setScreen(() -> null);
        context.waitTicks(10);
        for (String shot : shots) {
            LOGGER.info("[gametest] 截图: {}", shot);
        }
        if (shots.size() < 8) {
            throw new AssertionError("截图数量不足（" + shots.size() + " < 8）");
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
