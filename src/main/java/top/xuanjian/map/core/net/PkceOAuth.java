package top.xuanjian.map.core.net;

import com.google.gson.JsonObject;
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
                throw oauthError(response);
            }
            var json = Json.parseObject(response.body());
            String access = Json.optString(json, "access_token", "");
            String refresh = Json.optString(json, "refresh_token", "");
            long expiresIn = Json.optLong(json, "expires_in", 7200);
            String scope = Json.optString(json, "scope", DEFAULT_SCOPE);
            if (access.isBlank()) {
                throw new ApiException("invalid_response", "OAuth 响应缺少 access_token", response.statusCode());
            }
            String nickname = extractNickname(json);
            OAuthTokens tokens = new OAuthTokens(access, refresh,
                    System.currentTimeMillis() + expiresIn * 1000L, scope);
            tokenStore.save(tokens, nickname);
            GisLog.info(TAG, "OAuth 登录成功（scope=" + scope
                    + (nickname == null || nickname.isBlank() ? "" : ", 用户=" + nickname) + "）");
            return tokens;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException("network_error",
                    "网络错误：无法连接 " + config.apiBase(), 0);
        }
    }

    /**
     * 把 OAuth 2.0 错误响应 `{error, error_description}` 映射成可直接给玩家看的中文原因。
     * code 保留服务端原始 error（便于排查/日志），message 为中文 + 服务端描述。
     */
    private static ApiException oauthError(HttpResponse<String> response) {
        JsonObject json = Json.parseObjectQuietly(response.body());
        String code = Json.optString(json, "error", "oauth_error");
        String description = Json.optString(json, "error_description",
                Json.optString(json, "message", ""));
        String message = switch (code) {
            case "invalid_grant" -> "授权码无效或已过期（code 5 分钟一次性，请重新点「登录 OAuth」）";
            case "invalid_request" -> "请求不合法（检查 redirect_uri / code_verifier 是否与授权时一致）";
            case "invalid_client" -> "OAuth 客户端未注册或 client_id 配置错误";
            case "unauthorized_client" -> "该客户端不允许使用此授权方式";
            case "unsupported_grant_type" -> "不支持的授权类型";
            case "invalid_scope" -> "请求的 scope 不被允许";
            case "server_error", "temporarily_unavailable" -> "OAuth 服务端暂时不可用，请稍后重试";
            default -> "OAuth 登录失败（" + code + "）";
        };
        if (!description.isBlank()) {
            message += "：" + description;
        }
        return new ApiException(code, message, response.statusCode());
    }

    /** 从 token 响应里尽力提取昵称（不同实现可能放在 user/nickname/username 等字段）。 */
    private static String extractNickname(JsonObject json) {
        String direct = Json.optString(json, "nickname",
                Json.optString(json, "username", Json.optString(json, "name", "")));
        if (!direct.isBlank()) {
            return direct;
        }
        JsonObject user = Json.optObject(json, "user");
        if (user == null) {
            user = Json.optObject(json, "data");
        }
        if (user != null) {
            return Json.optString(user, "nickname",
                    Json.optString(user, "username", Json.optString(user, "name", "")));
        }
        return "";
    }

    /**
     * 登录后用 access_token 取用户信息（`GET /api/oauth/userinfo`），用于 UI 显示「已登录：昵称」。
     * 失败返回空串，不影响登录结果。
     */
    public String fetchNickname() {
        OAuthTokens tokens = tokenStore.tokens();
        if (!tokens.hasAccessToken()) {
            return "";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/oauth/userinfo"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                GisLog.debug(TAG, "userinfo 不可用: HTTP " + response.statusCode());
                return "";
            }
            JsonObject json = Json.parseObject(response.body());
            String nickname = extractNickname(json);
            if (nickname == null || nickname.isBlank()) {
                JsonObject user = Json.optObject(json, "user");
                if (user != null) {
                    nickname = Json.optString(user, "nickname", Json.optString(user, "username", ""));
                }
            }
            return nickname == null ? "" : nickname;
        } catch (Exception e) {
            GisLog.debug(TAG, "获取 nickname 失败: " + e);
            return "";
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
