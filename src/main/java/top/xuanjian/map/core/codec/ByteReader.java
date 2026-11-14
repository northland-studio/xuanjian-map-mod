package top.xuanjian.map.core.codec;

import java.nio.charset.StandardCharsets;

/** 小端（LE）二进制读取器，越界即抛异常（坏包必须被明确拒绝，不能读出脏数据）。 */
public final class ByteReader {

    private final byte[] data;
    private int pos;

    public ByteReader(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return data.length - pos;
    }

    public boolean hasRemaining() {
        return pos < data.length;
    }

    private void need(int n) {
        if (pos + n > data.length) {
            throw new IllegalArgumentException("二进制数据不足：需要 " + n + " 字节，剩余 " + remaining());
        }
    }

    public int u8() {
        need(1);
        return data[pos++] & 0xFF;
    }

    public int u16() {
        need(2);
        int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public short i16() {
        return (short) u16();
    }

    public int i32() {
        need(4);
        int v = (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long u32() {
        return i32() & 0xFFFFFFFFL;
    }

    public long i64() {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (data[pos + i] & 0xFF)) << (8 * i);
        }
        pos += 8;
        return v;
    }

    public byte[] bytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    public String ascii(int n) {
        return new String(bytes(n), StandardCharsets.US_ASCII);
    }

    public String utf8(int n) {
        return new String(bytes(n), StandardCharsets.UTF_8);
    }
}
