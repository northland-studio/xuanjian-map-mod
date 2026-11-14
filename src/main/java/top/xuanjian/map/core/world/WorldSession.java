package top.xuanjian.map.core.world;

/**
 * 客户端当前的世界会话（世界绑定结果）。
 *
 * <p>客户端 <b>不能自造世界</b>：worldId/worldKey 必须来自 bootstrap + resolve-world 校验。
 * 未注册服务器/世界时 {@code registered=false}，只允许本地地图功能，禁止上传。
 *
 * @param serverKey  配置的服务器 Key
 * @param worldId    服务端世界 ID（数字）
 * @param worldKey   世界稳定 key
 * @param dimension  当前 Dimension（归一化）
 * @param mcVersion  世界登记的 Minecraft 版本
 * @param registered 是否已完成 resolve-world 校验
 * @param statusLabel 世界状态文案（ACTIVE/ARCHIVED/SNAPSHOT）
 * @param resolvedAt 校验时间（毫秒）
 * @param lastError  最近一次绑定失败原因（用于 UI）
 */
public record WorldSession(String serverKey, long worldId, String worldKey, String dimension,
                           String mcVersion, boolean registered, String statusLabel,
                           long resolvedAt, String lastError) {

    public static final WorldSession NONE =
            new WorldSession("", 0, "", "", "", false, "", 0, "尚未连接到服务器");

    public boolean canUpload() {
        return registered && worldId > 0 && !dimension.isBlank();
    }

    public String displayName() {
        if (worldKey == null || worldKey.isBlank()) {
            return "未绑定世界";
        }
        return worldKey + " (#" + worldId + ")";
    }

    public WorldSession withError(String error) {
        return new WorldSession(serverKey, worldId, worldKey, dimension, mcVersion, false,
                statusLabel, resolvedAt, error);
    }
}
