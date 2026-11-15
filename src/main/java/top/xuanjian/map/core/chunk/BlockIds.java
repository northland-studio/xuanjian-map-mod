package top.xuanjian.map.core.chunk;

/**
 * 方块/结构等注册表 ID 的规范化。
 *
 * <p>v2 契约要求 hash16 使用<b>完整命名空间资源名</b>（如 {@code minecraft:grass_block}）。
 * 但不同 MC 版本的反射结果不一致：
 * <ul>
 *   <li>{@code ResourceKey.toString()} → {@code ResourceKey[minecraft:block / minecraft:grass_block]}；</li>
 *   <li>{@code ResourceLocation.toString()} → {@code minecraft:grass_block}；</li>
 *   <li>{@code ResourceLocation.getPath()} → {@code grass_block}（<b>丢失命名空间，禁止直接使用</b>）；</li>
 * </ul>
 * 历史 bug：1.21 分支取到的是 {@code getPath()}，上传的 hash 变成了
 * {@code CRC32("dirt")} 而不是 {@code CRC32("minecraft:dirt")}，导致服务端 palette 对不上
 * （线上 57782=CRC32("dirt")=5338? 实际 57782=CRC32("dirt")&0xFFFF，5338=CRC32("water")）。
 * 这里统一做规范化，并对纯 path 补默认命名空间，保证跨版本、跨分支 hash 一致。
 */
public final class BlockIds {

    public static final String DEFAULT_NAMESPACE = "minecraft";

    private BlockIds() {
    }

    /**
     * 把反射拿到的 key 形态规范成 {@code namespace:path}。
     *
     * @param raw 可能是 ResourceLocation.toString()、ResourceKey.toString()、纯 path 或 null
     * @return 规范化后的资源名；无法识别返回 null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "empty".equalsIgnoreCase(text)) {
            return null;
        }
        // ResourceKey[minecraft:block / minecraft:grass_block]
        if (text.startsWith("ResourceKey[") || text.contains(" / ")) {
            int slash = text.lastIndexOf(" / ");
            if (slash >= 0) {
                text = text.substring(slash + 3);
            }
            if (text.endsWith("]")) {
                text = text.substring(0, text.length() - 1);
            }
            text = text.trim();
        }
        if (text.isEmpty()) {
            return null;
        }
        // 带命名空间：直接用（只保留 namespace:path 形态）
        int colon = text.indexOf(':');
        if (colon > 0) {
            String namespace = text.substring(0, colon).trim();
            String path = text.substring(colon + 1).trim();
            if (path.isEmpty()) {
                return null;
            }
            // 去掉可能残留的空格/括号
            while (path.endsWith("]")) {
                path = path.substring(0, path.length() - 1).trim();
            }
            if (namespace.isEmpty() || namespace.contains(" ") || path.isEmpty()) {
                return null;
            }
            return namespace + ":" + path;
        }
        // 纯 path（例如 ResourceLocation.getPath()）→ 补 minecraft 命名空间
        if (text.contains(" ") || text.contains("[") || text.contains("]")) {
            return null;
        }
        return DEFAULT_NAMESPACE + ":" + text;
    }
}
