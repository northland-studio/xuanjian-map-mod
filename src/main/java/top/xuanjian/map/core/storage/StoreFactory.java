package top.xuanjian.map.core.storage;

import top.xuanjian.map.core.util.GisLog;

import java.nio.file.Path;

/**
 * 存储工厂：优先 SQLite；任何 native/驱动失败都降级为内存存储并明确记录，绝不让客户端崩溃。
 *
 * <p>降级时 UI「数据状态」页会显示 {@code backend=memory}，提示本地缓存不落盘，
 * 用户可据此排查（通常是 jar-in-jar native 解压被系统拦截）。
 */
public final class StoreFactory {

    private static final String TAG = "storage";

    private StoreFactory() {
    }

    public static GisStore open(Path cacheDir) {
        Path db = cacheDir.resolve("gis-cache.db");
        try {
            SqliteGisStore sqlite = new SqliteGisStore(db);
            sqlite.init();
            return sqlite;
        } catch (Throwable t) {
            GisLog.error(TAG, "SQLite 不可用，降级为内存缓存（本次会话数据不落盘）: " + t, t);
            MemoryGisStore memory = new MemoryGisStore();
            memory.init();
            return memory;
        }
    }
}
