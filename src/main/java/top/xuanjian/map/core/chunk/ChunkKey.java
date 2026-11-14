package top.xuanjian.map.core.chunk;

import top.xuanjian.map.core.dimension.Dimensions;

/** 本地缓存中一个区块的唯一键（worldId + dimension + chunkX + chunkZ）。 */
public record ChunkKey(long worldId, String dimension, int chunkX, int chunkZ) {

    public ChunkKey {
        dimension = Dimensions.normalize(dimension);
    }

    public long cellHash() {
        long h = worldId * 31L + dimension.hashCode();
        h = h * 31L + chunkX;
        h = h * 31L + chunkZ;
        return h;
    }

    @Override
    public String toString() {
        return worldId + "|" + dimension + "|" + chunkX + "," + chunkZ;
    }
}
