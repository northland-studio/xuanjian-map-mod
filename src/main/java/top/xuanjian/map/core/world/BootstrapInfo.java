package top.xuanjian.map.core.world;

import top.xuanjian.map.core.model.Limits;
import top.xuanjian.map.core.model.RegisteredWorld;

import java.util.List;

/**
 * GET /api/gis/client/bootstrap 的结果（契约第 1 节）。
 *
 * @param contract    契约版本（必须为 1）
 * @param serverKey   服务器 Key（如 yl）
 * @param serverName  服务器显示名
 * @param worlds      该服务器注册的世界列表
 * @param limits      服务端限制
 * @param syncIntervalSeconds 服务端建议的增量同步间隔（缺省 60s）
 */
public record BootstrapInfo(int contract, String serverKey, String serverName,
                            List<RegisteredWorld> worlds, Limits limits,
                            int syncIntervalSeconds) {

    public BootstrapInfo {
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        limits = limits == null ? Limits.DEFAULT : limits;
        if (syncIntervalSeconds <= 0) {
            syncIntervalSeconds = 60;
        }
    }

    /** 当前世界：优先 isCurrent=true 且 ACTIVE 的世界。 */
    public RegisteredWorld currentWorld() {
        RegisteredWorld active = null;
        for (RegisteredWorld w : worlds) {
            if (w.status() != top.xuanjian.map.core.model.WorldStatus.ACTIVE) {
                continue;
            }
            if (w.current()) {
                return w;
            }
            if (active == null) {
                active = w;
            }
        }
        return active;
    }
}
