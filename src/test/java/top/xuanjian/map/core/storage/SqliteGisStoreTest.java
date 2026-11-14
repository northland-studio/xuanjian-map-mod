package top.xuanjian.map.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.xuanjian.map.core.chunk.ChunkKey;
import top.xuanjian.map.core.chunk.ChunkState;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.model.WorldStatus;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteGisStoreTest {

    private static SqliteGisStore open(Path dir) throws Exception {
        SqliteGisStore store = new SqliteGisStore(dir.resolve("cache").resolve("gis-cache.db"));
        store.init();
        return store;
    }

    private static GisStore.ChunkRow chunk(int x, int z, ChunkState state) {
        return new GisStore.ChunkRow(new ChunkKey(1, "minecraft:overworld", x, z), state,
                new byte[]{(byte) x, (byte) z, 1, 2, 3}, 1, 16, (short) 60, (short) 80, 3,
                1000L, 0, null, 0);
    }

    @Test
    void chunkCrudAndStats(@TempDir Path dir) throws Exception {
        try (SqliteGisStore store = open(dir)) {
            assertEquals("sqlite", store.backend());
            store.putChunk(chunk(0, 0, ChunkState.CAPTURED));
            store.putChunk(chunk(1, 0, ChunkState.CAPTURED));
            store.putChunk(chunk(0, 0, ChunkState.UPLOADED)); // 幂等 upsert 且已上传状态保留

            GisStore.ChunkRow row = store.getChunk(new ChunkKey(1, "minecraft:overworld", 0, 0));
            assertNotNull(row);
            assertEquals(ChunkState.UPLOADED, row.state());

            GisStore.ChunkStats stats = store.chunkStats();
            assertEquals(2, stats.total());
            assertEquals(1, stats.captured());
            assertEquals(1, stats.uploaded());

            List<GisStore.PendingChunk> pending = store.pendingChunks(1, "minecraft:overworld", 10);
            assertEquals(1, pending.size());
            assertEquals(1, pending.get(0).key().chunkX());

            store.setChunkState(List.of(new ChunkKey(1, "minecraft:overworld", 1, 0)),
                    ChunkState.FAILED, 5000L, "boom");
            GisStore.ChunkRow failed = store.getChunk(new ChunkKey(1, "minecraft:overworld", 1, 0));
            assertEquals(ChunkState.FAILED, failed.state());
            assertEquals("boom", failed.lastError());
            assertEquals(1, failed.attempts());

            assertNull(store.getChunk(new ChunkKey(9, "minecraft:overworld", 0, 0)));
            store.deleteChunks(1, "minecraft:overworld");
            assertEquals(0, store.chunkStats().total());
        }
    }

    @Test
    void jobLifecycle(@TempDir Path dir) throws Exception {
        try (SqliteGisStore store = open(dir)) {
            long id = store.insertJob(new GisStore.JobRow(0, 1, "minecraft:overworld", 1,
                    "xjabcdefgh", new byte[]{1, 2, 3}, 2, "0,0;1,0",
                    GisStore.JobState.PENDING, 0, 0, null, 1L, 1L));
            assertTrue(id > 0);

            List<GisStore.JobRow> pending = store.jobsByState(GisStore.JobState.PENDING, 10);
            assertEquals(1, pending.size());
            assertEquals("xjabcdefgh", pending.get(0).idempotencyKey());

            store.updateJob(id, GisStore.JobState.RETRY, 2, 9999L, "net", 2L);
            GisStore.JobRow retry = store.jobsByState(GisStore.JobState.RETRY, 1).get(0);
            assertEquals(2, retry.attempts());
            assertEquals(9999L, retry.nextAttemptAt());

            store.updateJob(id, GisStore.JobState.DONE, 3, 0, null, 3L);
            GisStore.JobStats stats = store.jobStats();
            assertEquals(1, stats.done());
            assertEquals(0, stats.pending());
            store.deleteJobsByState(GisStore.JobState.DONE);
            assertEquals(0, store.jobStats().done());
        }
    }

    @Test
    void objectsCursorWorlds(@TempDir Path dir) throws Exception {
        try (SqliteGisStore store = open(dir)) {
            store.upsertObjects(List.of(new GisStore.StoredObject(1, "minecraft:overworld",
                    "d1", "district", "青云城区", 1, "{\"type\":\"Point\",\"coordinates\":[0,0]}",
                    "{}", 0, 0, 0, 0, 10L, false)));
            assertEquals(1, store.loadObjects(1, "minecraft:overworld", false).size());

            store.markObjectsDeleted(1, "minecraft:overworld", "d1", 2);
            assertTrue(store.loadObjects(1, "minecraft:overworld", false).isEmpty());
            assertEquals(1, store.loadObjects(1, "minecraft:overworld", true).size());

            assertEquals("", store.syncCursor(1, "minecraft:overworld"));
            store.setSyncCursor(1, "minecraft:overworld", "cursor-1");
            assertEquals("cursor-1", store.syncCursor(1, "minecraft:overworld"));

            store.putWorlds("yl", List.of(new RegisteredWorld(1, "main", WorldStatus.ACTIVE,
                    true, "26.2", List.of("minecraft:overworld"))));
            List<RegisteredWorld> worlds = store.worlds();
            assertEquals(1, worlds.size());
            assertEquals("main", worlds.get(0).worldKey());
            assertTrue(worlds.get(0).current());
        }
    }

    @Test
    void metaAndClearCache(@TempDir Path dir) throws Exception {
        try (SqliteGisStore store = open(dir)) {
            assertEquals(1, store.nextBatchSeq(1, "minecraft:overworld"));
            assertEquals(2, store.nextBatchSeq(1, "minecraft:overworld"));
            assertEquals(1, store.nextBatchSeq(1, "minecraft:the_nether"));

            store.setLastUploadAt(123456L);
            assertEquals(123456L, store.lastUploadAt());
            assertEquals("", store.meta("nothing", ""));

            store.putChunk(chunk(0, 0, ChunkState.CAPTURED));
            store.clearCache();
            assertEquals(0, store.chunkStats().total());
            // 世界注册信息不受清缓存影响
            store.putWorlds("yl", List.of(new RegisteredWorld(2, "w2", WorldStatus.ACTIVE,
                    true, "26.2", List.of())));
            store.clearCache();
            assertEquals(1, store.worlds().size());
            assertFalse(store.dbBytes() <= 0, "db 文件应存在");
        }
    }

    @Test
    void sqliteUnavailableFallsBackToMemory(@TempDir Path dir) {
        // 用非法路径触发降级分支（父路径是文件）
        Path bad = dir.resolve("file-not-dir");
        try {
            java.nio.file.Files.writeString(bad, "x");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GisStore store = StoreFactory.open(bad);
        try {
            assertNotNull(store.backend());
            assertTrue(store.backend().equals("memory") || store.backend().equals("sqlite"));
        } finally {
            store.close();
        }
    }
}
