package top.xuanjian.map.core.net;

import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;
import top.xuanjian.map.core.world.BootstrapInfo;

import java.util.List;

/**
 * GIS API 接口（与 xuanjian.top 契约一一对应）。
 *
 * <p>实现见 {@link HttpGisApi}；单元/集成测试用 mock HTTP 服务器验证真实报文。
 * 所有方法都是阻塞的，只能在后台线程调用。
 */
public interface GisApi {

    /** POST /api/gis/client/resolve-world 结果。 */
    record ResolveResult(boolean ok, long worldId, String worldKey, String dimension, String status) {
    }

    /** POST /api/gis/client/chunks/plan 结果：have 是服务端已有、missing 才需要上传。 */
    record PlanResult(List<int[]> have, List<int[]> missing) {
        public PlanResult {
            have = have == null ? List.of() : List.copyOf(have);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }

        public int missingCount() {
            return missing.size();
        }
    }

    /** POST /api/gis/client/chunks 结果（幂等重放 duplicate=true）。 */
    record UploadResult(boolean duplicate, int stored, int received) {
    }

    /** tombstone（本地缓存删除）。 */
    record DeletedObject(String type, String id, int revision) {
    }

    /** GET /api/gis/client/sync 结果。 */
    record SyncResult(List<GisObject> objects, List<DeletedObject> deleted,
                      String cursor, boolean hasMore) {
        public SyncResult {
            objects = objects == null ? List.of() : List.copyOf(objects);
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
        }
    }

    record CoverageChunk(int chunkX, int chunkZ, int minY, int maxY, boolean hasGaps) {
    }

    /** GET /api/gis/terrain/coverage 结果。 */
    record CoverageResult(double coverage, List<CoverageChunk> chunks, boolean hasGaps) {
        public CoverageResult {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    /** 启动引导：取服务器与其世界列表。 */
    BootstrapInfo bootstrap(String serverKey) throws ApiException;

    /** 世界绑定校验（客户端不能指定他人世界）。 */
    ResolveResult resolveWorld(String serverKey, long worldId, String worldKey, String dimension)
            throws ApiException;

    /** 增量计划：服务端已有 have，客户端只传 missing。 */
    PlanResult planChunks(long worldId, String dimension, List<int[]> chunks) throws ApiException;

    /** 上传二进制批次（Content-Encoding + Idempotency-Key + X-GIS-Contract）。 */
    UploadResult uploadChunks(long worldId, String dimension, String idempotencyKey,
                              String contentEncoding, byte[] payload) throws ApiException;

    /** 增量同步 GIS 对象（含 tombstone）。 */
    SyncResult sync(long worldId, String dimension, String sinceCursor) throws ApiException;

    /** bbox 查询对象（面积上限 16,000,000 blocks²，只返 Published）。 */
    List<GisObject> objects(long worldId, String dimension, BBox bbox,
                            List<GisObjectKind> types, int limit, String cursor) throws ApiException;

    /** 地形覆盖度（客户端数据状态页用，不伪造未采集区域）。 */
    CoverageResult coverage(long worldId, String dimension, BBox bbox) throws ApiException;
}
