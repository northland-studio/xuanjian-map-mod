package top.xuanjian.map.core.upload;

/**
 * 上传重试策略：指数退避 1s/2s/4s… 上限 60s（契约要求）。
 *
 * <p>429 时以服务端 Retry-After 为准；重试耗尽后任务置 FAILED，
 * <b>本地 chunk 数据保留</b>，用户可在数据状态页手动重试或下次启动继续。
 */
public record RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(8, 1_000L, 60_000L);

    public RetryPolicy {
        if (maxAttempts <= 0) {
            maxAttempts = DEFAULT.maxAttempts;
        }
        if (baseDelayMs <= 0) {
            baseDelayMs = DEFAULT.baseDelayMs;
        }
        if (maxDelayMs < baseDelayMs) {
            maxDelayMs = Math.max(baseDelayMs, DEFAULT.maxDelayMs);
        }
    }

    /** attempts 为“已经失败过的次数”（1 = 首次失败后等待）。 */
    public long delayMsForAttempt(int attempts) {
        int n = Math.max(1, Math.min(attempts, 30));
        long delay = baseDelayMs << Math.min(n - 1, 20);
        return Math.min(delay, maxDelayMs);
    }

    /** 429 的 retryAfter 优先，但要夹在 [baseDelay, maxDelay] 内。 */
    public long delayForRateLimited(int retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            return baseDelayMs;
        }
        return Math.max(baseDelayMs, Math.min(maxDelayMs, retryAfterSeconds * 1000L));
    }

    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
