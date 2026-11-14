package top.xuanjian.map.core.chunk;

import top.xuanjian.map.core.codec.ByteReader;
import top.xuanjian.map.core.codec.ByteWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 单区块段（body）编解码，严格对齐 GIS 契约：
 *
 * <pre>
 * chunkX i32, chunkZ i32, sampleStep u8, sampleSize u8, minY i16, maxY i16,
 * flags u8(bit0 高度矩阵 / bit1 biome / bit2 blockHist),
 * heightMatrix i16[n*n]（-32768 = 无数据，保留缺口）,
 * [biomeMatrix u16[n*n]]（0xFFFF = 未知）,
 * [blockHist]（布局待服务端确认，默认关闭）
 * </pre>
 */
public final class ChunkBodyCodec {

    /** blockHist 布局（本客户端提案，默认关闭；见 docs/api-contract.md 待确认项 2）。 */
    private static final int BLOCK_HIST_LAYOUT_VERSION = 1;

    private ChunkBodyCodec() {
    }

    public static byte[] encodeBody(ChunkData data) {
        ByteWriter w = new ByteWriter(64 + data.cells() * 4);
        w.i32(data.chunkX());
        w.i32(data.chunkZ());
        w.u8(data.sampleStep());
        w.u8(data.sampleSize());
        w.i16(data.minY());
        w.i16(data.maxY());
        w.u8(data.flags());
        for (short h : data.heights()) {
            w.i16(h);
        }
        if (data.hasBiomes()) {
            for (int b : data.biomes()) {
                w.u16(b < 0 ? ChunkData.UNKNOWN_BIOME : (b & 0xFFFF));
            }
        }
        if (data.hasBlockIndices()) {
            // 提案布局：u8 layoutVersion, u8 paletteCount, [u8 len + UTF-8 id], u16[n*n] 下标（0xFFFF=未知）
            List<String> palette = data.blockPalette();
            if (palette.size() > 255) {
                throw new IllegalArgumentException("block palette 超过 255 项");
            }
            w.u8(BLOCK_HIST_LAYOUT_VERSION);
            w.u8(palette.size());
            for (String id : palette) {
                byte[] utf8 = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (utf8.length > 255) {
                    throw new IllegalArgumentException("block id 过长: " + id);
                }
                w.u8(utf8.length);
                w.bytes(utf8);
            }
            for (short idx : data.blockIndices()) {
                w.u16(idx < 0 ? 0xFFFF : (idx & 0xFFFF));
            }
        }
        return w.toByteArray();
    }

    /** 从批次流里读出一个 chunk 段（reader 位置会前进）。 */
    public static ChunkData decodeBody(ByteReader r) {
        int chunkX = r.i32();
        int chunkZ = r.i32();
        int sampleStep = r.u8();
        int sampleSize = r.u8();
        short minY = r.i16();
        short maxY = r.i16();
        int flags = r.u8();
        int n = sampleSize * sampleSize;
        short[] heights = new short[n];
        for (int i = 0; i < n; i++) {
            heights[i] = r.i16();
        }
        int[] biomes = null;
        if ((flags & ChunkData.FLAG_BIOMES) != 0) {
            biomes = new int[n];
            for (int i = 0; i < n; i++) {
                int v = r.u16();
                biomes[i] = v == ChunkData.UNKNOWN_BIOME ? -1 : v;
            }
        }
        List<String> palette = new ArrayList<>();
        short[] blockIndices = null;
        if ((flags & ChunkData.FLAG_BLOCK_HIST) != 0) {
            int layout = r.u8();
            if (layout != BLOCK_HIST_LAYOUT_VERSION) {
                throw new IllegalArgumentException("未知 blockHist 布局版本: " + layout);
            }
            int paletteCount = r.u8();
            for (int i = 0; i < paletteCount; i++) {
                int len = r.u8();
                palette.add(r.utf8(len));
            }
            blockIndices = new short[n];
            for (int i = 0; i < n; i++) {
                int v = r.u16();
                blockIndices[i] = (short) (v == 0xFFFF ? -1 : v);
            }
        }
        return new ChunkData(chunkX, chunkZ, sampleStep, sampleSize, minY, maxY,
                heights, biomes, palette, blockIndices);
    }

    public static ChunkData decodeBody(byte[] body) {
        ByteReader r = new ByteReader(body);
        ChunkData data = decodeBody(r);
        if (r.hasRemaining()) {
            throw new IllegalArgumentException("chunk body 尾部有多余字节: " + r.remaining());
        }
        return data;
    }

    /** 段字节长度（用于分批时估算 4MiB/8MiB 上限，不再重复编码）。 */
    public static int bodySize(ChunkData data) {
        int size = 4 + 4 + 1 + 1 + 2 + 2 + 1 + data.cells() * 2;
        if (data.hasBiomes()) {
            size += data.cells() * 2;
        }
        if (data.hasBlockIndices()) {
            size += 1 + 1 + data.cells() * 2;
            for (String id : data.blockPalette()) {
                size += 1 + id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        return size;
    }
}
