package top.xuanjian.map.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuanjian.map.XuanjianMap;

/**
 * 客户端入口。真正的 GIS 客户端服务在 {@link top.xuanjian.map.client.GisClient} 中装配，
 * 本类只负责初始化与把核心层日志接到 SLF4J。
 */
public class XuanjianMapClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(XuanjianMap.MOD_ID);

    @Override
    public void onInitializeClient() {
        top.xuanjian.map.core.util.GisLog.setSink((level, tag, message, error) -> {
            switch (level) {
                case DEBUG -> LOGGER.debug("[{}] {}", tag, message);
                case INFO -> LOGGER.info("[{}] {}", tag, message);
                case WARN -> {
                    if (error != null) {
                        LOGGER.warn("[{}] {}", tag, message, error);
                    } else {
                        LOGGER.warn("[{}] {}", tag, message);
                    }
                }
                case ERROR -> LOGGER.error("[{}] {}", tag, message, error);
            }
        });
        LOGGER.info("[{}] 客户端入口初始化", XuanjianMap.MOD_ID);
    }
}
