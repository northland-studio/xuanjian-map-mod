package top.xuanjian.map.client.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import top.xuanjian.map.client.compat.McReflect;
import top.xuanjian.map.core.chunk.BlockHash;
import top.xuanjian.map.core.chunk.ChunkData;
import top.xuanjian.map.core.net.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 区块采样器（跨版本反射实现，26.2 / 1.21.11 共用）。
 *
 * <p>只采集 Minecraft <b>原生</b>数据：高度矩阵、biome、表面方块直方图、结构、block entity 类型。
 * 规范明确禁止自动推断玩家建筑/道路/城区/商店 —— 本类不做任何推断。
 *
 * <p>缺数据（未加载/接口不可用）一律写 {@link ChunkData#NO_DATA}，绝不填 0。
 * 契约约束：sampleStep 1..4，sampleSize = 16/sampleStep。
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
    private static final int BLOCK_HIST_TOP_N = 32;

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

    /** 契约：sampleStep 1..4 且必须整除 16。 */
    public static int normalizeStep(int step) {
        if (step <= 1) {
            return 1;
        }
        if (step >= 4) {
            return 4;
        }
        return step <= 2 ? 2 : 4;
    }

    /**
     * 采样一个区块。
     *
     * @param includeSurfaceBlocks v2：是否采集每列地表方块 hash16（surfaceBlockMatrix）
     */
    public static Result sample(Object level, int chunkX, int chunkZ, int sampleStep,
                                boolean includeBlockHist, boolean includeSurfaceBlocks) {
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
        int[] surfaceBlocks = includeSurfaceBlocks ? new int[n * n] : null;
        java.util.Arrays.fill(heights, ChunkData.NO_DATA);
        java.util.Arrays.fill(biomes, -1);
        if (surfaceBlocks != null) {
            java.util.Arrays.fill(surfaceBlocks, BlockHash.NO_SURFACE);
        }
        boolean anyHeight = false;
        boolean anyBiome = false;

        Map<Object, String> blockIdCache = new java.util.IdentityHashMap<>();
        Map<Object, Integer> blockNumericCache = new java.util.IdentityHashMap<>();
        Map<Object, Integer> biomeIdCache = new java.util.IdentityHashMap<>();
        Map<String, Integer> hashCache = new java.util.HashMap<>();
        Map<Integer, String> blockNames = new LinkedHashMap<>();
        Map<Integer, Long> histogram = new LinkedHashMap<>();

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

                Object pos = McReflect.construct(blockPosClass, worldX, heights[idx] - 1, worldZ);
                if (pos == null) {
                    warnings.add("BlockPos 构造失败");
                    continue;
                }

                Object biomeId = readBiomeId(level, pos, biomeIdCache);
                if (biomeId instanceof Integer id) {
                    biomes[idx] = id;
                    anyBiome = true;
                }

                Object block = readBlock(chunk, level, pos);
                if (block != null) {
                    Integer numeric = registryNumericId(blockRegistry, block, blockNumericCache);
                    if (numeric != null) {
                        histogram.merge(numeric, 1L, Long::sum);
                        blockNames.putIfAbsent(numeric, registryId(blockRegistry, block, blockIdCache));
                    }
                }
                if (surfaceBlocks != null) {
                    // v2：该列「顶层非空气方块」；水面记录水方块本身；无数据/空列 = 0xFFFF
                    int surfaceY = heights[idx] - 1;
                    String surfaceName = block == null ? null
                            : registryId(blockRegistry, block, blockIdCache);
                    // 若顶层是空气（heightmap 语义差异），向下最多探 8 格找顶层非空气方块
                    for (int probe = 1; isAirBlockName(surfaceName) && probe <= 8; probe++) {
                        Object probePos = McReflect.construct(blockPosClass, worldX, surfaceY - probe, worldZ);
                        if (probePos == null) {
                            break;
                        }
                        Object candidate = readBlock(chunk, level, probePos);
                        if (candidate == null) {
                            continue;
                        }
                        surfaceName = registryId(blockRegistry, candidate, blockIdCache);
                    }
                    // hash 按资源名缓存：同一区块内每种方块只算一次 CRC32（每列仅一次 Map 查表）
                    surfaceBlocks[idx] = hashCache.computeIfAbsent(
                            surfaceName == null ? "" : surfaceName,
                            name -> isAirBlockName(name) ? BlockHash.NO_SURFACE : BlockHash.hash16(name));
                }
            }
        }

        if (!anyHeight) {
            return Result.fail("高度矩阵全部不可用（heightmap API 不匹配），本次跳过该区块");
        }
        int[] biomeMatrix = anyBiome ? biomes : null;

        List<ChunkData.BlockCount> blockHist = new ArrayList<>();
        if (includeBlockHist && !histogram.isEmpty()) {
            histogram.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue).reversed()
                            .thenComparingInt(Map.Entry::getKey))
                    .limit(BLOCK_HIST_TOP_N)
                    .forEach(e -> blockHist.add(new ChunkData.BlockCount(e.getKey(), e.getValue())));
        }

        short[] minMax = ChunkData.computeMinMax(heights);
        ChunkData data = new ChunkData(chunkX, chunkZ, step, n, minMax[0], minMax[1],
                heights, biomeMatrix, blockHist, surfaceBlocks);

        String structuresJson = readStructures(chunk, warnings);
        String blockEntitiesJson = readBlockEntities(chunk, warnings);

        JsonArray palette = new JsonArray();
        blockNames.forEach((id, name) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("name", name);
            palette.add(entry);
        });
        return new Result(new Capture(data, structuresJson, blockEntitiesJson, Json.write(palette),
                warnings), "");
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
        return value instanceof Number num ? num.intValue() : null;
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

    private static Object readBlock(Object chunk, Object level, Object pos) {
        Object state = McReflect.call(chunk, new String[]{"getBlockState"}, pos);
        if (state == null) {
            state = McReflect.call(level, new String[]{"getBlockState"}, pos);
        }
        return state == null ? null : McReflect.call(state, new String[]{"getBlock"});
    }

    /** 空气类方块（含 cave_air / void_air）不算地表。 */
    private static boolean isAirBlockName(String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return true;
        }
        String name = blockName.trim();
        return name.endsWith(":air") || name.endsWith(":cave_air") || name.endsWith(":void_air");
    }
    private static Integer registryNumericId(Object registry, Object entry,
                                             Map<Object, Integer> cache) {
        if (registry == null || entry == null) {
            return null;
        }
        synchronized (LOCK) {
            if (cache.containsKey(entry)) {
                return cache.get(entry);
            }
        }
        Integer id = null;
        Object raw = McReflect.call(registry, new String[]{"getId", "getIdOrThrow"}, entry);
        if (raw instanceof Number num) {
            id = num.intValue();
        }
        synchronized (LOCK) {
            cache.put(entry, id);
        }
        return id;
    }

    private static String registryId(Object registry, Object entry, Map<Object, String> cache) {
        if (registry == null || entry == null) {
            return null;
        }
        synchronized (LOCK) {
            if (cache.containsKey(entry)) {
                return cache.get(entry);
            }
        }
        String id = null;
        Object key = McReflect.call(registry, new String[]{"getKey", "getResourceKey"}, entry);
        id = resourceKeyId(key);
        if (id == null) {
            Object holder = McReflect.call(entry, new String[]{"builtInRegistryHolder"});
            if (holder != null) {
                id = resourceKeyId(McReflect.call(holder, new String[]{"key", "unwrapKey"}));
            }
        }
        if (id == null) {
            String fallback = String.valueOf(entry);
            id = fallback.isBlank() || "null".equals(fallback) ? null : fallback;
        }
        synchronized (LOCK) {
            cache.put(entry, id);
        }
        return id;
    }

    private static String resourceKeyId(Object keyLike) {
        if (keyLike == null) {
            return null;
        }
        if (keyLike instanceof java.util.Optional<?> optional) {
            return optional.isEmpty() ? null : resourceKeyId(optional.get());
        }
        Object location = McReflect.call(keyLike, new String[]{"location", "registry", "getPath"});
        if (location instanceof String s) {
            return s;
        }
        return location == null ? null : String.valueOf(location);
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
                String structureId = registryId(structureRegistry, entry.getKey(),
                        new java.util.IdentityHashMap<>());
                if (structureId == null) {
                    continue;
                }
                Object bbox = McReflect.call(entry.getValue(), new String[]{"getBoundingBox"});
                int[] coords = readBoundingBox(bbox);
                if (coords == null) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("id", structureId);
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
        String[] names = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            Object value = McReflect.call(bbox, new String[]{names[i],
                    "get" + Character.toUpperCase(names[i].charAt(0)) + names[i].substring(1)});
            if (value == null) {
                value = McReflect.field(bbox, names[i]);
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
            Map<Object, String> cache = new java.util.IdentityHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_BLOCK_ENTITIES) {
                    break;
                }
                Object be = entry.getValue();
                if (be == null) {
                    continue;
                }
                String typeId = registryId(blockEntityTypeRegistry,
                        McReflect.call(be, new String[]{"getType"}), cache);
                Object pos = entry.getKey();
                Object x = McReflect.call(pos, new String[]{"getX"});
                Object y = McReflect.call(pos, new String[]{"getY"});
                Object z = McReflect.call(pos, new String[]{"getZ"});
                if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", typeId == null ? "unknown" : typeId);
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
