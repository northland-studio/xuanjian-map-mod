package top.xuanjian.map.core.util;

/**
 * 时间源抽象：核心逻辑（重试退避、限速、缓存 TTL）不直接调 {@link System#currentTimeMillis()}，
 * 便于单元测试注入固定/可推进的时间。
 */
public interface TimeSource {

    /** 当前毫秒时间戳。 */
    long millis();

    /** 单调时钟（用于节流判断，不受系统时间回拨影响）。 */
    default long nanoTime() {
        return System.nanoTime();
    }

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long millis() {
            return System.currentTimeMillis();
        }
    };

    /** 测试用：时间固定，可手动推进。 */
    static Manual manual(long startMillis) {
        return new Manual(startMillis);
    }

    final class Manual implements TimeSource {
        private long now;

        private Manual(long start) {
            this.now = start;
        }

        @Override
        public long millis() {
            return now;
        }

        public void advance(long deltaMillis) {
            now += deltaMillis;
        }

        public void set(long millis) {
            now = millis;
        }
    }
}
