package top.xuanjian.map.core.util;

/**
 * 极简日志门面。
 *
 * <p>核心层（main source set）不直接依赖 SLF4J：单元测试只跑纯 JDK + Gson，
 * 不需要把 Minecraft/日志实现拖进测试运行时。模组入口在客户端初始化时把 sink 换成 SLF4J。
 */
public final class GisLog {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    public interface Sink {
        void log(Level level, String tag, String message, Throwable error);
    }

    private static volatile Sink sink = (level, tag, message, error) -> {
        // 默认 sink：输出到 stderr，避免核心层出现“静默失败”
        if (level == Level.DEBUG) {
            return;
        }
        System.err.println("[" + tag + "/" + level + "] " + message
                + (error == null ? "" : " (" + error + ")"));
    };

    private GisLog() {
    }

    public static void setSink(Sink newSink) {
        sink = newSink == null ? sink : newSink;
    }

    public static Sink sink() {
        return sink;
    }

    public static void debug(String tag, String message) {
        sink.log(Level.DEBUG, tag, message, null);
    }

    public static void info(String tag, String message) {
        sink.log(Level.INFO, tag, message, null);
    }

    public static void warn(String tag, String message) {
        sink.log(Level.WARN, tag, message, null);
    }

    public static void warn(String tag, String message, Throwable error) {
        sink.log(Level.WARN, tag, message, error);
    }

    public static void error(String tag, String message, Throwable error) {
        sink.log(Level.ERROR, tag, message, error);
    }
}
