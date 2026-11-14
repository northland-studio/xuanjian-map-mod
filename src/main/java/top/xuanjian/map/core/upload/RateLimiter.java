package top.xuanjian.map.core.upload;

import top.xuanjian.map.core.util.TimeSource;

/**
 * 上传限速器（令牌桶）：默认 60 次/分钟/用户，与服务端 limits.requestsPerMinute 对齐。
 *
 * <p>429 时通过 {@link #pauseUntil(long)} 尊重服务端 Retry-After。
 */
public final class RateLimiter {

    private final int permitsPerMinute;
    private final TimeSource time;
    private double tokens;
    private long lastRefillMillis;
    private long pausedUntilMillis;

    public RateLimiter(int permitsPerMinute, TimeSource time) {
        this.permitsPerMinute = Math.max(1, permitsPerMinute);
        this.time = time;
        this.tokens = this.permitsPerMinute;
        this.lastRefillMillis = time.millis();
    }

    public int permitsPerMinute() {
        return permitsPerMinute;
    }

    private void refill() {
        long now = time.millis();
        long elapsed = Math.max(0, now - lastRefillMillis);
        if (elapsed <= 0) {
            return;
        }
        double perMs = permitsPerMinute / 60_000.0;
        tokens = Math.min(permitsPerMinute, tokens + elapsed * perMs);
        lastRefillMillis = now;
    }

    /** 尝试获取一个请求许可。 */
    public synchronized boolean tryAcquire() {
        long now = time.millis();
        if (now < pausedUntilMillis) {
            return false;
        }
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** 距离下一个许可可用大约多久（毫秒），用于调度而不是忙等。 */
    public synchronized long waitMillis() {
        long now = time.millis();
        if (now < pausedUntilMillis) {
            return pausedUntilMillis - now;
        }
        refill();
        if (tokens >= 1.0) {
            return 0;
        }
        double perMs = permitsPerMinute / 60_000.0;
        return (long) Math.ceil((1.0 - tokens) / perMs);
    }

    /** 服务端返回 429：暂停到指定时间。 */
    public synchronized void pauseUntil(long millis) {
        this.pausedUntilMillis = Math.max(this.pausedUntilMillis, millis);
    }

    public synchronized long pausedUntil() {
        return pausedUntilMillis;
    }
}
