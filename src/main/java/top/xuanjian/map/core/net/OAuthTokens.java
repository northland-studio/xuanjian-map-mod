package top.xuanjian.map.core.net;

/**
 * OAuth2 tokens（access 2h / refresh 30d 一次性轮换）。
 *
 * <p>只落在本地 config 目录，绝不入库、不入 git；日志里也不打印 token。
 */
public record OAuthTokens(String accessToken, String refreshToken, long expiresAtMillis, String scope) {

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /** 提前 60 秒视为过期，避免临界点请求失败。 */
    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0 && nowMillis >= expiresAtMillis - 60_000L;
    }
}
