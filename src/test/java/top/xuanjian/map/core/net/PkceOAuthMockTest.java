package top.xuanjian.map.core.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.xuanjian.map.core.config.GisConfig;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth 真实 HTTP 往返测试（mock 服务端）：验证 code 兑换、昵称解析、错误码中文化。
 * 服务端 token 错误响应为 {@code {error, error_description}}（已与生产核对）。
 */
class PkceOAuthMockTest {

    private static HttpServer server;
    private static String baseUrl;

    /** token 响应模式：0=成功含昵称，1=成功不含昵称，2=invalid_grant，3=invalid_request。 */
    private static volatile int tokenMode;
    private static volatile String lastTokenRequestBody = "";

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/oauth/token", ex -> {
            lastTokenRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (tokenMode) {
                case 2 -> json(ex, 400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"授权码无效\"}");
                case 3 -> json(ex, 400,
                        "{\"error\":\"invalid_request\",\"error_description\":\"redirect_uri 不匹配\"}");
                case 1 -> json(ex, 200, """
                        {"access_token":"at-no-nick","refresh_token":"rt-no-nick",
                         "expires_in":7200,"scope":"read:user read:member"}
                        """);
                default -> json(ex, 200, """
                        {"access_token":"at-1","refresh_token":"rt-1","expires_in":7200,
                         "scope":"read:user read:member","user":{"nickname":"玄剑玩家"}}
                        """);
            }
        });
        server.createContext("/api/oauth/userinfo", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer at-")) {
                json(ex, 401, "{\"error\":\"missing_token\"}");
                return;
            }
            json(ex, 200, "{\"nickname\":\"userinfo昵称\",\"username\":\"tester\"}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void json(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static PkceOAuth oauth(Path dir) {
        GisConfig config = new GisConfig(dir);
        config.apiBase(baseUrl);
        return new PkceOAuth(config, new TokenStore(dir));
    }

    @Test
    void exchangeCode_successSavesTokensAndNickname(@TempDir Path dir) throws Exception {
        tokenMode = 0;
        PkceOAuth oauth = oauth(dir);
        OAuthTokens tokens = oauth.exchangeCode("good-code", "verifier-1");

        assertEquals("at-1", tokens.accessToken());
        assertEquals("rt-1", tokens.refreshToken());
        assertTrue(lastTokenRequestBody.contains("grant_type=authorization_code"));
        assertTrue(lastTokenRequestBody.contains("code_verifier=verifier-1"));
        // token 响应里带 user.nickname → 直接落库
        assertEquals("玄剑玩家", oauth.tokenStore().nickname());
        assertTrue(oauth.tokenStore().loggedIn());
    }

    @Test
    void exchangeCode_fallsBackToUserinfoForNickname(@TempDir Path dir) throws Exception {
        tokenMode = 1;
        PkceOAuth oauth = oauth(dir);
        oauth.exchangeCode("good-code", "verifier-1");
        assertEquals("", oauth.tokenStore().nickname(), "token 响应无昵称时先留空");
        assertEquals("userinfo昵称", oauth.fetchNickname(), "userinfo 作为昵称兜底");
        // fetchNickname 只返回值，由客户端保存；这里确认 token 仍持久化
        TokenStore reloaded = new TokenStore(dir);
        assertEquals("at-no-nick", reloaded.tokens().accessToken());
        assertTrue(reloaded.loggedIn());
    }

    @Test
    void invalidGrant_mapsToChineseReason(@TempDir Path dir) {
        tokenMode = 2;
        PkceOAuth oauth = oauth(dir);
        ApiException error = assertThrows(ApiException.class,
                () -> oauth.exchangeCode("expired-code", "verifier-1"));
        assertEquals("invalid_grant", error.code());
        assertEquals(400, error.httpStatus());
        assertTrue(error.getMessage().contains("授权码无效或已过期"), error.getMessage());
        assertTrue(error.getMessage().contains("授权码无效"), "应显示服务端 error_description");
        assertFalse(oauth.tokenStore().loggedIn());
    }

    @Test
    void invalidRequest_mapsToChineseReason(@TempDir Path dir) {
        tokenMode = 3;
        PkceOAuth oauth = oauth(dir);
        ApiException error = assertThrows(ApiException.class,
                () -> oauth.exchangeCode("code", "verifier"));
        assertEquals("invalid_request", error.code());
        assertTrue(error.getMessage().contains("请求不合法"));
        assertTrue(error.getMessage().contains("redirect_uri 不匹配"), error.getMessage());
    }

    @Test
    void networkError_isReportedClearly(@TempDir Path dir) {
        GisConfig config = new GisConfig(dir);
        // 不可达端口 → 连接失败
        config.apiBase("http://127.0.0.1:9");
        PkceOAuth oauth = new PkceOAuth(config, new TokenStore(dir));
        ApiException error = assertThrows(ApiException.class,
                () -> oauth.exchangeCode("code", "verifier"));
        assertEquals("network_error", error.code());
        assertTrue(error.getMessage().contains("网络错误"), error.getMessage());
    }

    private static void assertFalse(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }
}
