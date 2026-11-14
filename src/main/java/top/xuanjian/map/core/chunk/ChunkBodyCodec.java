package top.xuanjian.map.core.chunk;

import top.xuanjian.map.core.codec.ByteReader;
import top.xuanjian.map.core.codec.ByteWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 单区块段（body）编解码，严格对齐生产契约 v2：
 *
 * <pre>
 * chunkX i32, chunkZ i32, sampleStep u8, sampleSize u8, minY i16, maxY i16,
 * flags u8(bit0 高度矩阵 / bit1 biome / bit2 blockHist / bit3 surfaceBlockMatrix),
 * heightMatrix i16[n*n]（-32768 = 无数据，保留缺口）,
 * [biomeMatrix u16[n*n]]（0xFFFF = 未知）,
 * [blockHist: u8 n + n*(u16 blockId, u32 count)],
 * [surfaceBlockMatrix u16[n*n]]（v2，地表方块 hash16，0xFFFF = 无数据/空列）
 * </pre>
 */
public final class ChunkBodyCodec {

    private ChunkBodyCodec() {
    }

    public static byte[] encodeBody(ChunkData data) {
        ByteWriter w = new ByteWriter(64 + data.cells() * 6);
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
        if (data.hasBlockHistogram()) {
            List<ChunkData.BlockCount> histogram = data.blockHistogram();
            int entries = Math.min(histogram.size(), ChunkData.MAX_BLOCK_HIST_ENTRIES);
            w.u8(entries);
            for (int i = 0; i < entries; i++) {
                ChunkData.BlockCount entry = histogram.get(i);
                w.u16(entry.blockId() & 0xFFFF);
                w.u32(entry.count());
            }
        }
        // v2：surfaceBlockMatrix 必须紧跟 blockHist 之后
        if (data.hasSurfaceBlocks()) {
            for (int hash : data.surfaceBlocks()) {
                w.u16(hash & 0xFFFF);
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
        List<ChunkData.BlockCount> histogram = null;
        if ((flags & ChunkData.FLAG_BLOCK_HIST) != 0) {
            int entries = r.u8();
            histogram = new ArrayList<>(entries);
            for (int i = 0; i < entries; i++) {
                int blockId = r.u16();
                long count = r.u32();
                histogram.add(new ChunkData.BlockCount(blockId, count));
            }
        }
        int[] surfaceBlocks = null;
        if ((flags & ChunkData.FLAG_SURFACE_BLOCKS) != 0) {
            surfaceBlocks = new int[n];
            for (int i = 0; i < n; i++) {
                surfaceBlocks[i] = r.u16();
            }
        }
        return new ChunkData(chunkX, chunkZ, sampleStep, sampleSize, minY, maxY,
                heights, biomes, histogram, surfaceBlocks);
    }

    public static ChunkData decodeBody(byte[] body) {
        ByteReader r = new ByteReader(body);
        ChunkData data = decodeBody(r);
        if (r.hasRemaining()) {
            throw new IllegalArgumentException("chunk body 尾部有多余字节: " + r.remaining());
        }
        return data;
    }

    /** 段字节长度（分批时预估 4MiB/8MiB 上限）。 */
    public static int bodySize(ChunkData data) {
        int size = 4 + 4 + 1 + 1 + 2 + 2 + 1 + data.cells() * 2;
        if (data.hasBiomes()) {
            size += data.cells() * 2;
        }
        if (data.hasBlockHistogram()) {
            size += 1 + data.blockHistogram().size() * 6;
        }
        if (data.hasSurfaceBlocks()) {
            size += data.cells() * 2;
        }
        return size;
    }
}
