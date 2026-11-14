package top.xuanjian.map.core.compress;

import io.airlift.compress.MalformedInputException;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;

import java.util.Arrays;

/**
 * Zstd 编解码（契约推荐 {@code Content-Encoding: zstd}）。
 *
 * <p>用纯 Java 的 aircompressor 实现（随模组 jar-in-jar 打包），
 * 不引入 native 库：玩家和 CI 环境都不需要额外系统依赖，避免 native 解压失败导致上传不可用。
 * 压缩率与 zstd 官方实现接近，足以处理区块高度/biome/blockHist 这类结构化小数据。
 */
public final class ZstdCodec implements BlobCodec {

    public static final ZstdCodec INSTANCE = new ZstdCodec();

    private final ZstdCompressor compressor = new ZstdCompressor();
    private final ZstdDecompressor decompressor = new ZstdDecompressor();

    @Override
    public String contentEncoding() {
        return "zstd";
    }

    @Override
    public byte[] compress(byte[] raw) {
        byte[] output = new byte[compressor.maxCompressedLength(raw.length)];
        int size = compressor.compress(raw, 0, raw.length, output, 0, output.length);
        return Arrays.copyOf(output, size);
    }

    @Override
    public byte[] decompress(byte[] compressed) {
        long known = ZstdDecompressor.getDecompressedSize(compressed, 0, compressed.length);
        if (known > 0 && known <= Integer.MAX_VALUE) {
            byte[] output = new byte[(int) known];
            int size = decompressor.decompress(compressed, 0, compressed.length, output, 0, output.length);
            return Arrays.copyOf(output, size);
        }
        // 帧里没有内容大小时，逐步放大缓冲区
        int capacity = Math.max(1024, compressed.length * 4);
        while (capacity <= 64 * 1024 * 1024) {
            try {
                byte[] output = new byte[capacity];
                int size = decompressor.decompress(compressed, 0, compressed.length, output, 0, output.length);
                return Arrays.copyOf(output, size);
            } catch (MalformedInputException e) {
                capacity *= 4;
            }
        }
        throw new IllegalArgumentException("zstd 解压失败（缓冲区上限内仍无法解出）");
    }
}
