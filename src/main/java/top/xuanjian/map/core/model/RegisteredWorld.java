package top.xuanjian.map.core.model;

import java.util.List;

/**
 * 服务端注册过的世界（GET /api/gis/client/bootstrap 返回的 worlds[]）。
 *
 * @param id        世界数字 ID（上传报文 worldId u32）
 * @param worldKey  世界稳定标识（resolve-world 用）
 * @param status    ACTIVE / ARCHIVED / SNAPSHOT
 * @param current   是否为该服务器的当前世界
 * @param mcVersion 该世界的 Minecraft 版本
 * @param dimensions 该世界已登记的 Dimension 列表
 */
public record RegisteredWorld(long id, String worldKey, WorldStatus status, boolean current,
                              String mcVersion, List<String> dimensions) {

    public RegisteredWorld {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public boolean supportsDimension(String dimension) {
        if (dimensions.isEmpty()) {
            return true;
        }
        for (String d : dimensions) {
            if (top.xuanjian.map.core.dimension.Dimensions.normalize(d).equals(
                    top.xuanjian.map.core.dimension.Dimensions.normalize(dimension))) {
                return true;
            }
        }
        return false;
    }
}
