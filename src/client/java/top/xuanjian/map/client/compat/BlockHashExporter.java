package top.xuanjian.map.client.compat;

import top.xuanjian.map.core.chunk.BlockHash;
import top.xuanjian.map.core.net.Json;
import top.xuanjian.map.core.util.GisLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次性导出「全部方块资源名 → hash16」清单（服务端 palette 补色用）。
 *
 * <p>{@code hash16 = CRC32(资源名 UTF-8) & 0xFFFF}，与 {@link BlockHash} 完全一致；
 * 遍历客户端注册表 {@code BuiltInRegistries.BLOCK} 的全部方块（反射，跨 26/1.21 兼容）。
 * 输出 JSON 以资源名排序，便于跨分支 diff 与跨端对账。
 */
public final class BlockHashExporter {

    private static final String TAG = "block-hash-export";

    private BlockHashExporter() {
    }

    /** 收集全部方块 hash（TreeMap 保证稳定顺序）。 */
    public static Map<String, Integer> collect() {
        TreeMap<String, Integer> out = new TreeMap<>();
        Object registry = blockRegistry();
        if (registry == null) {
            GisLog.warn(TAG, "找不到 BuiltInRegistries.BLOCK，无法导出方块 hash 清单");
            return out;
        }
        int scanned = 0;
        Object iterator = McReflect.call(registry, new String[]{"iterator"});
        if (iterator instanceof java.util.Iterator<?> it) {
            while (it.hasNext()) {
                Object block = it.next();
                scanned++;
                String name = resolveName(registry, block);
                if (name == null || name.isBlank()) {
                    continue;
                }
                out.put(name, BlockHash.hash16(name));
            }
        }
        GisLog.info(TAG, "方块注册表扫描完成: 扫描 " + scanned + " 个，导出 " + out.size() + " 个 hash16");
        return out;
    }

    /** 导出 JSON 到指定文件，返回方块数量。 */
    public static int exportJson(Path path) throws IOException {
        Map<String, Integer> map = collect();
        String json = Json.write(new LinkedHashMap<>(map));
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, json, StandardCharsets.UTF_8);
        GisLog.info(TAG, "已写出方块 hash 清单: " + path.toAbsolutePath()
                + "（" + map.size() + " 个方块，" + json.length() + " 字节）");
        return map.size();
    }

    /** 依次尝试多个类名/字段名拿方块注册表。 */
    private static Object blockRegistry() {
        String[][] candidates = {
                {"net.minecraft.core.registries.BuiltInRegistries", "BLOCK"},
                {"net.minecraft.core.Registry", "BLOCK"},
                {"net.minecraft.registry.Registries", "BLOCK"},
        };
        for (String[] candidate : candidates) {
            Class<?> type = McReflect.cls(candidate[0]);
            if (type == null) {
                continue;
            }
            Object registry = McReflect.staticField(type, candidate[1]);
            if (registry != null) {
                return registry;
            }
        }
        return null;
    }

    /** 取方块的资源名（2 个注册表 API + 1 个描述 ID 兜底）。 */
    private static String resolveName(Object registry, Object block) {
        if (block == null) {
            return null;
        }
        Object key = McReflect.call(registry, new String[]{"getKey", "getResourceKey"}, block);
        if (key != null) {
            String text = key.toString();
            if (!text.isBlank() && text.contains(":")) {
                return text;
            }
        }
        Object descriptionId = McReflect.call(block, new String[]{"getDescriptionId"});
        if (descriptionId instanceof String id) {
            // block.minecraft.grass_block -> minecraft:grass_block
            String trimmed = id.startsWith("block.") ? id.substring("block.".length()) : id;
            int dot = trimmed.indexOf('.');
            if (dot > 0) {
                return trimmed.substring(0, dot) + ":" + trimmed.substring(dot + 1);
            }
        }
        return null;
    }
}
