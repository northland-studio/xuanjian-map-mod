package top.xuanjian.map.core.compress;

/** 不压缩（契约允许 identity；只在测试与排查时用，生产默认 deflate）。 */
public final class IdentityCodec implements BlobCodec {

    public static final IdentityCodec INSTANCE = new IdentityCodec();

    @Override
    public String contentEncoding() {
        return "identity";
    }

    @Override
    public byte[] compress(byte[] raw) {
        return raw.clone();
    }

    @Override
    public byte[] decompress(byte[] compressed) {
        return compressed.clone();
    }
}
