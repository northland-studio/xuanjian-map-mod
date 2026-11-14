package top.xuanjian.map.core.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.xuanjian.map.core.chunk.ChunkBatchCodec;
import top.xuanjian.map.core.chunk.ChunkData;
import top.xuanjian.map.core.chunk.DecodedBatch;
import top.xuanjian.map.core.compress.DeflateCodec;
import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.GisObjectKind;
import top.xuanjian.map.core.world.BootstrapInfo;
import top.xuanjian.map.testutil.TestChunks;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mock 服务端契约集成测试：真实 HTTP 往返，验证请求头/二进制格式/幂等/错误码。
 * 服务端 API 上线前用它保证客户端协议实现正确（Lead 要求）。
 */
class HttpGisApiMockTest {

    private static HttpServer server;
    private static String baseUrl;

    private static final List<String> requestLog = new ArrayList<>();
    private static final Set<String> seenIdempotencyKeys = new HashSet<>();
    private static volatile String lastAuthHeader = "";
    private static volatile String lastContractHeader = "";
    private static volatile String lastContentEncoding = "";
    private static volatile String lastIdempotencyKey = "";
    private static volatile String lastQuery = "";
    private static volatile String lastResolveBody = "";
    private static volatile DecodedBatch lastUploadBody;
    private static volatile boolean rateLimitNext;
    private static volatile boolean forbiddenNext;
    private static volatile boolean invalidPayloadNext;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/gis/client/bootstrap", ex -> {
            record(ex);
            String serverKey = queryParam(ex, "serverKey");
            json(ex, 200, """
                    {"contract":1,"server":{"key":"%s","name":"玄剑生产服"},
                     "worlds":[
                       {"id":1,"worldKey":"xuanjian-main","status":"ACTIVE","isCurrent":true,
                        "mcVersion":"26.2","dimensions":["minecraft:overworld","minecraft:the_nether"]},
                       {"id":2,"worldKey":"xuanjian-s2","status":"ARCHIVED","isCurrent":false,
                        "mcVersion":"1.21.11","dimensions":["minecraft:overworld"]}],
                     "limits":{"maxPlanChunks":4096,"maxBatchChunks":64,"maxCompressedBytes":4194304,
                               "maxRawBytes":8388608,"maxSampleSize":32,"requestsPerMinute":60},
                     "sync":{"intervalSeconds":45}}
                    """.formatted(serverKey));
        });
        server.createContext("/api/gis/client/resolve-world", ex -> {
            record(ex);
            lastResolveBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            json(ex, 200, """
                    {"ok":true,"world":{"id":1,"worldKey":"xuanjian-main","status":"ACTIVE",
                     "dimension":"minecraft:overworld"}}
                    """);
        });
        server.createContext("/api/gis/client/chunks/plan", ex -> {
            record(ex);
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // 用坐标 0,0 表示“服务端已有”，其余都 missing
            boolean hasFirst = body.contains("\"x\":0");
            json(ex, 200, hasFirst
                    ? "{\"have\":[[0,0]],\"missing\":[[1,0],[2,0]]}"
                    : "{\"have\":[],\"missing\":[[1,0],[2,0]]}");
        });
        server.createContext("/api/gis/client/chunks", ex -> {
            record(ex);
            lastIdempotencyKey = ex.getRequestHeaders().getFirst("Idempotency-Key");
            lastContentEncoding = ex.getRequestHeaders().getFirst("Content-Encoding");
            lastContractHeader = ex.getRequestHeaders().getFirst("X-GIS-Contract");
            lastQuery = ex.getRequestURI().getQuery();
            if (rateLimitNext) {
                ex.getResponseHeaders().add("Retry-After", "3");
                json(ex, 429, "{\"ok\":false,\"error\":\"rate_limited\",\"message\":\"too many requests\"}");
                return;
            }
            if (forbiddenNext) {
                json(ex, 403, "{\"ok\":false,\"error\":\"forbidden\",\"required\":\"gis.submit\"}");
                return;
            }
            if (invalidPayloadNext) {
                json(ex, 400, "{\"ok\":false,\"error\":\"invalid_payload\",\"message\":\"bad chunk\",\"chunkIndex\":0}");
                return;
            }
            byte[] compressed = ex.getRequestBody().readAllBytes();
            byte[] raw = "deflate".equals(lastContentEncoding)
                    ? DeflateCodec.INSTANCE.decompress(compressed) : compressed;
            lastUploadBody = ChunkBatchCodec.decode(raw);
            boolean duplicate = !seenIdempotencyKeys.add(lastIdempotencyKey);
            json(ex, 200, "{\"ok\":true,\"duplicate\":" + duplicate
                    + ",\"stored\":" + lastUploadBody.chunks().size()
                    + ",\"received\":" + lastUploadBody.chunks().size() + "}");
        });
        server.createContext("/api/gis/client/sync", ex -> {
            record(ex);
            json(ex, 200, """
                    {"objects":[
                       {"type":"district","id":"d1","name":"青云城区","revision":3,
                        "geometry":{"type":"Polygon","coordinates":[[[-100,-100],[100,-100],[100,100],[-100,100]]]},
                        "properties":{"color":"#1D4ED8"}},
                       {"type":"road","id":"r1","name":"中央大道","revision":2,
                        "geometry":{"type":"LineString","coordinates":[[0,0],[200,0]]},
                        "properties":{"width":10}},
                       {"type":"marker","id":"m1","name":"玄剑集市","revision":1,
                        "geometry":{"type":"Point","coordinates":[10,20]},
                        "properties":{"category":"shop"}}],
                     "deleted":[{"type":"marker","id":"m0","revision":4}],
                     "cursor":"cursor-2","hasMore":false}
                    """);
        });
        server.createContext("/api/gis/objects", ex -> {
            record(ex);
            lastQuery = ex.getRequestURI().getQuery();
            json(ex, 200, """
                    {"objects":[
                      {"type":"marker","id":"m9","name":"测试点","revision":1,
                       "geometry":{"type":"Point","coordinates":[5,6]},"properties":{}}]}
                    """);
        });
        server.createContext("/api/gis/terrain/coverage", ex -> {
            record(ex);
            json(ex, 200, """
                    {"coverage":0.42,"hasGaps":true,
                     "chunks":[{"chunkX":0,"chunkZ":0,"minY":60,"maxY":90,"hasGaps":true}],
                     "missing":[[5,5]]}
                    """);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void record(HttpExchange ex) {
        lastAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
        requestLog.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
    }

    private static void json(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) {
            return "";
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx > 0 && part.substring(0, idx).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private HttpGisApi api(Path dir) {
        GisConfig config = new GisConfig(dir);
        config.apiBase(baseUrl);
        TokenStore tokens = new TokenStore(dir);
        tokens.save(new OAuthTokens("test-token", "refresh-token",
                System.currentTimeMillis() + 3600_000L, "read:user read:member"));
        return new HttpGisApi(config, tokens);
    }

    private static void resetFlags() {
        rateLimitNext = false;
        forbiddenNext = false;
        invalidPayloadNext = false;
        seenIdempotencyKeys.clear();
    }

    @Test
    void bootstrap_parsesWorldsLimitsAndAuth(@TempDir Path dir) throws Exception {
        HttpGisApi api = api(dir);
        BootstrapInfo info = api.bootstrap("yl");
        assertEquals(1, info.contract());
        assertEquals("yl", info.serverKey());
        assertEquals("玄剑生产服", info.serverName());
        assertEquals(2, info.worlds().size());
        assertEquals("xuanjian-main", info.currentWorld().worldKey());
        assertEquals(4096, info.limits().maxPlanChunks());
        assertEquals(64, info.limits().maxBatchChunks());
        assertEquals(45, info.syncIntervalSeconds());
        assertEquals("Bearer test-token", lastAuthHeader);
        assertTrue(requestLog.get(0).contains("/api/gis/client/bootstrap"));
    }

    @Test
    void resolveWorld_sendsRequiredFields(@TempDir Path dir) throws Exception {
        HttpGisApi api = api(dir);
        GisApi.ResolveResult result = api.resolveWorld("yl", 1, "xuanjian-main", "overworld");
        assertTrue(result.ok());
        assertEquals(1, result.worldId());
        assertEquals("minecraft:overworld", result.dimension());
        assertTrue(lastResolveBody.contains("\"serverKey\":\"yl\""));
        assertTrue(lastResolveBody.contains("\"worldId\":1"));
        assertTrue(lastResolveBody.contains("\"worldKey\":\"xuanjian-main\""));
    }

    @Test
    void planChunks_parsesHaveAndMissing(@TempDir Path dir) throws Exception {
        HttpGisApi api = api(dir);
        GisApi.PlanResult plan = api.planChunks(1, "minecraft:overworld",
                List.of(new int[]{0, 0}, new int[]{1, 0}, new int[]{2, 0}));
        assertEquals(1, plan.have().size());
        assertEquals(2, plan.missingCount());
        assertEquals(1, plan.missing().get(0)[0]);
    }

    @Test
    void uploadChunks_binaryContractAndIdempotency(@TempDir Path dir) throws Exception {
        resetFlags();
        HttpGisApi api = api(dir);
        ChunkData a = TestChunks.sample(1, 0, true, true);
        ChunkData b = TestChunks.sample(2, 0, false, false);
        byte[] raw = ChunkBatchCodec.encode(1, "minecraft:overworld", 7, List.of(a, b));
        byte[] compressed = DeflateCodec.INSTANCE.compress(raw);
        String idem = "xj0123456789abcdef0123456789abcd";

        GisApi.UploadResult result = api.uploadChunks(1, "minecraft:overworld", idem,
                "deflate", compressed);
        assertEquals(2, result.stored());
        assertFalse(result.duplicate());
        assertEquals("deflate", lastContentEncoding);
        assertEquals("1", lastContractHeader);
        assertEquals(idem, lastIdempotencyKey);
        assertTrue(lastQuery.contains("worldId=1"));
        assertTrue(lastQuery.contains("dimension=minecraft%3Aoverworld"));
        assertNotNull(lastUploadBody);
        assertEquals(2, lastUploadBody.chunks().size());
        assertEquals(7, lastUploadBody.batchSeq());
        assertEquals(ChunkData.NO_DATA, lastUploadBody.chunks().get(0).heights()[0],
                "服务端解出的 -32768 缺口必须保留");

        GisApi.UploadResult replay = api.uploadChunks(1, "minecraft:overworld", idem,
                "deflate", compressed);
        assertTrue(replay.duplicate(), "同一 Idempotency-Key 重放应返回 duplicate:true");
    }

    @Test
    void errorMapping_rateLimitedAndForbiddenAndInvalidPayload(@TempDir Path dir) {
        resetFlags();
        HttpGisApi api = api(dir);
        byte[] payload = DeflateCodec.INSTANCE.compress(ChunkBatchCodec.encode(1,
                "minecraft:overworld", 1, List.of(TestChunks.sample(0, 0, false, false))));

        rateLimitNext = true;
        ApiException rate = assertThrows(ApiException.class,
                () -> api.uploadChunks(1, "minecraft:overworld", "xjrate0001", "deflate", payload));
        assertEquals("rate_limited", rate.code());
        assertEquals(429, rate.httpStatus());
        assertEquals(3, rate.retryAfterSeconds());
        assertTrue(rate.isRateLimited());

        resetFlags();
        forbiddenNext = true;
        ApiException forbidden = assertThrows(ApiException.class,
                () -> api.uploadChunks(1, "minecraft:overworld", "xjforbid01", "deflate", payload));
        assertEquals("forbidden", forbidden.code());
        assertEquals(403, forbidden.httpStatus());
        assertEquals("缺少权限: gis.submit", forbidden.getMessage());
        assertTrue(forbidden.isPermanent());

        resetFlags();
        invalidPayloadNext = true;
        ApiException invalid = assertThrows(ApiException.class,
                () -> api.uploadChunks(1, "minecraft:overworld", "xjinvalid1", "deflate", payload));
        assertEquals("invalid_payload", invalid.code());
        assertEquals(0, invalid.chunkIndex());
        assertTrue(invalid.isPermanent());
    }

    @Test
    void sync_parsesObjectsTombstonesAndCursor(@TempDir Path dir) throws Exception {
        HttpGisApi api = api(dir);
        GisApi.SyncResult sync = api.sync(1, "minecraft:overworld", "cursor-1");
        assertEquals(3, sync.objects().size());
        assertEquals(1, sync.deleted().size());
        assertEquals("m0", sync.deleted().get(0).id());
        assertEquals("cursor-2", sync.cursor());
        assertFalse(sync.hasMore());

        var district = sync.objects().get(0);
        assertEquals(GisObjectKind.DISTRICT, district.kind());
        assertEquals("青云城区", district.name());
        assertTrue(((top.xuanjian.map.core.model.Geometry.GisPolygon) district.geometry())
                .contains(0, 0));

        var road = sync.objects().get(1);
        assertEquals(GisObjectKind.ROAD, road.kind());
        assertEquals(10.0, road.roadWidth(), 1e-9);

        var marker = sync.objects().get(2);
        assertEquals(GisObjectKind.MARKER, marker.kind());
        assertEquals(top.xuanjian.map.core.model.MarkerCategory.SHOP, marker.markerCategory());
    }

    @Test
    void objectsAndCoverage(@TempDir Path dir) throws Exception {
        HttpGisApi api = api(dir);
        var objects = api.objects(1, "minecraft:overworld", new BBox(-100, -100, 100, 100),
                List.of(GisObjectKind.MARKER), 200, "c0");
        assertEquals(1, objects.size());
        assertEquals("m9", objects.get(0).id());
        assertTrue(lastQuery.contains("bbox=-100.0%2C-100.0%2C100.0%2C100.0")
                || lastQuery.contains("bbox=-100.0,-100.0,100.0,100.0"));

        GisApi.CoverageResult coverage = api.coverage(1, "minecraft:overworld",
                new BBox(0, 0, 1000, 1000));
        assertEquals(0.42, coverage.coverage(), 1e-9);
        assertEquals(1, coverage.chunks().size());
        assertTrue(coverage.hasGaps(), "有 missing 时必须标记缺口，不能当作平地");
    }
}
