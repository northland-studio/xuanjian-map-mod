package top.xuanjian.map.testutil;

import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;
import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.model.RegisteredWorld;
import top.xuanjian.map.core.model.WorldStatus;
import top.xuanjian.map.core.net.ApiException;
import top.xuanjian.map.core.net.GisApi;
import top.xuanjian.map.core.chunk.ChunkBatchCodec;
import top.xuanjian.map.core.chunk.DecodedBatch;
import top.xuanjian.map.core.world.BootstrapInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 记录调用、可编排错误的 GisApi 假实现（单元测试与 mock 契约测试共用）。 */
public class FakeGisApi implements GisApi {

    public final List<String> calls = new ArrayList<>();
    public final List<DecodedBatch> uploadedBatches = new ArrayList<>();
    public final List<String> idempotencyKeys = new ArrayList<>();
    public final List<String> contentEncodings = new ArrayList<>();
    public final Set<Long> serverHas = new HashSet<>();
    public final List<ApiException> uploadErrors = new ArrayList<>();
    public final List<ApiException> planErrors = new ArrayList<>();

    public long worldId = 1;
    public String worldKey = "xuanjian-main";
    public String dimension = "minecraft:overworld";
    public boolean planThrowsNetwork = false;
    public int syncObjects = 0;

    @Override
    public BootstrapInfo bootstrap(String serverKey) {
        calls.add("bootstrap:" + serverKey);
        return new BootstrapInfo(1, serverKey, "玄剑",
                List.of(new RegisteredWorld(worldId, worldKey, WorldStatus.ACTIVE, true, "26.2",
                        List.of("minecraft:overworld", "minecraft:the_nether"))),
                Limits.DEFAULT, 60);
    }

    @Override
    public ResolveResult resolveWorld(String serverKey, long worldId, String worldKey, String dimension) {
        calls.add("resolve:" + worldId + ":" + dimension);
        return new ResolveResult(true, worldId, worldKey, dimension, "ACTIVE");
    }

    @Override
    public PlanResult planChunks(long worldId, String dimension, List<int[]> chunks) throws ApiException {
        calls.add("plan:" + chunks.size());
        if (!planErrors.isEmpty()) {
            throw planErrors.remove(0);
        }
        List<int[]> have = new ArrayList<>();
        List<int[]> missing = new ArrayList<>();
        for (int[] c : chunks) {
            if (serverHas.contains(coord(c[0], c[1]))) {
                have.add(c);
            } else {
                missing.add(c);
            }
        }
        return new PlanResult(have, missing);
    }

    @Override
    public UploadResult uploadChunks(long worldId, String dimension, String idempotencyKey,
                                     String contentEncoding, byte[] payload) throws ApiException {
        calls.add("upload");
        idempotencyKeys.add(idempotencyKey);
        contentEncodings.add(contentEncoding);
        if (!uploadErrors.isEmpty()) {
            throw uploadErrors.remove(0);
        }
        byte[] raw = top.xuanjian.map.core.compress.DeflateCodec.INSTANCE.decompress(payload);
        DecodedBatch batch = ChunkBatchCodec.decode(raw);
        uploadedBatches.add(batch);
        for (var chunk : batch.chunks()) {
            serverHas.add(coord(chunk.chunkX(), chunk.chunkZ()));
        }
        return new UploadResult(false, batch.chunks().size(), batch.chunks().size());
    }

    @Override
    public SyncResult sync(long worldId, String dimension, String sinceCursor) {
        calls.add("sync:" + sinceCursor);
        return new SyncResult(List.of(), List.of(), "cursor-1", false);
    }

    @Override
    public List<GisObject> objects(long worldId, String dimension, BBox bbox,
                                   List<GisObjectKind> types, int limit, String cursor) {
        calls.add("objects");
        return List.of();
    }

    @Override
    public CoverageResult coverage(long worldId, String dimension, BBox bbox) {
        calls.add("coverage");
        return new CoverageResult(0.5, List.of(), false);
    }

    public static long coord(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
