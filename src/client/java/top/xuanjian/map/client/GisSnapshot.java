package top.xuanjian.map.client;

import top.xuanjian.map.core.geo.LocationInfo;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.storage.GisStore;
import top.xuanjian.map.core.world.BootstrapInfo;
import top.xuanjian.map.core.world.WorldSession;

import java.util.List;

/**
 * UI 读取的不可变快照（渲染线程只读，后台线程更新），避免界面直接触碰 SQLite/网络。
 */
public record GisSnapshot(WorldSession session, BootstrapInfo bootstrap,
                          List<RegisteredWorld> worlds, GisStore.CacheStats stats,
                          LocationInfo location, String tokenState, String lastError,
                          long lastSyncAt, boolean syncing, String xaeroMode, boolean hudExpanded,
                          String apiBase, String serverKey, int capturedThisSession,
                          String contractHint) {

    public GisSnapshot {
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
    }

    public static final GisSnapshot EMPTY = new GisSnapshot(WorldSession.NONE, null, List.of(),
            GisStore.CacheStats.EMPTY, LocationInfo.EMPTY, "未登录", "", 0, false,
            "Xaero：未检测到", true, "", "", 0, "");

    public String worldLine() {
        if (session == null || !session.registered()) {
            String reason = session == null || session.lastError() == null || session.lastError().isBlank()
                    ? "未绑定" : session.lastError();
            return "世界：未绑定（" + reason + "）";
        }
        return "世界：" + session.worldKey() + " #" + session.worldId()
                + " · " + session.mcVersion() + " · " + session.statusLabel();
    }

    public String uploadLine() {
        if (session == null || !session.canUpload()) {
            return "上传：未启用（世界未注册 / 服务器未登记）";
        }
        GisStore.JobStats jobs = stats.jobs();
        return "上传队列：待传 " + jobs.pending() + " · 重试 " + jobs.retry()
                + " · 完成 " + jobs.done() + " · 失败 " + jobs.failed();
    }

    public String cacheLine() {
        GisStore.ChunkStats chunks = stats.chunks();
        return "缓存：区块 " + chunks.total() + "（已传 " + chunks.uploaded()
                + " / 待传 " + (chunks.captured() + chunks.queued()) + " / 失败 "
                + chunks.failed() + "） · 对象 " + stats.objectCount()
                + " · " + (stats.dbBytes() / 1024) + " KiB";
    }

    public String syncLine() {
        if (lastSyncAt <= 0) {
            return "同步：尚未同步（" + (syncing ? "进行中" : "等待中") + "）";
        }
        long ago = Math.max(0, (System.currentTimeMillis() - lastSyncAt) / 1000);
        return "同步：" + ago + " 秒前" + (syncing ? "（正在同步）" : "");
    }

    public String statusLine() {
        if (lastError != null && !lastError.isBlank()) {
            return "错误：" + lastError;
        }
        return "状态：正常";
    }
}
