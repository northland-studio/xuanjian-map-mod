package top.xuanjian.map.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import top.xuanjian.map.client.capture.ChunkCaptureService;
import top.xuanjian.map.client.compat.ClipboardCompat;
import top.xuanjian.map.client.compat.McReflect;
import top.xuanjian.map.client.compat.XaeroCompat;
import top.xuanjian.map.core.compress.BlobCodecs;
import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.geo.GisLocator;
import top.xuanjian.map.core.geo.LocationInfo;
import top.xuanjian.map.core.hud.HudText;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.net.ApiException;
import top.xuanjian.map.core.net.GisApi;
import top.xuanjian.map.core.net.HttpGisApi;
import top.xuanjian.map.core.net.OAuthCodeParser;
import top.xuanjian.map.core.net.OAuthTokens;
import top.xuanjian.map.core.net.ObjectJson;
import top.xuanjian.map.core.net.PkceOAuth;
import top.xuanjian.map.core.net.TokenStore;
import top.xuanjian.map.core.storage.GisStore;
import top.xuanjian.map.core.storage.StoreFactory;
import top.xuanjian.map.core.storage.SynchronizedStore;
import top.xuanjian.map.core.upload.Uploader;
import top.xuanjian.map.core.util.GisLog;
import top.xuanjian.map.core.util.TimeSource;
import top.xuanjian.map.core.world.BootstrapInfo;
import top.xuanjian.map.core.world.WorldSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GIS 客户端服务容器（单例）。
 *
 * <p>职责：装配配置/SQLite/API/上传/同步/采集/定位，并把所有阻塞工作丢到后台线程；
 * 渲染线程只读 {@link GisSnapshot} 与 {@link GisLocator}。
 */
public final class GisClient {

    private static final String TAG = "gis-client";
    private static volatile GisClient INSTANCE;

    public static GisClient get() {
        return INSTANCE;
    }

    public static synchronized void initialize() {
        if (INSTANCE == null) {
            INSTANCE = new GisClient();
        }
    }

    private final Path dataDir;
    private final GisConfig config;
    private final TokenStore tokenStore;
    private final HttpGisApi api;
    private final PkceOAuth oauth;
    private final SynchronizedStore store;
    private final GisLocator locator = new GisLocator();
    private final Uploader uploader;
    private final ChunkCaptureService capture;

    private final ExecutorService dbExec = daemon("xjm-db");
    private final ExecutorService netExec = daemon("xjm-net");

    private volatile BootstrapInfo bootstrap;
    private volatile WorldSession session = WorldSession.NONE;
    private volatile GisSnapshot snapshot = GisSnapshot.EMPTY;
    private volatile String lastSyncError = "";
    private volatile String lastUploadError = "";
    private volatile long lastSyncAt;
    private volatile boolean syncing;
    private volatile boolean binding;
    private volatile boolean uploading;
    private volatile int capturedThisSession;
    private volatile String xaeroMode = XaeroCompat.mode();

    private long lastBindAttempt;
    private long lastSyncAttempt;
    private long lastUiRefresh;
    private int tickCounter;
    private int bindFailures;
    private LocationInfo lastLocation = LocationInfo.EMPTY;
    private LocationInfo cachedLocation = LocationInfo.EMPTY;
    private long locationAt;
    private String loginVerifier = "";
    private volatile String pendingAuthorizeUrl = "";
    /** v2 补采：本地缺 surfaceBlockMatrix(bit3) 的区块坐标（只存 chunk 坐标，用于靠近时重采）。 */
    private final Set<Long> surfaceRecapture = ConcurrentHashMap.newKeySet();

    private GisClient() {
        this.dataDir = FabricLoader.getInstance().getConfigDir().resolve("xuanjianmap");
        this.config = new GisConfig(dataDir);
        this.tokenStore = new TokenStore(dataDir);
        this.api = new HttpGisApi(config, tokenStore);
        this.oauth = new PkceOAuth(config, tokenStore);
        this.store = new SynchronizedStore(StoreFactory.open(dataDir));
        BlobCodecs.verifyAtStartup();
        this.uploader = new Uploader(store, api, config, Limits.DEFAULT, TimeSource.SYSTEM,
                BlobCodecs.forUpload(config));
        this.capture = new ChunkCaptureService(this);
        GisLog.info(TAG, "GIS 客户端初始化完成：backend=" + store.backend()
                + ", api=" + config.apiBase() + ", serverKey=" + config.serverKey());
        // v2：旧版本默认关闭 surfaceBlockMatrix 写进配置的，启动时自动迁移为开启并回写
        if (config.migrated()) {
            config.save();
            config.clearMigrated();
            GisLog.info(TAG, "v2 地表方块采集：已把旧配置 capture.surfaceBlocks=false 迁移为默认开启");
        }
        // v2：旧版本默认关闭 surfaceBlockMatrix 写进配置的，启动时自动迁移为开启并回写
        if (config.migrated()) {
            config.save();
            config.clearMigrated();
            GisLog.info(TAG, "v2 地表方块采集：已把旧配置 capture.surfaceBlocks=false 迁移为默认开启");
        }
        dbExec.submit(this::refreshSnapshot);
    }

    /* ==================== 访问器 ==================== */

    public GisConfig config() {
        return config;
    }

    public TokenStore tokenStore() {
        return tokenStore;
    }

    public PkceOAuth oauth() {
        return oauth;
    }

    public GisApi api() {
        return api;
    }

    public GisStore store() {
        return store;
    }

    public GisLocator locator() {
        return locator;
    }

    public Uploader uploader() {
        return uploader;
    }

    public ChunkCaptureService capture() {
        return capture;
    }

    public BootstrapInfo bootstrap() {
        return bootstrap;
    }

    public WorldSession session() {
        return session;
    }

    public GisSnapshot snapshot() {
        return snapshot;
    }

    public Path dataDir() {
        return dataDir;
    }

    public String xaeroMode() {
        return xaeroMode;
    }

    /* ==================== tick（渲染线程，只做轻量工作） ==================== */

    public void tick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        tickCounter++;
        if (mc.player == null) {
            if (session != null && !session.dimension().isBlank()) {
                session = WorldSession.NONE;
                lastLocation = LocationInfo.EMPTY;
                refreshSnapshotSoon();
            }
            return;
        }
        String dimension = readDimension(mc);
        if (tickCounter % 20 == 0) {
            maybeBind(dimension);
            maybeSync();
            maybeUpload();
        }
        if (tickCounter % 40 == 0 && System.currentTimeMillis() - lastUiRefresh > 1500) {
            lastUiRefresh = System.currentTimeMillis();
            refreshSnapshotSoon();
        }
        capture.tick(mc);
    }

    private void maybeBind(String dimension) {
        long now = System.currentTimeMillis();
        if (binding) {
            return;
        }
        boolean retryAfterFailure = session != null && !session.lastError().isBlank();
        // 未绑定（服务器未注册/世界归档/网络不可用）时逐步退避，避免反复请求轰炸接口
        long interval = retryAfterFailure
                ? Math.min(300_000L, 60_000L * Math.max(1, bindFailures))
                : 5_000L;
        if (now - lastBindAttempt < interval) {
            return;
        }
        if (session.registered() && session.dimension().equals(dimension)) {
            return;
        }
        lastBindAttempt = now;
        binding = true;
        netExec.submit(() -> {
            try {
                bindWorld(dimension);
            } finally {
                binding = false;
            }
        });
    }

    /** 绑定失败时进入本地模式：仍可采集/HUD/叠加，但永远不上传（规范第六节）。 */
    private WorldSession localSession(String serverKey, String dimension, String error) {
        bindFailures++;
        return new WorldSession(serverKey == null ? "" : serverKey, 0, "", dimension, "", false,
                "LOCAL", System.currentTimeMillis(), error);
    }

    private void bindWorld(String dimension) {
        String serverKey = config.serverKey();
        if (serverKey == null || serverKey.isBlank()) {
            session = localSession("", dimension, "未配置 gis.serverKey（本地模式，上传暂停）");
            refreshSnapshotSoon();
            return;
        }
        try {
            BootstrapInfo info = api.bootstrap(serverKey);
            this.bootstrap = info;
            store.putWorlds(serverKey, info.worlds());
            RegisteredWorld world = info.currentWorld();
            if (world == null) {
                session = localSession(serverKey, dimension, archivedReason(info));
                GisLog.warn(TAG, "无 ACTIVE 世界，进入本地模式（上传暂停）: " + session.lastError());
                refreshSnapshotSoon();
                return;
            }
            GisApi.ResolveResult resolved = api.resolveWorld(serverKey, world.id(),
                    world.worldKey(), dimension);
            session = new WorldSession(serverKey, resolved.worldId(), resolved.worldKey(),
                    Dimensions.normalize(resolved.dimension()), world.mcVersion(),
                    resolved.ok(), resolved.status(), System.currentTimeMillis(), "");
            bindFailures = 0;
            GisLog.info(TAG, "世界绑定成功: " + session.worldKey() + " #" + session.worldId()
                    + " dim=" + session.dimension());
            syncNow();
            refreshSurfaceBackfill();
        } catch (ApiException e) {
            session = localSession(serverKey, dimension, bindErrorReason(e));
            GisLog.warn(TAG, "世界绑定失败，进入本地模式（上传暂停）: " + e.describe());
        } finally {
            refreshSnapshotSoon();
        }
    }

    /** 无 ACTIVE 世界时的明确原因（世界全部归档 / 服务器尚未注册世界）。 */
    private static String archivedReason(BootstrapInfo info) {
        boolean hasArchived = info != null && info.worlds().stream()
                .anyMatch(w -> w.status() == top.xuanjian.map.core.model.WorldStatus.ARCHIVED);
        if (hasArchived) {
            return "服务器世界已归档（无 ACTIVE 世界），上传暂停；官方恢复后自动继续";
        }
        return "服务器未注册 ACTIVE 世界（本地模式），上传暂停；注册后自动继续";
    }

    private static String bindErrorReason(ApiException e) {
        return switch (e.code()) {
            case "server_not_registered" -> "服务器未在玄剑 GIS 注册（本地模式），上传暂停";
            case "world_not_registered" -> "世界未注册（本地模式），上传暂停";
            case "world_archived" -> "世界已归档，上传暂停；官方恢复 ACTIVE 后自动继续";
            case "dimension_mismatch" -> "该维度未登记，上传暂停（可切换维度或联系管理员）";
            case "unauthorized" -> "未登录 OAuth（本地模式），上传暂停";
            default -> "无法绑定世界（" + e.code() + "），上传暂停";
        };
    }

    private void maybeSync() {
        if (syncing || !session.registered()) {
            return;
        }
        int interval = bootstrap == null ? 60 : bootstrap.syncIntervalSeconds();
        long now = System.currentTimeMillis();
        if (now - lastSyncAttempt < Math.max(15, interval) * 1000L) {
            return;
        }
        lastSyncAttempt = now;
        syncing = true;
        netExec.submit(() -> {
            try {
                syncNow();
            } finally {
                syncing = false;
                refreshSnapshotSoon();
            }
        });
    }

    /** 增量同步：对象 upsert + tombstone 删除 + cursor 落库 + 重建空间索引。 */
    public void syncNow() {
        WorldSession current = session;
        if (current == null || !current.registered()) {
            return;
        }
        try {
            for (int page = 0; page < 20; page++) {
                String cursor = store.syncCursor(current.worldId(), current.dimension());
                GisApi.SyncResult result = api.sync(current.worldId(), current.dimension(), cursor);
                List<GisStore.StoredObject> objects = new ArrayList<>();
                for (var object : result.objects()) {
                    objects.add(ObjectJson.toStored(object));
                }
                if (!objects.isEmpty()) {
                    store.upsertObjects(objects);
                }
                for (var deleted : result.deleted()) {
                    store.markObjectsDeleted(current.worldId(), current.dimension(),
                            deleted.id(), deleted.revision());
                }
                store.setSyncCursor(current.worldId(), current.dimension(), result.cursor());
                if (!result.hasMore()) {
                    break;
                }
            }
            reloadLocator(current.worldId(), current.dimension());
            lastSyncAt = System.currentTimeMillis();
            lastSyncError = "";
        } catch (ApiException e) {
            lastSyncError = e.describe();
            GisLog.warn(TAG, "GIS 对象同步失败: " + e.describe());
            // 同步失败也要能用本地缓存
            reloadLocator(current.worldId(), current.dimension());
        }
    }

    private void reloadLocator(long worldId, String dimension) {
        List<GisStore.StoredObject> stored = store.loadObjects(worldId, dimension, false);
        List<top.xuanjian.map.core.model.GisObject> objects = new ArrayList<>(stored.size());
        for (GisStore.StoredObject row : stored) {
            var object = ObjectJson.fromStored(row);
            if (object != null) {
                objects.add(object);
            }
        }
        locator.replaceDimension(dimension, objects);
    }

    /* ==================== v2 地表方块补采（旧区块重传） ==================== */

    /** 该区块是否需要补采（缺 surfaceBlockMatrix 且当前世界/维度匹配）。 */
    public boolean needsSurfaceRecapture(int chunkX, int chunkZ) {
        if (!config.captureSurfaceBlocks()) {
            return false;
        }
        WorldSession current = session;
        if (current == null || !current.registered() || current.dimension() == null) {
            return false;
        }
        return surfaceRecapture.contains(coordKey(chunkX, chunkZ));
    }

    /** 补采完成后从待补采集合移除（上传由 Uploader 自动接管，同 chunk key upsert）。 */
    public void markSurfaceRecaptured(int chunkX, int chunkZ) {
        surfaceRecapture.remove(coordKey(chunkX, chunkZ));
    }

    /** 待补采区块数量（数据页/日志展示）。 */
    public int surfaceBackfillCount() {
        return surfaceRecapture.size();
    }

    /**
     * 扫描本地缓存中「已存在但缺 surfaceBlockMatrix」的区块，加入待补采集合。
     * 玩家靠近时 ChunkCaptureService 会重采并重传（不要求用户重新跑图）。
     */
    public void refreshSurfaceBackfill() {
        if (!config.captureSurfaceBlocks()) {
            surfaceRecapture.clear();
            return;
        }
        WorldSession current = session;
        if (current == null || !current.registered()) {
            return;
        }
        dbExec.submit(() -> {
            try {
                List<int[]> coords = store.chunksNeedingSurface(current.worldId(),
                        current.dimension(), 8192);
                surfaceRecapture.clear();
                for (int[] c : coords) {
                    surfaceRecapture.add(coordKey(c[0], c[1]));
                }
                if (!coords.isEmpty()) {
                    GisLog.info(TAG, "v2 地表方块补采：本地有 " + coords.size()
                            + " 个旧区块待补（玩家靠近时自动重采并重传）");
                }
            } catch (RuntimeException e) {
                GisLog.warn(TAG, "扫描待补采区块失败: " + e);
            }
        });
    }

    private static long coordKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
    /** 从本地 SQLite 重新加载 GIS 对象到内存空间索引（测试/手动刷新用）。 */
    public void reloadLocatorFromStore() {
        WorldSession current = session;
        if (current == null || current.dimension() == null || current.dimension().isBlank()) {
            return;
        }
        reloadLocator(current.worldId(), current.dimension());
    }

    private void maybeUpload() {
        if (uploading || !config.uploadEnabled() || !session.canUpload()) {
            return;
        }
        uploading = true;
        netExec.submit(() -> {
            try {
                Uploader.TickResult result = uploader.tick(session);
                if (result.uploadedJobs() > 0) {
                    lastUploadError = "";
                } else if (!result.lastError().isBlank()) {
                    // 保留最近一次上传错误（403 forbidden(required) 会带“缺少权限: gis.submit”）
                    lastUploadError = result.lastError();
                }
                if (result.uploadedJobs() > 0 || result.planned() > 0 || result.failedJobs() > 0) {
                    GisLog.info(TAG, "上传: planned=" + result.planned()
                            + ", uploaded=" + result.uploadedJobs()
                            + ", failed=" + result.failedJobs()
                            + (result.lastError().isBlank() ? "" : ", err=" + result.lastError()));
                }
            } catch (RuntimeException e) {
                GisLog.error(TAG, "上传轮询异常", e);
            } finally {
                uploading = false;
                refreshSnapshotSoon();
            }
        });
    }

    /* ==================== 定位（HUD 用，节流） ==================== */

    public LocationInfo locateCurrent(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - locationAt < config.hudThrottleMs()) {
            return cachedLocation;
        }
        locationAt = now;
        if (mc == null || mc.player == null || session == null
                || session.dimension() == null || session.dimension().isBlank()) {
            cachedLocation = LocationInfo.EMPTY;
            return cachedLocation;
        }
        Object px = McReflect.call(mc.player, new String[]{"getX"});
        Object pz = McReflect.call(mc.player, new String[]{"getZ"});
        if (!(px instanceof Number nx) || !(pz instanceof Number nz)) {
            return cachedLocation;
        }
        LocationInfo fresh = locator.locate(session.dimension(), nx.doubleValue(), nz.doubleValue());
        if (lastLocation != null && !fresh.equals(lastLocation)) {
            String message = HudText.transitionMessage(lastLocation, fresh);
            if (message != null) {
                notifyPlayer(mc, message);
            }
        }
        lastLocation = fresh;
        cachedLocation = fresh;
        return fresh;
    }

    public void notifyPlayer(Minecraft mc, String message) {
        if (mc.player == null) {
            return;
        }
        try {
            Object component = net.minecraft.network.chat.Component.literal("§b[玄剑GIS] §f" + message);
            // 1.21：displayClientMessage(Component, boolean)；26：sendSystemMessage(Component)
            if (McReflect.hasMethod(mc.player, "displayClientMessage", component, false)) {
                McReflect.call(mc.player, "displayClientMessage", component, false);
            } else {
                McReflect.call(mc.player, "sendSystemMessage", component);
            }
        } catch (Exception e) {
            GisLog.debug(TAG, "发送提示失败: " + e);
        }
    }

    /* ==================== 快照 ==================== */

    public void refreshSnapshotSoon() {
        dbExec.submit(this::refreshSnapshot);
    }

    /** 后台线程执行（DB/编码/压缩等阻塞工作）。 */
    public void submitDb(Runnable task) {
        dbExec.submit(task);
    }

    /** 后台网络线程执行（上传/同步）。 */
    public void submitNet(Runnable task) {
        netExec.submit(task);
    }

    private void refreshSnapshot() {
        try {
            WorldSession current = session;
            GisStore.ChunkStats chunks = store.chunkStats();
            GisStore.JobStats jobs = store.jobStats();
            long objectCount = 0;
            if (current != null && current.registered()) {
                objectCount = store.loadObjects(current.worldId(), current.dimension(), false).size();
            }
            GisStore.CacheStats stats = new GisStore.CacheStats(chunks, jobs, objectCount,
                    store.dbBytes(), store.backend(), store.lastUploadAt(), lastSyncError);
            String tokenState;
            if (!tokenStore.loggedIn()) {
                tokenState = "OAuth：未登录（点「登录 OAuth」后按提示完成，上传需要登录）";
            } else {
                String nickname = tokenStore.nickname();
                boolean expired = tokenStore.tokens().isExpired(System.currentTimeMillis());
                tokenState = "OAuth：已登录"
                        + (nickname == null || nickname.isBlank() ? "" : "（" + nickname + "）")
                        + (expired ? " · access_token 已过期（将自动刷新）" : " · 有效");
            }
            String combinedError = !lastUploadError.isBlank() ? lastUploadError : lastSyncError;
            BootstrapInfo info = bootstrap;
            String contractHint = info == null ? "契约 v1（未获取 limits）"
                    : "契约 v" + info.contract() + " · plan≤" + info.limits().maxPlanChunks()
                    + " · 单批≤" + info.limits().maxBatchChunks()
                    + " · 压缩≤" + (info.limits().maxCompressedBytes() / 1024 / 1024) + "MiB";
            snapshot = new GisSnapshot(current, info, store.worlds(), stats, cachedLocation, tokenState,
                    combinedError, lastSyncAt, syncing, xaeroMode, config.hudExpanded(),
                    config.apiBase(), config.serverKey(), capturedThisSession, contractHint);
        } catch (RuntimeException e) {
            GisLog.error(TAG, "刷新快照失败", e);
        }
    }

    public void incrementCaptured() {
        capturedThisSession++;
    }

    /* ==================== 用户动作（UI 调用） ==================== */

    public void saveConfig() {
        config.save();
        refreshSnapshot();
        refreshSurfaceBackfill();
        // 配置变化后重新绑定
        lastBindAttempt = 0;
        session = WorldSession.NONE;
    }

    public void clearCache() {
        dbExec.submit(() -> {
            try {
                store.clearCache();
                locator.clearAll();
                capturedThisSession = 0;
                GisLog.info(TAG, "用户清理了本地 GIS 缓存");
            } finally {
                refreshSnapshot();
            }
        });
    }

    public void retryFailedUploads() {
        netExec.submit(() -> {
            int count = uploader.retryFailed();
            GisLog.info(TAG, "手动重试失败上传: " + count);
            refreshSnapshotSoon();
        });
    }

    public void forceSync() {
        lastSyncAttempt = 0;
        maybeSync();
    }

    /** 重新走 bootstrap + resolve-world（世界切换/配置修改后）。 */
    public void rebind() {
        session = WorldSession.NONE;
        lastBindAttempt = 0;
        binding = false;
        bindFailures = 0;
    }

    public void toggleUploadEnabled() {
        config.uploadEnabled(!config.uploadEnabled());
        config.save();
        refreshSnapshot();
    }

    public void toggleCaptureEnabled() {
        config.captureEnabled(!config.captureEnabled());
        config.save();
        refreshSnapshot();
    }

    public void toggleHudEnabled() {
        config.hudEnabled(!config.hudEnabled());
        config.save();
        refreshSnapshot();
    }

    public void toggleHudExpanded() {
        config.hudExpanded(!config.hudExpanded());
        config.save();
        refreshSnapshot();
    }

    public boolean toggleLayer(int index) {
        boolean value;
        switch (index) {
            case 0 -> {
                value = !config.layerDistricts();
                config.layerDistricts(value);
            }
            case 1 -> {
                value = !config.layerRoads();
                config.layerRoads(value);
            }
            case 2 -> {
                value = !config.layerMarkers();
                config.layerMarkers(value);
            }
            default -> {
                value = !config.layerOthers();
                config.layerOthers(value);
            }
        }
        config.save();
        refreshSnapshot();
        return value;
    }


    /* ==================== OAuth 登录（UI 闭环） ==================== */

    /** 登录链接生成结果：是否复制成功、链接、复制方式、链接文件路径（兜底）。 */
    public record LoginBegin(boolean copied, String url, String method, String filePath) {
        public static LoginBegin failed(String url, String filePath) {
            return new LoginBegin(false, url, "none", filePath);
        }
    }

    /** 登录结果（用于 UI 提示，成功时带昵称）。 */
    public record LoginResult(boolean ok, String message, String nickname) {
    }

    /** 最近一次生成的授权链接（设置页展示，便于用户手动 Ctrl+A/Ctrl+C 复制）。 */
    public String pendingAuthorizeUrl() {
        return pendingAuthorizeUrl;
    }

    /** 授权链接兜底文件（剪贴板不可用时写入，用户可手动打开复制）。 */
    public java.nio.file.Path authorizeUrlFile() {
        return dataDir.resolve("oauth-authorize-url.txt");
    }

    /** code 兜底文件：剪贴板不可用时，用户可把 code 写进该文件后点「完成登录」。 */
    public java.nio.file.Path oauthCodeFile() {
        return dataDir.resolve("oauth-code.txt");
    }

    /**
     * 生成 PKCE 授权链接：先尝试系统剪贴板（GLFW→KeyboardHandler→AWT 多重兜底），
     * 全部失败时写入 {@code config/xuanjianmap/oauth-authorize-url.txt}，
     * 保证用户仍能手动复制（不依赖剪贴板权限）。
     */
    public LoginBegin beginLoginAndCopy() {
        String url = beginLogin();
        pendingAuthorizeUrl = url;
        ClipboardCompat.WriteResult write = ClipboardCompat.write(url);
        String filePath = "";
        if (write.ok()) {
            GisLog.info(TAG, "OAuth 授权链接已复制到剪贴板（方式=" + write.method() + "）: " + url);
        } else {
            try {
                java.nio.file.Path file = authorizeUrlFile();
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, url);
                filePath = file.toAbsolutePath().toString();
                GisLog.info(TAG, "剪贴板不可用，授权链接已写入文件: " + filePath);
            } catch (Exception e) {
                GisLog.warn(TAG, "写授权链接文件失败: " + e);
            }
            GisLog.info(TAG, "OAuth 授权链接: " + url);
        }
        return new LoginBegin(write.ok(), url, write.method(), filePath);
    }

    public String beginLogin() {
        loginVerifier = oauth.newVerifier();
        return oauth.authorizeUrl(loginVerifier, "xjm");
    }

    /** 重新复制最近的授权链接（设置页「复制链接」按钮用）。 */
    public boolean copyPendingAuthorizeUrl() {
        if (pendingAuthorizeUrl == null || pendingAuthorizeUrl.isBlank()) {
            return false;
        }
        return ClipboardCompat.write(pendingAuthorizeUrl).ok();
    }

    /**
     * 从系统剪贴板读取 code 并完成登录（容错：纯 code / code=… / 完整回调 URL）。
     * 剪贴板不可用时尝试读 {@code oauth-code.txt}，仍失败则提示用输入框手动粘贴。
     */
    public LoginResult completeLoginFromClipboard() {
        String clipboard = ClipboardCompat.read();
        if (clipboard != null && !clipboard.isBlank()) {
            return completeLoginWithCode(clipboard);
        }
        try {
            java.nio.file.Path file = oauthCodeFile();
            if (java.nio.file.Files.exists(file)) {
                String text = java.nio.file.Files.readString(file).trim();
                if (!text.isEmpty()) {
                    GisLog.info(TAG, "剪贴板不可用，改用 oauth-code.txt 中的 code");
                    return completeLoginWithCode(text);
                }
            }
        } catch (Exception e) {
            GisLog.warn(TAG, "读取 oauth-code.txt 失败: " + e);
        }
        return new LoginResult(false,
                "剪贴板不可用：请在设置页输入框手动粘贴回调页的 code，再点「登录（输入框）」"
                        + "（也可把 code 写入 " + oauthCodeFile().toAbsolutePath() + "）", "");
    }

    /**
     * 用给定文本完成登录（供剪贴板流程与测试调用）。
     *
     * @param rawCodeOrUrl 纯 code、`code=xxx` 或完整回调 URL
     */
    public LoginResult completeLoginWithCode(String rawCodeOrUrl) {
        if (loginVerifier == null || loginVerifier.isBlank()) {
            return new LoginResult(false,
                    "请先点「登录 OAuth」生成授权链接（PKCE 校验需要本次会话的 code_verifier）", "");
        }
        String code = OAuthCodeParser.extractCode(rawCodeOrUrl);
        if (code == null || code.isBlank()) {
            return new LoginResult(false,
                    "未在剪贴板中找到授权码 code（可粘贴 code 本身或完整回调 URL）", "");
        }
        try {
            OAuthTokens tokens = oauth.exchangeCode(code, loginVerifier);
            String nickname = oauth.fetchNickname();
            if (nickname == null) {
                nickname = "";
            }
            tokenStore.save(tokens, nickname);
            loginVerifier = "";
            GisLog.info(TAG, "OAuth 登录成功" + (nickname.isBlank() ? "" : "：" + nickname));
            // 登录后重新绑定世界（bootstrap/上传需要 Bearer）
            rebind();
            refreshSnapshotSoon();
            return new LoginResult(true,
                    "已登录" + (nickname.isBlank() ? "（服务端未返回昵称）" : "：" + nickname), nickname);
        } catch (ApiException e) {
            GisLog.warn(TAG, "OAuth 登录失败: code=" + e.code() + ", " + e.getMessage());
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = "登录失败（" + e.code() + "）";
            }
            return new LoginResult(false, message, "");
        }
    }

    /** 旧接口保留（供命令行/调试）：用 code 兑换 token，失败返回 null。 */
    public OAuthTokens finishLogin(String code) {
        LoginResult result = completeLoginWithCode(code);
        return result.ok() ? tokenStore.tokens() : null;
    }

    public void logout() {
        oauth.logout();
        loginVerifier = "";
        rebind();
        refreshSnapshot();
    }

    /* ==================== 工具 ==================== */

    public String readDimension(Minecraft mc) {
        Object level = McReflect.field(mc, "level");
        if (level == null) {
            level = McReflect.call(mc, new String[]{"level", "getLevel"});
        }
        if (level == null) {
            return Dimensions.OVERWORLD;
        }
        Object key = McReflect.call(level, new String[]{"dimension", "getDimension"});
        if (key == null) {
            return Dimensions.OVERWORLD;
        }
        // 优先 location()/getLocation()；拿不到就清洗 ResourceKey.toString()（形如
        // "ResourceKey[minecraft:dimension / minecraft:overworld]"）
        Object location = McReflect.call(key, new String[]{"location", "getLocation"});
        String raw = location != null ? String.valueOf(location) : String.valueOf(key);
        return Dimensions.normalize(Dimensions.sanitize(raw));
    }

    /** debug：把世界/维度/反射关键信息拼成一行（gametest 打印，便于跨版本排查）。 */
    public String debugWorldInfo(Minecraft mc) {
        Object level = McReflect.field(mc, "level");
        if (level == null) {
            level = McReflect.call(mc, new String[]{"level", "getLevel"});
        }
        Object rawKey = level == null ? null : McReflect.call(level, new String[]{"dimension", "getDimension"});
        Object rawLocation = rawKey == null ? null : McReflect.call(rawKey, new String[]{"location", "getLocation"});
        return "level=" + (level == null ? "null" : level.getClass().getSimpleName())
                + ", dimensionKey=" + rawKey
                + ", location=" + rawLocation
                + ", normalized=" + readDimension(mc);
    }

    public void shutdown() {
        capture.shutdown();
        dbExec.shutdownNow();
        netExec.shutdownNow();
        try {
            dbExec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.close();
    }

    private static ExecutorService daemon(String name) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, name + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
