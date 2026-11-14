package top.xuanjian.map.core.codec;

import java.util.Arrays;

/**
 * 小端（LE）二进制写入器 —— 契约明确要求 LE。
 *
 * <p>不用 {@link java.nio.ByteBuffer} 的默认大端，避免“看起来一样、线上全错”的字节序事故。
 */
public final class ByteWriter {

    private byte[] buf;
    private int size;

    public ByteWriter() {
        this(64);
    }

    public ByteWriter(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    public int size() {
        return size;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }

    private void ensure(int extra) {
        if (size + extra <= buf.length) {
            return;
        }
        int cap = buf.length;
        while (cap < size + extra) {
            cap <<= 1;
        }
        buf = Arrays.copyOf(buf, cap);
    }

    public ByteWriter u8(int v) {
        ensure(1);
        buf[size++] = (byte) (v & 0xFF);
        return this;
    }

    public ByteWriter u16(int v) {
        ensure(2);
        buf[size++] = (byte) (v & 0xFF);
        buf[size++] = (byte) ((v >>> 8) & 0xFF);
        return this;
    }

    public ByteWriter i16(int v) {
        return u16(v & 0xFFFF);
    }

    public ByteWriter i32(int v) {
        ensure(4);
        buf[size++] = (byte) (v & 0xFF);
        buf[size++] = (byte) ((v >>> 8) & 0xFF);
        buf[size++] = (byte) ((v >>> 16) & 0xFF);
        buf[size++] = (byte) ((v >>> 24) & 0xFF);
        return this;
    }

    public ByteWriter u32(long v) {
        return i32((int) (v & 0xFFFFFFFFL));
    }

    public ByteWriter i64(long v) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buf[size++] = (byte) ((v >>> (8 * i)) & 0xFF);
        }
        return this;
    }

    public ByteWriter bytes(byte[] data) {
        ensure(data.length);
        System.arraycopy(data, 0, buf, size, data.length);
        size += data.length;
        return this;
    }

    public ByteWriter ascii(String text) {
        return bytes(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
