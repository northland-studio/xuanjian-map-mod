package top.xuanjian.map.core.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OAuthCodeParserTest {

    @Test
    void plainCode() {
        assertEquals("abc123XYZ-_", OAuthCodeParser.extractCode("abc123XYZ-_"));
        assertEquals("abc123", OAuthCodeParser.extractCode("  abc123  "));
        assertEquals("abc123", OAuthCodeParser.extractCode("\"abc123\""));
        assertEquals("abc123", OAuthCodeParser.extractCode("'abc123'"));
    }

    @Test
    void codePrefix() {
        assertEquals("abc123", OAuthCodeParser.extractCode("code=abc123"));
        assertEquals("abc123", OAuthCodeParser.extractCode("code=abc123&state=xyz"));
        assertEquals("abc123", OAuthCodeParser.extractCode("授权码: code=abc123"));
        assertEquals("abc123", OAuthCodeParser.extractCode("code: abc123"));
    }

    @Test
    void fullCallbackUrl() {
        assertEquals("abc123", OAuthCodeParser.extractCode(
                "https://xuanjian.top/api/oauth/mod-callback?code=abc123&state=st"));
        assertEquals("abc123", OAuthCodeParser.extractCode(
                "https://xuanjian.top/api/oauth/mod-callback?state=st&code=abc123"));
        assertEquals("abc123", OAuthCodeParser.extractCode(
                "https://xuanjian.top/api/oauth/mod-callback?code=abc123#frag"));
    }

    @Test
    void urlEncoded() {
        assertEquals("a b", OAuthCodeParser.extractCode("code=a%20b"));
        assertEquals("a+b", OAuthCodeParser.extractCode("code=a%2Bb"));
    }

    @Test
    void rejectsGarbage() {
        assertNull(OAuthCodeParser.extractCode(null));
        assertNull(OAuthCodeParser.extractCode(""));
        assertNull(OAuthCodeParser.extractCode("   "));
        // 中文说明不是 code（避免把用户复制的提示文字当授权码）
        assertNull(OAuthCodeParser.extractCode("授权码无效，请重新登录"));
    }
}
