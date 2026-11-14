package top.xuanjian.map.core.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.model.WorldStatus;
import top.xuanjian.map.core.util.GisLog;
import top.xuanjian.map.core.world.BootstrapInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GIS HTTP 客户端（JDK HttpClient，无第三方 HTTP 依赖）。
 *
 * <p>按 Lead 定稿契约实现：Authorization Bearer、X-GIS-Contract: 1、
 * 二进制上传 + Content-Encoding + Idempotency-Key、统一错误 JSON。
 */
public final class HttpGisApi implements GisApi {

    private static final String TAG = "gis-api";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(90);

    private final GisConfig config;
    private final TokenStore tokenStore;
    private final HttpClient http;
    private final String userAgent;
    private final ConcurrentHashMap<String, String> locationCache = new ConcurrentHashMap<>();

    public HttpGisApi(GisConfig config, TokenStore tokenStore) {
        this.config = config;
        this.tokenStore = tokenStore;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = "xuanjian-map-mod/" + System.getProperty("xjm.version", "0.1.0");
    }

    /* ==================== bootstrap ==================== */

    @Override
    public BootstrapInfo bootstrap(String serverKey) throws ApiException {
        String path = "/api/gis/client/bootstrap?serverKey=" + enc(serverKey);
        JsonObject json = getJson(path, true);
        int contract = Json.optInt(json, "contract", 1);
        JsonObject server = Json.optObject(json, "server");
        String key = server != null ? Json.optString(server, "key", serverKey)
                : Json.optString(json, "serverKey", serverKey);
        String name = server != null ? Json.optString(server, "name", key)
                : Json.optString(json, "serverName", key);

        List<RegisteredWorld> worlds = new ArrayList<>();
        for (JsonElement e : Json.optArray(json, "worlds")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject w = e.getAsJsonObject();
            List<String> dims = new ArrayList<>();
            for (JsonElement d : Json.optArray(w, "dimensions")) {
                if (d.isJsonPrimitive()) {
                    dims.add(Dimensions.normalize(d.getAsString()));
                } else if (d.isJsonObject()) {
                    // 生产契约：dimensions[] 是对象 {dimension, chunkCount, bbox, updatedAt}
                    String value = Json.optString(d.getAsJsonObject(), "dimension", "");
                    if (!value.isBlank()) {
                        dims.add(Dimensions.normalize(value));
                    }
                }
            }
            worlds.add(new RegisteredWorld(
                    Json.optLong(w, "id", 0),
                    Json.optString(w, "worldKey", ""),
                    WorldStatus.fromWire(Json.optString(w, "status", "ACTIVE")),
                    Json.optBool(w, "isCurrent", Json.optBool(w, "current", false)),
                    Json.optString(w, "mcVersion", ""),
                    dims));
        }

        JsonObject limitsJson = Json.optObject(json, "limits");
        Limits limits = Limits.DEFAULT;
        if (limitsJson != null) {
            // 同时兼容 Lead 转述的命名（maxPlanChunks/maxBatchChunks/maxRawBytes）
            // 与生产 /api/gis/client/contract 的命名（planChunksMax/maxChunkBatch/maxDecompressedBytes）
            limits = new Limits(
                    Json.optInt(limitsJson, "maxPlanChunks",
                            Json.optInt(limitsJson, "planChunksMax", Limits.DEFAULT.maxPlanChunks())),
                    Json.optInt(limitsJson, "maxBatchChunks",
                            Json.optInt(limitsJson, "maxChunkBatch", Limits.DEFAULT.maxBatchChunks())),
                    Json.optLong(limitsJson, "maxCompressedBytes", Limits.DEFAULT.maxCompressedBytes()),
                    Json.optLong(limitsJson, "maxRawBytes",
                            Json.optLong(limitsJson, "maxDecompressedBytes", Limits.DEFAULT.maxRawBytes())),
                    Json.optInt(limitsJson, "maxSampleSize", Limits.DEFAULT.maxSampleSize()),
                    Json.optInt(limitsJson, "requestsPerMinute",
                            Json.optInt(limitsJson, "uploadRatePerMinute", Limits.DEFAULT.requestsPerMinute())));
        }
        JsonObject sync = Json.optObject(json, "sync");
        int syncInterval = sync != null
                ? Json.optInt(sync, "intervalSeconds", Json.optInt(sync, "interval", 60))
                : Json.optInt(json, "syncIntervalSeconds", 60);
        GisLog.info(TAG, "bootstrap: server=" + key + ", worlds=" + worlds.size()
                + ", contract=" + contract);
        return new BootstrapInfo(contract, key, name, worlds, limits, syncInterval);
    }

    /* ==================== resolve-world ==================== */

    @Override
    public ResolveResult resolveWorld(String serverKey, long worldId, String worldKey, String dimension)
            throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("serverKey", serverKey);
        body.addProperty("worldId", worldId);
        body.addProperty("worldKey", worldKey);
        body.addProperty("dimension", Dimensions.normalize(dimension));
        JsonObject json = postJson("/api/gis/client/resolve-world", body, true);
        JsonObject world = Json.optObject(json, "world");
        long resolvedId = world != null ? Json.optLong(world, "id", worldId) : Json.optLong(json, "worldId", worldId);
        String resolvedKey = world != null ? Json.optString(world, "worldKey", worldKey)
                : Json.optString(json, "worldKey", worldKey);
        String resolvedDim = world != null ? Json.optString(world, "dimension", dimension)
                : Json.optString(json, "dimension", dimension);
        String status = world != null ? Json.optString(world, "status", "ACTIVE")
                : Json.optString(json, "status", "ACTIVE");
        return new ResolveResult(Json.optBool(json, "ok", true), resolvedId, resolvedKey,
                Dimensions.normalize(resolvedDim), status);
    }

    /* ==================== plan ==================== */

    @Override
    public PlanResult planChunks(long worldId, String dimension, List<int[]> chunks) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("worldId", worldId);
        body.addProperty("dimension", Dimensions.normalize(dimension));
        JsonArray arr = new JsonArray();
        for (int[] c : chunks) {
            JsonObject o = new JsonObject();
            o.addProperty("x", c[0]);
            o.addProperty("z", c[1]);
            arr.add(o);
        }
        body.add("chunks", arr);
        JsonObject json = postJson("/api/gis/client/chunks/plan", body, true);
        return new PlanResult(parseChunkList(Json.optArray(json, "have")),
                parseChunkList(Json.optArray(json, "missing")));
    }

    /* ==================== upload ==================== */

    @Override
    public UploadResult uploadChunks(long worldId, String dimension, String idempotencyKey,
                                     String contentEncoding, byte[] payload) throws ApiException {
        String path = "/api/gis/client/chunks?worldId=" + worldId
                + "&dimension=" + enc(Dimensions.normalize(dimension));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(UPLOAD_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", contentEncoding)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-GIS-Contract", String.valueOf(GisConfig.GIS_CONTRACT))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        applyAuth(b);
        HttpResponse<String> response = execute(b.build());
        JsonObject json = jsonOrThrow(response);
        return new UploadResult(
                Json.optBool(json, "duplicate", false),
                Json.optInt(json, "stored", Json.optInt(json, "chunks", 0)),
                Json.optInt(json, "received", 0));
    }

    /* ==================== sync ==================== */

    @Override
    public SyncResult sync(long worldId, String dimension, String sinceCursor) throws ApiException {
        String path = "/api/gis/client/sync?worldId=" + worldId
                + "&dimension=" + enc(Dimensions.normalize(dimension))
                + "&since=" + enc(sinceCursor == null ? "" : sinceCursor);
        JsonObject json = getJson(path, true);
        List<GisObject> objects = ObjectJson.fromApiList(
                Json.optArray(json, "objects"), worldId, dimension);
        List<DeletedObject> deleted = new ArrayList<>();
        for (JsonElement e : Json.optArray(json, "deleted")) {
            if (e.isJsonObject()) {
                JsonObject d = e.getAsJsonObject();
                deleted.add(new DeletedObject(Json.optString(d, "type", "other"),
                        Json.optString(d, "id", ""), Json.optInt(d, "revision", 0)));
            }
        }
        return new SyncResult(objects, deleted,
                Json.optString(json, "cursor", sinceCursor == null ? "" : sinceCursor),
                Json.optBool(json, "hasMore", false));
    }

    /* ==================== objects / coverage ==================== */

    @Override
    public List<GisObject> objects(long worldId, String dimension, BBox bbox,
                                   List<GisObjectKind> types, int limit, String cursor)
            throws ApiException {
        if (bbox == null) {
            throw new ApiException("bbox_required", "objects 查询必须带 bbox", 400);
        }
        if (bbox.area() > 16_000_000) {
            throw new ApiException("bbox_too_large",
                    "bbox 面积超限: " + (long) bbox.area() + " > 16000000", 400);
        }
        int maxPerPage = Math.min(limit, Limits.DEFAULT.maxObjectsPerPage());
        StringBuilder path = new StringBuilder("/api/gis/objects")
                .append("?worldId=").append(worldId)
                .append("&dimension=").append(enc(Dimensions.normalize(dimension)))
                .append("&bbox=").append(enc(bbox.toString()))
                .append("&limit=").append(Math.max(1, Math.min(maxPerPage, 1000)));
        if (types != null && !types.isEmpty()) {
            StringBuilder t = new StringBuilder();
            for (GisObjectKind k : types) {
                if (t.length() > 0) {
                    t.append(',');
                }
                t.append(k.wire());
            }
            path.append("&types=").append(enc(t.toString()));
        }
        if (cursor != null && !cursor.isBlank()) {
            path.append("&cursor=").append(enc(cursor));
        }
        String body = rawGet(path.toString(), true);
        JsonObject json = Json.parseObject(body);
        JsonArray array = json.has("objects")
                ? Json.optArray(json, "objects")
                : (json.has("data") ? Json.optArray(json, "data") : new JsonArray());
        return ObjectJson.fromApiList(array, worldId, dimension);
    }

    @Override
    public CoverageResult coverage(long worldId, String dimension, BBox bbox) throws ApiException {
        String path = "/api/gis/terrain/coverage?worldId=" + worldId
                + "&dimension=" + enc(Dimensions.normalize(dimension))
                + "&bbox=" + enc(bbox.toString());
        JsonObject json = getJson(path, true);
        double coverage = Json.optDouble(json, "coverage", 0);
        if (coverage > 1.0) {
            coverage = coverage / 100.0;
        }
        List<CoverageChunk> chunks = new ArrayList<>();
        boolean anyGap = Json.optBool(json, "hasGaps", false);
        for (JsonElement e : Json.optArray(json, "chunks")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject c = e.getAsJsonObject();
            boolean gaps = Json.optBool(c, "hasGaps", false);
            anyGap |= gaps;
            chunks.add(new CoverageChunk(
                    Json.optInt(c, "chunkX", 0), Json.optInt(c, "chunkZ", 0),
                    Json.optInt(c, "minY", 0), Json.optInt(c, "maxY", 0), gaps));
        }
        if (!Json.optArray(json, "missing").isEmpty()) {
            anyGap = true;
        }
        return new CoverageResult(coverage, chunks, anyGap);
    }

    /* ==================== HTTP 基础设施 ==================== */

    private String base() {
        String b = config.apiBase();
        return b == null || b.isBlank() ? GisConfig.DEFAULT_API_BASE
                : (b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
    }

    private String cacheKey(String path) {
        return path;
    }

    private JsonObject getJson(String path, boolean auth) throws ApiException {
        return Json.parseObject(rawGet(path, auth));
    }

    private String rawGet(String path, boolean auth) throws ApiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("X-GIS-Contract", String.valueOf(GisConfig.GIS_CONTRACT))
                .header("User-Agent", userAgent)
                .GET();
        if (auth) {
            applyAuth(b);
        }
        HttpResponse<String> response = execute(b.build());
        jsonOrThrow(response);
        return response.body();
    }

    private JsonObject postJson(String path, JsonObject body, boolean auth) throws ApiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-GIS-Contract", String.valueOf(GisConfig.GIS_CONTRACT))
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        if (auth) {
            applyAuth(b);
        }
        HttpResponse<String> response = execute(b.build());
        return jsonOrThrow(response);
    }

    private void applyAuth(HttpRequest.Builder b) {
        OAuthTokens tokens = tokenStore.tokens();
        if (tokens.hasAccessToken()) {
            b.header("Authorization", "Bearer " + tokens.accessToken());
        }
        if (config.serverToken() != null && !config.serverToken().isBlank()) {
            b.header("X-GIS-Server-Token", config.serverToken());
        }
    }

    private HttpResponse<String> execute(HttpRequest request) throws ApiException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException("server_error", "网络请求失败: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("server_error", "网络请求被中断", 0);
        }
    }

    private JsonObject jsonOrThrow(HttpResponse<String> response) throws ApiException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Json.parseObject(response.body());
        }
        JsonObject body = Json.parseObjectQuietly(response.body());
        String code = Json.optString(body, "error", "server_error");
        String message = Json.optString(body, "message", "");
        int retryAfter = response.headers().firstValue("Retry-After")
                .map(s -> {
                    try {
                        return Integer.parseInt(s.trim());
                    } catch (NumberFormatException ex) {
                        return -1;
                    }
                }).orElse(-1);
        String required = Json.optString(body, "required", "");
        if (!required.isBlank() && message.isBlank()) {
            message = "缺少权限: " + required;
        }
        int chunkIndex = Json.optInt(body, "chunkIndex", -1);
        throw new ApiException(code, message, status, retryAfter, chunkIndex);
    }

    private static List<int[]> parseChunkList(JsonArray array) {
        List<int[]> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                if (a.size() >= 2) {
                    out.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()});
                }
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                out.add(new int[]{
                        Json.optInt(o, "x", Json.optInt(o, "chunkX", 0)),
                        Json.optInt(o, "z", Json.optInt(o, "chunkZ", 0))});
            }
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
