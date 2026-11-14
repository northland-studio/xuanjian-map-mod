package top.xuanjian.map.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import top.xuanjian.map.client.capture.ChunkCaptureService;
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
    private LocationInfo lastLocation = LocationInfo.EMPTY;
    private LocationInfo cachedLocation = LocationInfo.EMPTY;
    private long locationAt;
    private String loginVerifier = "";

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
        long interval = retryAfterFailure ? 60_000L : 5_000L;
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
        return new WorldSession(serverKey == null ? "" : serverKey, 0, "", dimension, "", false,
                "LOCAL", System.currentTimeMillis(), error);
    }

    private void bindWorld(String dimension) {
        String serverKey = config.serverKey();
        if (serverKey == null || serverKey.isBlank()) {
            session = localSession("", dimension, "未配置 gis.serverKey（本地模式）");
            refreshSnapshotSoon();
            return;
        }
        try {
            BootstrapInfo info = api.bootstrap(serverKey);
            this.bootstrap = info;
            store.putWorlds(serverKey, info.worlds());
            RegisteredWorld world = info.currentWorld();
            if (world == null) {
                session = localSession(serverKey, dimension, "服务器未注册 ACTIVE 世界（本地模式）");
                refreshSnapshotSoon();
                return;
            }
            GisApi.ResolveResult resolved = api.resolveWorld(serverKey, world.id(),
                    world.worldKey(), dimension);
            session = new WorldSession(serverKey, resolved.worldId(), resolved.worldKey(),
                    Dimensions.normalize(resolved.dimension()), world.mcVersion(),
                    resolved.ok(), resolved.status(), System.currentTimeMillis(), "");
            GisLog.info(TAG, "世界绑定成功: " + session.worldKey() + " #" + session.worldId()
                    + " dim=" + session.dimension());
            syncNow();
        } catch (ApiException e) {
            session = localSession(serverKey, dimension, e.describe() + "（本地模式）");
            GisLog.warn(TAG, "世界绑定失败，进入本地模式: " + e.describe());
        } finally {
            refreshSnapshotSoon();
        }
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

    private void maybeUpload() {
        if (uploading || !config.uploadEnabled() || !session.canUpload()) {
            return;
        }
        uploading = true;
        netExec.submit(() -> {
            try {
                Uploader.TickResult result = uploader.tick(session);
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
            String tokenState = tokenStore.hasAccessToken()
                    ? "OAuth：已登录（写权限由服务端 gis.submit 判定）"
                    : "OAuth：未登录（不能上传，本地功能可用）";
            BootstrapInfo info = bootstrap;
            String contractHint = info == null ? "契约 v1（未获取 limits）"
                    : "契约 v" + info.contract() + " · plan≤" + info.limits().maxPlanChunks()
                    + " · 单批≤" + info.limits().maxBatchChunks()
                    + " · 压缩≤" + (info.limits().maxCompressedBytes() / 1024 / 1024) + "MiB";
            snapshot = new GisSnapshot(current, info, store.worlds(), stats, cachedLocation, tokenState,
                    lastSyncError, lastSyncAt, syncing, xaeroMode, config.hudExpanded(),
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


    /* ==================== OAuth 登录 ==================== */

    public String beginLogin() {
        loginVerifier = oauth.newVerifier();
        return oauth.authorizeUrl(loginVerifier, "xjm");
    }

    public OAuthTokens finishLogin(String code) {
        try {
            OAuthTokens tokens = oauth.exchangeCode(code.trim(), loginVerifier);
            refreshSnapshotSoon();
            return tokens;
        } catch (ApiException e) {
            GisLog.warn(TAG, "登录失败: " + e.describe());
            return null;
        }
    }

    public void logout() {
        oauth.logout();
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
        Object key = McReflect.call(level, new String[]{"dimension"});
        if (key == null) {
            return Dimensions.OVERWORLD;
        }
        Object location = McReflect.call(key, new String[]{"location", "registry"});
        return location == null ? Dimensions.OVERWORLD : Dimensions.normalize(String.valueOf(location));
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
