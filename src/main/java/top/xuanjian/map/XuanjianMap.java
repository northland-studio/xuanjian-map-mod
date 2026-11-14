package top.xuanjian.map;

import net.fabricmc.api.ModInitializer;
import top.xuanjian.map.core.util.GisLog;

/**
 * 玄剑 Map Mod 主入口（客户端专用模组）。
 *
 * <p>这里只做与 Minecraft 无关的初始化（日志门面），真正的客户端 GIS 服务
 * （配置、SQLite、采集、上传、HUD、叠加层）在 {@link top.xuanjian.map.client.XuanjianMapClient} 里启动，
 * 保证核心逻辑可被纯 Java 单元测试覆盖。
 */
public class XuanjianMap implements ModInitializer {

    public static final String MOD_ID = "xuanjianmap";
    public static final String VERSION = "0.1.0";
    public static final String TAG = MOD_ID;

    @Override
    public void onInitialize() {
        GisLog.info(TAG, "玄剑 Map Mod 加载中 v" + VERSION + "（GIS 客户端）");
    }
}
