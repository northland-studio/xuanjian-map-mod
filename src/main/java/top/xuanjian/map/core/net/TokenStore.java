package top.xuanjian.map.core.net;

import top.xuanjian.map.core.util.GisLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

/**
 * OAuth token 本地存储（config/xuanjianmap-oauth.properties）。
 *
 * <p>安全：文件权限尽量 600；内容不进日志；退出登录时可 {@link #clear()}。
 * 这些 token 永远不会被提交到仓库（.gitignore 已排除 config/）。
 */
public final class TokenStore {

    private static final String TAG = "oauth";
    private static final String FILE_NAME = "xuanjianmap-oauth.properties";

    private final Path file;
    private OAuthTokens tokens;
    private String nickname = "";

    public TokenStore(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
        load();
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            tokens = new OAuthTokens("", "", 0, "");
            nickname = "";
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            GisLog.warn(TAG, "读取 OAuth token 失败: " + e);
        }
        tokens = new OAuthTokens(
                props.getProperty("access_token", ""),
                props.getProperty("refresh_token", ""),
                parseLong(props.getProperty("expires_at", "0")),
                props.getProperty("scope", ""));
        nickname = props.getProperty("nickname", "");
    }

    public synchronized OAuthTokens tokens() {
        return tokens;
    }

    public synchronized boolean hasAccessToken() {
        return tokens.hasAccessToken();
    }

    /** 已登录用户昵称（登录时从 token 响应 / userinfo 获取，用于 UI 显示）。 */
    public synchronized String nickname() {
        return nickname;
    }

    /** 是否持有可用的登录态（access 或 refresh 任一存在）。 */
    public synchronized boolean loggedIn() {
        return tokens.hasAccessToken() || tokens.hasRefreshToken();
    }

    public synchronized void save(OAuthTokens newTokens) {
        save(newTokens, nickname);
    }

    public synchronized void save(OAuthTokens newTokens, String newNickname) {
        this.tokens = newTokens;
        this.nickname = newNickname == null ? "" : newNickname;
        Properties props = new Properties();
        props.setProperty("access_token", nullToEmpty(newTokens.accessToken()));
        props.setProperty("refresh_token", nullToEmpty(newTokens.refreshToken()));
        props.setProperty("expires_at", String.valueOf(newTokens.expiresAtMillis()));
        props.setProperty("scope", nullToEmpty(newTokens.scope()));
        props.setProperty("nickname", this.nickname);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Xuanjian Map Mod OAuth tokens (本地凭据，请勿提交)");
            }
            try {
                Files.setPosixFilePermissions(file, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows 无 POSIX 权限，跳过
            }
        } catch (IOException e) {
            GisLog.error(TAG, "保存 OAuth token 失败", e);
        }
    }

    public synchronized void clear() {
        this.tokens = new OAuthTokens("", "", 0, "");
        this.nickname = "";
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            GisLog.warn(TAG, "删除 OAuth token 文件失败: " + e);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
