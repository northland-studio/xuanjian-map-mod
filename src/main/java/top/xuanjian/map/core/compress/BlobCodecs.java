package top.xuanjian.map.core.compress;

import top.xuanjian.map.core.config.GisConfig;
import top.xuanjian.map.core.util.GisLog;

/**
 * 压缩方案注册表：契约允许 {@code zstd|gzip|deflate|identity}，默认 <b>zstd</b>。
 *
 * <p>zstd 用纯 Java 实现（aircompressor），不经 native，CI 与玩家环境都稳定；
 * 若某些环境 zstd 初始化失败，自动降级 deflate（同为契约允许的编码）。
 */
public final class BlobCodecs {

    private static final String TAG = "compress";
    private static volatile BlobCodec zstd = ZstdCodec.INSTANCE;

    private BlobCodecs() {
    }

    /** 按配置返回上传编码（未知/不可用则回退）。 */
    public static BlobCodec forUpload(GisConfig config) {
        String encoding = config == null ? "zstd" : config.contentEncoding();
        return byEncoding(encoding);
    }

    public static BlobCodec byEncoding(String encoding) {
        if (encoding == null) {
            return zstdOrFallback();
        }
        return switch (encoding.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "zstd" -> zstdOrFallback();
            case "gzip" -> GzipCodec.INSTANCE;
            case "identity" -> IdentityCodec.INSTANCE;
            default -> DeflateCodec.INSTANCE;
        };
    }

    public static boolean zstdAvailable() {
        return zstd != null;
    }

    private static BlobCodec zstdOrFallback() {
        BlobCodec codec = zstd;
        if (codec != null) {
            return codec;
        }
        return DeflateCodec.INSTANCE;
    }

    /** 启动自检：真正压/解一次，失败则降级 deflate 并记录。 */
    public static void verifyAtStartup() {
        try {
            BlobCodec codec = ZstdCodec.INSTANCE;
            byte[] probe = "xuanjian-gis-zstd-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] compressed = codec.compress(probe);
            byte[] back = codec.decompress(compressed);
            if (!java.util.Arrays.equals(probe, back)) {
                throw new IllegalStateException("zstd 自检数据不一致");
            }
            zstd = codec;
            GisLog.info(TAG, "压缩方案：zstd（纯 Java 实现）可用");
        } catch (Throwable t) {
            zstd = null;
            GisLog.warn(TAG, "zstd 不可用，自动降级 deflate: " + t, t);
        }
    }
}
