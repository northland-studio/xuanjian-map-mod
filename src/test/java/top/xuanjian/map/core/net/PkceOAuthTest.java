package top.xuanjian.map.core.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkceOAuthTest {

    @Test
    void challengeMatchesRfc7636Vector() {
        // RFC 7636 Appendix B 官方测试向量
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PkceOAuth.challenge(verifier));
    }

    @Test
    void verifierCharsetAndLength(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        GisConfigStub cfg = new GisConfigStub(dir);
        PkceOAuth oauth = new PkceOAuth(cfg.config, new TokenStore(dir));
        String verifier = oauth.newVerifier();
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128);
        assertTrue(verifier.matches("[A-Za-z0-9\\-._~]+"), "verifier 字符集必须符合 RFC 7636");
        assertEquals(43, PkceOAuth.challenge(verifier).length(), "S256 challenge 为 43 字符 base64url");
    }

    @Test
    void authorizeUrlContainsPkceParams(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        GisConfigStub cfg = new GisConfigStub(dir);
        PkceOAuth oauth = new PkceOAuth(cfg.config, new TokenStore(dir));
        String verifier = oauth.newVerifier();
        String url = oauth.authorizeUrl(verifier, "state-1");
        assertTrue(url.contains("/api/oauth/authorize"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("code_challenge=" + PkceOAuth.challenge(verifier)));
        assertTrue(url.contains("scope=read%3Auser+read%3Amember"));
        assertTrue(url.contains("state=state-1"));
    }

    @Test
    void expiredTokensDetection() {
        OAuthTokens tokens = new OAuthTokens("a", "r", 100_000L, "read:user");
        assertTrue(tokens.isExpired(50_000L));
        assertFalse(tokens.isExpired(10_000L));
        assertFalse(new OAuthTokens("", "", 0, "").hasAccessToken());
    }

    private static final class GisConfigStub {
        final top.xuanjian.map.core.config.GisConfig config;

        GisConfigStub(java.nio.file.Path dir) {
            this.config = new top.xuanjian.map.core.config.GisConfig(dir);
        }
    }
}
