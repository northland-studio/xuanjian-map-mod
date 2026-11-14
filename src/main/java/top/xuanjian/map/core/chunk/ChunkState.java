package top.xuanjian.map.core.chunk;

import java.util.Locale;

/** 本地区块的同步状态。上传失败永远不丢本地数据（规范第八节）。 */
public enum ChunkState {
    /** 已采集、已落本地缓存，等待服务端 plan 决定是否需要上传。 */
    CAPTURED,
    /** 服务端 plan 说 missing，已进入上传队列。 */
    QUEUED,
    /** 服务端确认收到。 */
    UPLOADED,
    /** 重试耗尽；数据仍在本地，可手动/下次启动重试。 */
    FAILED;

    public static ChunkState fromWire(String wire) {
        if (wire == null) {
            return CAPTURED;
        }
        try {
            return valueOf(wire.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CAPTURED;
        }
    }

    public String labelZh() {
        return switch (this) {
            case CAPTURED -> "已采集";
            case QUEUED -> "待上传";
            case UPLOADED -> "已上传";
            case FAILED -> "上传失败";
        };
    }
}
