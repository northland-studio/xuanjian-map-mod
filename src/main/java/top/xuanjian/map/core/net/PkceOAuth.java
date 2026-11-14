package top.xuanjian.map.core.net;

import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.util.GisLog;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一账号 OAuth2 登录（PKCE 公共客户端）。
 *
 * <p>契约：复用 xuanjian.top 现有 OAuth2，不新建账号体系；scope 固定
 * {@code read:user read:member}；access_token 2h，refresh_token 30d 且一次性轮换。
 * GIS 写权限由服务端 {@code gis.submit} 判定，客户端不做管理员名单。
 *
 * <p>不引入浏览器自动化：授权页由用户在浏览器打开，回调拿到 code 后粘贴回游戏内设置页，
 * 由本类换取 token 并保存。CI 环境不执行登录（上传走 mock 的固定 token）。
 */
public final class PkceOAuth {

    private static final String TAG = "oauth";
    public static final String DEFAULT_SCOPE = "read:user read:member";
    /** RFC 7636：verifier 长度 43..128，字符集 [A-Za-z0-9-._~]。 */
    private static final int VERIFIER_LENGTH = 64;
    private static final char[] VERIFIER_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toCharArray();

    private final GisConfig config;
    private final TokenStore tokenStore;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public PkceOAuth(GisConfig config, TokenStore tokenStore) {
        this.config = config;
        this.tokenStore = tokenStore;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public TokenStore tokenStore() {
        return tokenStore;
    }

    /** 生成 code_verifier。 */
    public String newVerifier() {
        StringBuilder sb = new StringBuilder(VERIFIER_LENGTH);
        for (int i = 0; i < VERIFIER_LENGTH; i++) {
            sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)]);
        }
        return sb.toString();
    }

    /** S256 challenge：base64url(sha256(verifier))，无 padding。 */
    public static String challenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 PKCE challenge", e);
        }
    }

    /** 浏览器授权 URL。 */
    public String authorizeUrl(String verifier, String state) {
        return base() + "/api/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(config.oauthClientId())
                + "&redirect_uri=" + enc(config.oauthRedirectUri())
                + "&scope=" + enc(DEFAULT_SCOPE)
                + "&code_challenge=" + enc(challenge(verifier))
                + "&code_challenge_method=S256"
                + "&state=" + enc(state == null ? "" : state);
    }

    /** 用授权码换 token。 */
    public OAuthTokens exchangeCode(String code, String verifier) throws ApiException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", config.oauthRedirectUri());
        form.put("client_id", config.oauthClientId());
        form.put("code_verifier", verifier);
        return postToken(form);
    }

    /** 刷新 token（refresh_token 一次性轮换：成功后立刻写入新的 refresh）。 */
    public OAuthTokens refresh() throws ApiException {
        OAuthTokens current = tokenStore.tokens();
        if (!current.hasRefreshToken()) {
            throw new ApiException("unauthorized", "未登录（无 refresh_token），请先在设置页登录", 401);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", current.refreshToken());
        form.put("client_id", config.oauthClientId());
        return postToken(form);
    }

    /** 有效 token：过期则自动刷新；无 token 返回 null。 */
    public OAuthTokens validTokens() {
        OAuthTokens current = tokenStore.tokens();
        if (!current.hasAccessToken() && !current.hasRefreshToken()) {
            return null;
        }
        if (current.hasAccessToken() && !current.isExpired(System.currentTimeMillis())) {
            return current;
        }
        try {
            return refresh();
        } catch (ApiException e) {
            GisLog.warn(TAG, "OAuth token 刷新失败: " + e.describe());
            return null;
        }
    }

    public void logout() {
        tokenStore.clear();
        GisLog.info(TAG, "已退出 OAuth 登录");
    }

    private OAuthTokens postToken(Map<String, String> form) throws ApiException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/oauth/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException("unauthorized",
                        "OAuth token 交换失败: HTTP " + response.statusCode(), response.statusCode());
            }
            var json = Json.parseObject(response.body());
            String access = Json.optString(json, "access_token", "");
            String refresh = Json.optString(json, "refresh_token", "");
            long expiresIn = Json.optLong(json, "expires_in", 7200);
            String scope = Json.optString(json, "scope", DEFAULT_SCOPE);
            if (access.isBlank()) {
                throw new ApiException("unauthorized", "OAuth 响应缺少 access_token", response.statusCode());
            }
            OAuthTokens tokens = new OAuthTokens(access, refresh,
                    System.currentTimeMillis() + expiresIn * 1000L, scope);
            tokenStore.save(tokens);
            GisLog.info(TAG, "OAuth 登录成功（scope=" + scope + "）");
            return tokens;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException("network_error", "OAuth 请求失败: " + e.getMessage(), 0);
        }
    }

    private String base() {
        String b = config.apiBase();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
