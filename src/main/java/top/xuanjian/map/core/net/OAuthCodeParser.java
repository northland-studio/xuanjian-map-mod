package top.xuanjian.map.core.net;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 从用户粘贴内容中容错提取 OAuth 授权码。
 *
 * <p>真实用户可能粘贴：纯 code、`code=xxx`、整段回调 URL
 * （`https://xuanjian.top/api/oauth/mod-callback?code=xxx&state=yyy`）、带引号/空白的文本。
 * 这里统一清洗，避免“拿到了 code 但格式不对 → invalid_grant”的体验问题。
 */
public final class OAuthCodeParser {

    private OAuthCodeParser() {
    }

    /**
     * @return 提取到的 code；无法识别返回 null
     */
    public static String extractCode(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 去掉包裹引号
        if (text.length() >= 2
                && ((text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"')
                || (text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\''))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        // 整段 URL 或含 code= 的查询串：按参数抽取
        int codeIdx = indexOfParam(text, "code");
        if (codeIdx >= 0) {
            String value = text.substring(codeIdx);
            int amp = value.indexOf('&');
            if (amp >= 0) {
                value = value.substring(0, amp);
            }
            int hash = value.indexOf('#');
            if (hash >= 0) {
                value = value.substring(0, hash);
            }
            return normalizeUrlEncoded(value.trim());
        }
        // 形如 "code: xxx" / "授权码: xxx"
        int colon = text.lastIndexOf(':');
        if (colon >= 0 && colon < text.length() - 1) {
            String tail = text.substring(colon + 1).trim();
            if (looksLikeCode(tail)) {
                return tail;
            }
        }
        return looksLikeCode(text) ? normalizeUrlEncoded(text) : null;
    }

    /** code 参数起始位置（返回值的开始索引；找不到返回 -1）。 */
    private static int indexOfParam(String text, String name) {
        String needle = name + "=";
        int idx = text.indexOf(needle);
        while (idx >= 0) {
            boolean boundary = idx == 0
                    || text.charAt(idx - 1) == '?'
                    || text.charAt(idx - 1) == '&'
                    || text.charAt(idx - 1) == ';'
                    || Character.isWhitespace(text.charAt(idx - 1));
            if (boundary) {
                return idx + needle.length();
            }
            idx = text.indexOf(needle, idx + 1);
        }
        return -1;
    }

    private static String normalizeUrlEncoded(String value) {
        String result = value;
        try {
            result = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 非法 % 转义就按原文使用
        }
        result = result.trim();
        return result.isEmpty() ? null : result;
    }

    /** 粗判：授权码通常是无空白、无中文、长度适中的 token 串。 */
    private static boolean looksLikeCode(String text) {
        if (text.isEmpty() || text.length() > 512) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isWhitespace(c)) {
                return false;
            }
            if (c < 0x21 || c > 0x7E) {
                return false; // 非可打印 ASCII（中文说明等）不算 code
            }
        }
        return true;
    }
}
