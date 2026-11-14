package top.xuanjian.map.core.config;

import top.xuanjian.map.core.util.GisLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * GIS 客户端配置（config/xuanjianmap.properties）。
 *
 * <p>所有可调项集中在这里：API 地址、服务器 Key、采集/上传/限速、HUD 与图层开关、调试。
 * 设置界面（GisSettingsScreen）读写本对象；保存后调用 {@link #save()}。
 *
 * <p>安全约定：这里只放 endpoint、clientId 等公开配置；access_token / refresh_token 由
 * {@code TokenStore} 单独存取，绝不写进本文件的注释或提交到仓库。
 */
public class GisConfig {

    private static final String TAG = "config";
    public static final String FILE_NAME = "xuanjianmap.properties";

    /* ---------------- 默认值 ---------------- */
    public static final String DEFAULT_API_BASE = "https://xuanjian.top";
    public static final String DEFAULT_SERVER_KEY = "yl";
    /** 契约固定值：X-GIS-Contract。 */
    public static final int GIS_CONTRACT = 1;
    public static final int DEFAULT_CAPTURE_INTERVAL_TICKS = 100;
    public static final int DEFAULT_CAPTURE_RADIUS_CHUNKS = 2;
    public static final int DEFAULT_SAMPLE_STEP = 1;
    public static final int DEFAULT_UPLOAD_RATE_PER_MINUTE = 60;
    public static final int DEFAULT_UPLOAD_BATCH_CHUNKS = 32;
    public static final int DEFAULT_HUD_THROTTLE_MS = 250;

    private final Path configDir;
    private final Path file;
    private final Properties raw = new Properties();

    private String apiBase = DEFAULT_API_BASE;
    private String serverKey = DEFAULT_SERVER_KEY;
    /** 可选：服务端令牌（契约 X-GIS-Server-Token），客户端一般留空，由 OAuth 鉴权。 */
    private String serverToken = "";
    private String oauthClientId = "xuanjian-map-mod";
    private String oauthRedirectUri = DEFAULT_API_BASE + "/api/oauth/mod-callback";

    private boolean captureEnabled = true;
    private int captureIntervalTicks = DEFAULT_CAPTURE_INTERVAL_TICKS;
    private int captureRadiusChunks = DEFAULT_CAPTURE_RADIUS_CHUNKS;
    private int sampleStep = DEFAULT_SAMPLE_STEP;
    /**
     * v2：是否采集/上传 surfaceBlockMatrix（地表方块 hash16）。
     * 服务端已于 v2 上线 decodeBatch bit3 + palette，默认开启；用户显式配置 false 可关闭。
     */
    private boolean captureSurfaceBlocks = true;

    private boolean uploadEnabled = true;
    private int uploadRatePerMinute = DEFAULT_UPLOAD_RATE_PER_MINUTE;
    private int uploadBatchChunks = DEFAULT_UPLOAD_BATCH_CHUNKS;
    private int uploadMaxAttempts = 8;
    /** 上传压缩编码：契约允许 zstd/gzip/deflate/identity，默认 zstd（纯 Java 实现）。 */
    private String contentEncoding = "zstd";

    private boolean hudEnabled = true;
    private boolean hudExpanded = true;
    private int hudThrottleMs = DEFAULT_HUD_THROTTLE_MS;

    private boolean layerDistricts = true;
    private boolean layerRoads = true;
    private boolean layerMarkers = true;
    private boolean layerOthers = true;

    /** blockHist 上传：生产契约已定义布局 u8 n + n*(u16 blockId,u32 count)，默认开启。 */
    private boolean uploadBlockHistogram = true;
    private boolean debug = false;

    public GisConfig(Path configDir) {
        this.configDir = configDir;
        this.file = configDir.resolve(FILE_NAME);
        load();
    }

    public Path configDir() {
        return configDir;
    }

    public Path file() {
        return file;
    }

    /* ---------------- 读写 ---------------- */

    public final void load() {
        if (!Files.exists(file)) {
            save();
            GisLog.info(TAG, "已生成默认 GIS 配置: " + file);
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            raw.clear();
            raw.load(in);
        } catch (IOException e) {
            GisLog.error(TAG, "读取 GIS 配置失败，使用默认值", e);
            return;
        }
        apiBase = str("api.base", DEFAULT_API_BASE);
        serverKey = str("gis.serverKey", DEFAULT_SERVER_KEY);
        serverToken = str("gis.serverToken", "");
        oauthClientId = str("oauth.clientId", "xuanjian-map-mod");
        oauthRedirectUri = str("oauth.redirectUri", apiBase + "/api/oauth/mod-callback");

        captureEnabled = bool("capture.enabled", true);
        captureIntervalTicks = clampInt(intOf("capture.intervalTicks", DEFAULT_CAPTURE_INTERVAL_TICKS), 20, 12000);
        captureRadiusChunks = clampInt(intOf("capture.radiusChunks", DEFAULT_CAPTURE_RADIUS_CHUNKS), 0, 8);
        sampleStep = clampInt(intOf("capture.sampleStep", DEFAULT_SAMPLE_STEP), 1, 32);
        captureSurfaceBlocks = bool("capture.surfaceBlocks", true);

        uploadEnabled = bool("upload.enabled", true);
        uploadRatePerMinute = clampInt(intOf("upload.ratePerMinute", DEFAULT_UPLOAD_RATE_PER_MINUTE), 1, 600);
        uploadBatchChunks = clampInt(intOf("upload.batchChunks", DEFAULT_UPLOAD_BATCH_CHUNKS), 1, 64);
        uploadMaxAttempts = clampInt(intOf("upload.maxAttempts", 8), 1, 30);
        contentEncoding = normalizeEncoding(str("upload.contentEncoding", "zstd"));

        hudEnabled = bool("hud.enabled", true);
        hudExpanded = bool("hud.expanded", true);
        hudThrottleMs = clampInt(intOf("hud.throttleMs", DEFAULT_HUD_THROTTLE_MS), 50, 5000);

        layerDistricts = bool("layer.districts", true);
        layerRoads = bool("layer.roads", true);
        layerMarkers = bool("layer.markers", true);
        layerOthers = bool("layer.others", true);

        uploadBlockHistogram = bool("upload.blockHistogram", true);
        debug = bool("debug", false);
    }

    public void save() {
        raw.setProperty("api.base", apiBase);
        raw.setProperty("gis.serverKey", serverKey);
        raw.setProperty("gis.serverToken", serverToken);
        raw.setProperty("oauth.clientId", oauthClientId);
        raw.setProperty("oauth.redirectUri", oauthRedirectUri);
        raw.setProperty("capture.enabled", String.valueOf(captureEnabled));
        raw.setProperty("capture.intervalTicks", String.valueOf(captureIntervalTicks));
        raw.setProperty("capture.radiusChunks", String.valueOf(captureRadiusChunks));
        raw.setProperty("capture.sampleStep", String.valueOf(sampleStep));
        raw.setProperty("capture.surfaceBlocks", String.valueOf(captureSurfaceBlocks));
        raw.setProperty("upload.enabled", String.valueOf(uploadEnabled));
        raw.setProperty("upload.ratePerMinute", String.valueOf(uploadRatePerMinute));
        raw.setProperty("upload.batchChunks", String.valueOf(uploadBatchChunks));
        raw.setProperty("upload.maxAttempts", String.valueOf(uploadMaxAttempts));
        raw.setProperty("upload.contentEncoding", contentEncoding);
        raw.setProperty("hud.enabled", String.valueOf(hudEnabled));
        raw.setProperty("hud.expanded", String.valueOf(hudExpanded));
        raw.setProperty("hud.throttleMs", String.valueOf(hudThrottleMs));
        raw.setProperty("layer.districts", String.valueOf(layerDistricts));
        raw.setProperty("layer.roads", String.valueOf(layerRoads));
        raw.setProperty("layer.markers", String.valueOf(layerMarkers));
        raw.setProperty("layer.others", String.valueOf(layerOthers));
        raw.setProperty("upload.blockHistogram", String.valueOf(uploadBlockHistogram));
        raw.setProperty("debug", String.valueOf(debug));
        try {
            Files.createDirectories(configDir);
            try (OutputStream out = Files.newOutputStream(file)) {
                raw.store(out, "Xuanjian Map Mod (GIS) 客户端配置 — 请勿写入任何 token");
            }
        } catch (IOException e) {
            GisLog.error(TAG, "保存 GIS 配置失败", e);
        }
    }

    /** 恢复默认（设置页“重置”用；仅内存生效，需 save()）。 */
    public void resetDefaults() {
        apiBase = DEFAULT_API_BASE;
        serverKey = DEFAULT_SERVER_KEY;
        serverToken = "";
        oauthClientId = "xuanjian-map-mod";
        oauthRedirectUri = DEFAULT_API_BASE + "/api/oauth/mod-callback";
        captureEnabled = true;
        captureIntervalTicks = DEFAULT_CAPTURE_INTERVAL_TICKS;
        captureRadiusChunks = DEFAULT_CAPTURE_RADIUS_CHUNKS;
        sampleStep = DEFAULT_SAMPLE_STEP;
        captureSurfaceBlocks = true;
        uploadEnabled = true;
        uploadRatePerMinute = DEFAULT_UPLOAD_RATE_PER_MINUTE;
        uploadBatchChunks = DEFAULT_UPLOAD_BATCH_CHUNKS;
        uploadMaxAttempts = 8;
        contentEncoding = "zstd";
        hudEnabled = true;
        hudExpanded = true;
        hudThrottleMs = DEFAULT_HUD_THROTTLE_MS;
        layerDistricts = true;
        layerRoads = true;
        layerMarkers = true;
        layerOthers = true;
        uploadBlockHistogram = true;
        debug = false;
    }

    /* ---------------- 工具 ---------------- */

    private String str(String key, String def) {
        String v = raw.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private boolean bool(String key, boolean def) {
        String v = raw.getProperty(key);
        if (v == null) {
            return def;
        }
        return "true".equalsIgnoreCase(v.trim());
    }

    private int intOf(String key, int def) {
        try {
            String v = raw.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /* ---------------- 访问器 ---------------- */

    public String apiBase() {
        return apiBase;
    }

    public void apiBase(String value) {
        this.apiBase = value == null || value.isBlank() ? DEFAULT_API_BASE : value.trim();
    }

    public String serverKey() {
        return serverKey;
    }

    public void serverKey(String value) {
        this.serverKey = value == null ? "" : value.trim();
    }

    public String serverToken() {
        return serverToken;
    }

    public void serverToken(String value) {
        this.serverToken = value == null ? "" : value.trim();
    }

    public String oauthClientId() {
        return oauthClientId;
    }

    public void oauthClientId(String value) {
        this.oauthClientId = value == null ? "" : value.trim();
    }

    public String oauthRedirectUri() {
        return oauthRedirectUri;
    }

    public void oauthRedirectUri(String value) {
        this.oauthRedirectUri = value == null ? "" : value.trim();
    }

    public boolean captureEnabled() {
        return captureEnabled;
    }

    public void captureEnabled(boolean value) {
        this.captureEnabled = value;
    }

    public int captureIntervalTicks() {
        return captureIntervalTicks;
    }

    public void captureIntervalTicks(int value) {
        this.captureIntervalTicks = clampInt(value, 20, 12000);
    }

    public int captureRadiusChunks() {
        return captureRadiusChunks;
    }

    public void captureRadiusChunks(int value) {
        this.captureRadiusChunks = clampInt(value, 0, 8);
    }

    public boolean captureSurfaceBlocks() {
        return captureSurfaceBlocks;
    }

    public void captureSurfaceBlocks(boolean value) {
        this.captureSurfaceBlocks = value;
    }

    public int sampleStep() {
        return sampleStep;
    }

    public void sampleStep(int value) {
        this.sampleStep = clampInt(value, 1, 32);
    }

    public boolean uploadEnabled() {
        return uploadEnabled;
    }

    public void uploadEnabled(boolean value) {
        this.uploadEnabled = value;
    }

    public int uploadRatePerMinute() {
        return uploadRatePerMinute;
    }

    public void uploadRatePerMinute(int value) {
        this.uploadRatePerMinute = clampInt(value, 1, 600);
    }

    public int uploadBatchChunks() {
        return uploadBatchChunks;
    }

    public void uploadBatchChunks(int value) {
        this.uploadBatchChunks = clampInt(value, 1, 64);
    }

    /** 校验并归一压缩编码（契约只允许四种）。 */
    public static String normalizeEncoding(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "zstd", "gzip", "deflate", "identity" -> v;
            default -> "zstd";
        };
    }

    public String contentEncoding() {
        return contentEncoding;
    }

    public void contentEncoding(String value) {
        this.contentEncoding = normalizeEncoding(value);
    }

    public int uploadMaxAttempts() {
        return uploadMaxAttempts;
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public void hudEnabled(boolean value) {
        this.hudEnabled = value;
    }

    public boolean hudExpanded() {
        return hudExpanded;
    }

    public void hudExpanded(boolean value) {
        this.hudExpanded = value;
    }

    public int hudThrottleMs() {
        return hudThrottleMs;
    }

    public void hudThrottleMs(int value) {
        this.hudThrottleMs = clampInt(value, 50, 5000);
    }

    public boolean layerDistricts() {
        return layerDistricts;
    }

    public void layerDistricts(boolean value) {
        this.layerDistricts = value;
    }

    public boolean layerRoads() {
        return layerRoads;
    }

    public void layerRoads(boolean value) {
        this.layerRoads = value;
    }

    public boolean layerMarkers() {
        return layerMarkers;
    }

    public void layerMarkers(boolean value) {
        this.layerMarkers = value;
    }

    public boolean layerOthers() {
        return layerOthers;
    }

    public void layerOthers(boolean value) {
        this.layerOthers = value;
    }

    public boolean uploadBlockHistogram() {
        return uploadBlockHistogram;
    }

    public void uploadBlockHistogram(boolean value) {
        this.uploadBlockHistogram = value;
    }

    public boolean debug() {
        return debug;
    }

    public void debug(boolean value) {
        this.debug = value;
    }
}
