package top.xuanjian.map.core.compress;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib/deflate 编解码（HTTP {@code Content-Encoding: deflate}，RFC 9110 定义为 zlib 封装）。
 *
 * <p>等级 9 + 直接内存复用，避免每次上传都重新分配 Deflater。
 */
public final class DeflateCodec implements BlobCodec {

    public static final DeflateCodec INSTANCE = new DeflateCodec();

    @Override
    public String contentEncoding() {
        return "deflate";
    }

    @Override
    public byte[] compress(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, false);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                if (n <= 0) {
                    break;
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @Override
    public byte[] decompress(byte[] compressed) {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 3));
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("deflate 数据损坏: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }
}
