package top.xuanjian.map.core.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** gzip 编解码（契约允许 {@code Content-Encoding: gzip}，作为 deflate 的备选）。 */
public final class GzipCodec implements BlobCodec {

    public static final GzipCodec INSTANCE = new GzipCodec();

    @Override
    public String contentEncoding() {
        return "gzip";
    }

    @Override
    public byte[] compress(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(raw);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("gzip 压缩失败", e);
        }
    }

    @Override
    public byte[] decompress(byte[] compressed) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("gzip 数据损坏: " + e.getMessage(), e);
        }
    }
}
