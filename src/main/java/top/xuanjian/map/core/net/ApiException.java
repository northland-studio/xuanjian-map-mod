package top.xuanjian.map.core.net;

/**
 * GIS API 统一错误（契约 {@code {ok:false, error, message}}）。
 *
 * <p>错误码覆盖：unauthorized / forbidden(含 required) / invalid_argument / invalid_payload
 * / payload_too_large / bbox_required / bbox_too_large / server_not_registered
 * / world_not_registered / world_archived / dimension_mismatch / version_conflict
 * / rate_limited(retryAfter) / data_unavailable / server_error。
 */
public class ApiException extends Exception {

    private final String code;
    private final int httpStatus;
    private final int retryAfterSeconds;
    private final int chunkIndex;

    public ApiException(String code, String message, int httpStatus) {
        this(code, message, httpStatus, -1, -1);
    }

    public ApiException(String code, String message, int httpStatus, int retryAfterSeconds, int chunkIndex) {
        super(message == null || message.isBlank() ? code : message);
        this.code = code == null || code.isBlank() ? "unknown" : code;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
        this.chunkIndex = chunkIndex;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 429 的 retryAfter 秒（无则 -1）。 */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** invalid_payload 里的坏 chunk 下标（无则 -1）。 */
    public int chunkIndex() {
        return chunkIndex;
    }

    public boolean isRateLimited() {
        return httpStatus == 429 || "rate_limited".equals(code);
    }

    public boolean isAuthError() {
        return httpStatus == 401 || "unauthorized".equals(code);
    }

    /** 永久性错误（重试也没意义，直接判失败但保留本地数据）。 */
    public boolean isPermanent() {
        return switch (code) {
            case "invalid_argument", "invalid_payload", "payload_too_large",
                 "bbox_required", "bbox_too_large", "server_not_registered",
                 "world_not_registered", "world_archived", "dimension_mismatch",
                 "version_conflict", "forbidden" -> true;
            default -> false;
        };
    }

    public String describe() {
        return code + "(" + httpStatus + "): " + getMessage();
    }
}
