package top.xuanjian.map.client.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import top.xuanjian.map.client.compat.McReflect;
import top.xuanjian.map.core.chunk.ChunkData;
import top.xuanjian.map.core.net.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 区块采样器（跨版本反射实现，26.2 / 1.21.11 共用）。
 *
 * <p>只采集 Minecraft <b>原生</b>数据：高度矩阵、biome、表面方块类型、结构、block entity 类型。
 * 规范明确禁止自动推断玩家建筑/道路/城区/商店 —— 本类不做任何推断。
 *
 * <p>缺数据（未加载/接口不可用）一律写 {@link ChunkData#NO_DATA}，绝不填 0。
 */
public final class ChunkSampler {

    /** 采样结果：契约二进制数据 + 仅本地保存的原生扩展数据。 */
    public record Capture(ChunkData data, String structuresJson, String blockEntitiesJson,
                          String blockPaletteJson, List<String> warnings) {
    }

    public record Result(Capture capture, String error) {
        public boolean ok() {
            return capture != null;
        }

        public static Result fail(String error) {
            return new Result(null, error);
        }
    }

    private static final int MAX_STRUCTURES = 32;
    private static final int MAX_BLOCK_ENTITIES = 64;

    private static final Object LOCK = new Object();
    private static boolean resolved;
    private static Class<?> blockPosClass;
    private static Class<?> heightmapTypesClass;
    private static Object worldSurface;
    private static Object blockRegistry;
    private static Object biomeRegistry;
    private static Object structureRegistry;
    private static Object blockEntityTypeRegistry;

    private ChunkSampler() {
    }

    /** sampleStep 必须是能整除 16 的值（1/2/4/8/16）。 */
    public static int normalizeStep(int step) {
        if (step <= 1) {
            return 1;
        }
        if (step >= 16) {
            return 16;
        }
        return step <= 2 ? 2 : step <= 4 ? 4 : 8;
    }

    public static Result sample(Object level, int chunkX, int chunkZ, int sampleStep,
                                boolean includeBlockHist) {
        if (level == null) {
            return Result.fail("level 为 null（未进入世界）");
        }
        int step = normalizeStep(sampleStep);
        int n = 16 / step;
        resolveTypes();

        Object chunk = fetchChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return Result.fail("无法取得 chunk (" + chunkX + "," + chunkZ + ")");
        }

        List<String> warnings = new ArrayList<>();
        short[] heights = new short[n * n];
        int[] biomes = new int[n * n];
        java.util.Arrays.fill(heights, ChunkData.NO_DATA);
        java.util.Arrays.fill(biomes, -1);
        String[] surfaceBlocks = new String[n * n];
        boolean anyHeight = false;
        boolean anyBiome = false;

        Object blockPosClassLocal = blockPosClass;
        Map<Object, String> blockIdCache = new IdentityHashMap<>();
        Map<Object, Integer> biomeIdCache = new IdentityHashMap<>();

        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                int idx = row * n + col;
                int lx = col * step;
                int lz = row * step;
                int worldX = chunkX * 16 + lx;
                int worldZ = chunkZ * 16 + lz;

                Integer height = readHeight(chunk, level, lx, lz, worldX, worldZ);
                if (height == null) {
                    continue;
                }
                heights[idx] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, height));
                anyHeight = true;

                Object pos = McReflect.construct(blockPosClassLocal, worldX, heights[idx] - 1, worldZ);
                if (pos == null) {
                    warnings.add("BlockPos 构造失败");
                    continue;
                }

                Object biomeId = readBiomeId(level, pos, biomeIdCache);
                if (biomeId instanceof Integer id) {
                    biomes[idx] = id;
                    anyBiome = true;
                } else {
                    biomes[idx] = -1;
                }

                String blockId = readBlockId(chunk, level, pos, blockIdCache);
                surfaceBlocks[idx] = blockId;
            }
        }

        if (!anyHeight) {
            return Result.fail("高度矩阵全部不可用（heightmap API 不匹配），本次跳过该区块");
        }
        int[] biomeMatrix = anyBiome ? biomes : null;

        // 表面方块调色板（本地保存；契约 blockHist 布局待确认后才上传）
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        short[] blockIndices = new short[n * n];
        java.util.Arrays.fill(blockIndices, (short) -1);
        for (int i = 0; i < surfaceBlocks.length; i++) {
            String id = surfaceBlocks[i];
            if (id == null) {
                continue;
            }
            int pi = paletteIndex.computeIfAbsent(id, k -> paletteIndex.size());
            if (pi > Short.MAX_VALUE) {
                continue;
            }
            blockIndices[i] = (short) pi;
        }
        List<String> palette = new ArrayList<>(paletteIndex.keySet());

        short[] minMax = ChunkData.computeMinMax(heights);
        ChunkData data;
        if (includeBlockHist && !palette.isEmpty()) {
            data = new ChunkData(chunkX, chunkZ, step, n, minMax[0], minMax[1],
                    heights, biomeMatrix, palette, blockIndices);
        } else {
            data = new ChunkData(chunkX, chunkZ, step, n, minMax[0], minMax[1],
                    heights, biomeMatrix, List.of(), null);
        }

        String structuresJson = readStructures(chunk, warnings);
        String blockEntitiesJson = readBlockEntities(chunk, warnings);
        JsonObject paletteJson = new JsonObject();
        JsonArray paletteArray = new JsonArray();
        palette.forEach(paletteArray::add);
        paletteJson.add("palette", paletteArray);
        JsonArray indexArray = new JsonArray();
        for (short v : blockIndices) {
            indexArray.add(v);
        }
        paletteJson.add("indices", indexArray);

        return new Result(new Capture(data, structuresJson, blockEntitiesJson,
                Json.write(paletteJson), warnings), "");
    }

    /* ==================== 单项读取 ==================== */

    private static Object fetchChunk(Object level, int chunkX, int chunkZ) {
        Object chunk = McReflect.call(level, new String[]{"getChunk", "getChunkAt"}, chunkX, chunkZ);
        if (chunk != null) {
            return chunk;
        }
        chunk = McReflect.call(level, new String[]{"getChunk"}, chunkX, chunkZ, true);
        if (chunk != null) {
            return chunk;
        }
        return McReflect.call(level, new String[]{"getChunk"}, chunkX, chunkZ, false);
    }

    private static Integer readHeight(Object chunk, Object level, int lx, int lz,
                                      int worldX, int worldZ) {
        Object value = null;
        if (worldSurface != null) {
            value = McReflect.call(chunk, new String[]{"getHeight"}, worldSurface, lx, lz);
            if (value == null) {
                value = McReflect.call(level, new String[]{"getHeight"}, worldSurface, worldX, worldZ);
            }
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    private static Object readBiomeId(Object level, Object pos, Map<Object, Integer> cache) {
        Object holder = McReflect.call(level, new String[]{"getBiome"}, pos);
        if (holder == null) {
            return null;
        }
        synchronized (LOCK) {
            if (cache.containsKey(holder)) {
                Integer cached = cache.get(holder);
                return cached == null ? null : cached;
            }
        }
        Integer id = null;
        Object biome = McReflect.call(holder, new String[]{"value"});
        if (biome != null && biomeRegistry != null) {
            Object raw = McReflect.call(biomeRegistry, new String[]{"getId", "getIdOrThrow"}, biome);
            if (raw instanceof Number num) {
                id = num.intValue();
            }
        }
        synchronized (LOCK) {
            cache.put(holder, id);
        }
        return id;
    }

    private static String readBlockId(Object chunk, Object level, Object pos,
                                      Map<Object, String> cache) {
        Object state = McReflect.call(chunk, new String[]{"getBlockState"}, pos);
        if (state == null) {
            state = McReflect.call(level, new String[]{"getBlockState"}, pos);
        }
        if (state == null) {
            return null;
        }
        Object block = McReflect.call(state, new String[]{"getBlock"});
        if (block == null) {
            return null;
        }
        synchronized (LOCK) {
            if (cache.containsKey(block)) {
                return cache.get(block);
            }
        }
        String id = registryId(blockRegistry, block);
        synchronized (LOCK) {
            cache.put(block, id);
        }
        return id;
    }

    private static String registryId(Object registry, Object entry) {
        if (registry == null || entry == null) {
            return null;
        }
        Object key = McReflect.call(registry, new String[]{"getKey", "getResourceKey"}, entry);
        String id = resourceKeyId(key);
        if (id != null) {
            return id;
        }
        Object holder = McReflect.call(entry, new String[]{"builtInRegistryHolder"});
        if (holder != null) {
            Object holderKey = McReflect.call(holder, new String[]{"key", "unwrapKey"});
            id = resourceKeyId(holderKey);
            if (id != null) {
                return id;
            }
        }
        String fallback = String.valueOf(entry);
        return fallback.isBlank() || "null".equals(fallback) ? null : fallback;
    }

    private static String resourceKeyId(Object keyLike) {
        if (keyLike == null) {
            return null;
        }
        if (keyLike instanceof java.util.Optional<?> optional) {
            if (optional.isEmpty()) {
                return null;
            }
            return resourceKeyId(optional.get());
        }
        Object location = McReflect.call(keyLike, new String[]{"location", "registry", "getPath"});
        if (location instanceof String s) {
            return s;
        }
        if (location != null) {
            return String.valueOf(location);
        }
        return null;
    }

    private static String readStructures(Object chunk, List<String> warnings) {
        Object starts = McReflect.call(chunk, new String[]{"getAllStarts", "getStructureStarts"});
        JsonArray array = new JsonArray();
        if (starts instanceof Map<?, ?> map) {
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_STRUCTURES) {
                    break;
                }
                String structureId = registryId(structureRegistry, entry.getKey());
                if (structureId == null) {
                    continue;
                }
                Object bbox = McReflect.call(entry.getValue(), new String[]{"getBoundingBox"});
                JsonObject o = new JsonObject();
                o.addProperty("id", structureId);
                int[] coords = readBoundingBox(bbox);
                if (coords == null) {
                    continue;
                }
                o.addProperty("minX", coords[0]);
                o.addProperty("minY", coords[1]);
                o.addProperty("minZ", coords[2]);
                o.addProperty("maxX", coords[3]);
                o.addProperty("maxY", coords[4]);
                o.addProperty("maxZ", coords[5]);
                array.add(o);
            }
        } else if (starts != null) {
            warnings.add("结构数据接口返回类型未知: " + starts.getClass().getSimpleName());
        }
        return Json.write(array);
    }

    private static int[] readBoundingBox(Object bbox) {
        if (bbox == null) {
            return null;
        }
        String[] methods = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        String[] fields = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            Object value = McReflect.call(bbox, new String[]{methods[i]});
            if (value == null) {
                value = McReflect.call(bbox, new String[]{"get" + Character.toUpperCase(methods[i].charAt(0))
                        + methods[i].substring(1)});
            }
            if (value == null) {
                value = McReflect.field(bbox, fields[i]);
            }
            if (!(value instanceof Number num)) {
                return null;
            }
            out[i] = num.intValue();
        }
        return out;
    }

    private static String readBlockEntities(Object chunk, List<String> warnings) {
        Object entities = McReflect.call(chunk, new String[]{"getBlockEntities", "getBlockEntityMap"});
        JsonArray array = new JsonArray();
        if (entities instanceof Map<?, ?> map) {
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_BLOCK_ENTITIES) {
                    break;
                }
                Object be = entry.getValue();
                if (be == null) {
                    continue;
                }
                Object type = McReflect.call(be, new String[]{"getType"});
                String typeId = registryId(blockEntityTypeRegistry, type);
                JsonObject o = new JsonObject();
                o.addProperty("type", typeId == null ? "unknown" : typeId);
                Object pos = entry.getKey();
                Object x = McReflect.call(pos, new String[]{"getX"});
                Object y = McReflect.call(pos, new String[]{"getY"});
                Object z = McReflect.call(pos, new String[]{"getZ"});
                if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                    o.addProperty("x", nx.intValue());
                    o.addProperty("y", ny.intValue());
                    o.addProperty("z", nz.intValue());
                    array.add(o);
                }
            }
        } else if (entities != null) {
            warnings.add("block entity 接口返回类型未知: " + entities.getClass().getSimpleName());
        }
        return Json.write(array);
    }

    /* ==================== 类型解析 ==================== */

    private static void resolveTypes() {
        synchronized (LOCK) {
            if (resolved) {
                return;
            }
            resolved = true;
            blockPosClass = McReflect.cls("net.minecraft.core.BlockPos");
            heightmapTypesClass = McReflect.cls("net.minecraft.world.level.levelgen.Heightmap$Types",
                    "net.minecraft.world.level.levelgen.Heightmap$Type");
            if (heightmapTypesClass != null) {
                worldSurface = McReflect.constant(heightmapTypesClass, "WORLD_SURFACE", "WORLD_SURFACE_WG");
            }
            Class<?> registries = McReflect.cls("net.minecraft.core.registries.BuiltInRegistries");
            if (registries != null) {
                blockRegistry = McReflect.staticField(registries, "BLOCK");
                biomeRegistry = McReflect.staticField(registries, "BIOME");
                structureRegistry = McReflect.staticField(registries, "STRUCTURE");
                blockEntityTypeRegistry = McReflect.staticField(registries, "BLOCK_ENTITY_TYPE");
            }
            if (worldSurface == null) {
                top.xuanjian.map.core.util.GisLog.warn("capture",
                        "heightmap Types.WORLD_SURFACE 解析失败，区块采集将跳过");
            }
        }
    }

    /** debug：把关键类的方法签名拼成一段日志。 */
    public static String describeApi() {
        resolveTypes();
        StringBuilder sb = new StringBuilder();
        sb.append(McReflect.describe(blockPosClass, 6)).append('\n');
        sb.append(McReflect.describe(heightmapTypesClass, 6)).append('\n');
        sb.append(McReflect.describe(blockRegistry == null ? null : blockRegistry.getClass(), 12)).append('\n');
        return sb.toString();
    }
}
