package top.xuanjian.map.core.compress;

/**
 * 压缩编解码器。
 *
 * <p>契约允许 {@code Content-Encoding: zstd|gzip|deflate|identity}，推荐 zstd。
 * 客户端默认用 <b>deflate</b>（zlib，RFC 1950/1951）：JDK 原生实现、零 native 依赖、
 * 跨平台稳定；压缩后体积对区块高度矩阵足够小。zstd 需要 native 库，会把 CI/玩家环境的
 * 可靠性押在 native 解压上，因此首版不用，接口保留后续可替换（contract 已允许 deflate）。
 */
public interface BlobCodec {

    /** HTTP Content-Encoding 值。 */
    String contentEncoding();

    /** 压缩。 */
    byte[] compress(byte[] raw);

    /** 解压。 */
    byte[] decompress(byte[] compressed);
}
