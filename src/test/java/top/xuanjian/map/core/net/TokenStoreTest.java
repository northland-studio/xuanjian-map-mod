package top.xuanjian.map.core.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStoreTest {

    @Test
    void nicknamePersistsAcrossReload(@TempDir Path dir) {
        TokenStore store = new TokenStore(dir);
        assertFalse(store.loggedIn());
        assertEquals("", store.nickname());

        store.save(new OAuthTokens("access-1", "refresh-1",
                System.currentTimeMillis() + 3600_000L, "read:user read:member"), "玄剑玩家");
        assertTrue(store.loggedIn());
        assertEquals("玄剑玩家", store.nickname());

        TokenStore reloaded = new TokenStore(dir);
        assertEquals("access-1", reloaded.tokens().accessToken());
        assertEquals("玄剑玩家", reloaded.nickname());
        assertTrue(reloaded.loggedIn());
    }

    @Test
    void clearRemovesLoginStateAndNickname(@TempDir Path dir) {
        TokenStore store = new TokenStore(dir);
        store.save(new OAuthTokens("a", "r", System.currentTimeMillis() + 1000, "scope"), "昵称");
        store.clear();
        assertFalse(store.loggedIn());
        assertEquals("", store.nickname());
        assertFalse(new TokenStore(dir).loggedIn());
    }

    @Test
    void saveWithoutNicknameKeepsPrevious(@TempDir Path dir) {
        TokenStore store = new TokenStore(dir);
        store.save(new OAuthTokens("a", "r", 0, ""), "旧昵称");
        store.save(new OAuthTokens("a2", "r2", 0, ""));
        assertEquals("旧昵称", store.nickname());
    }
}
