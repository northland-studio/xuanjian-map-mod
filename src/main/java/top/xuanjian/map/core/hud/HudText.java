package top.xuanjian.map.core.hud;

import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.geo.LocationInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 文案（纯函数，便于单元测试）。
 *
 * <p>规范第十二节要求的格式：
 * <ul>
 *   <li>无道路时：{@code 当前城区 · 无道路 · X/Y/Z}</li>
 *   <li>在道路上：{@code 当前城区 · 道路名 · X/Y/Z}</li>
 *   <li>展开态再补充 Dimension、缓存/上传状态、附近 Marker 数。</li>
 * </ul>
 */
public final class HudText {

    private HudText() {
    }

    /** 折叠态单行：城区 · 道路 · X/Y/Z。 */
    public static String compact(LocationInfo location, int x, int y, int z) {
        LocationInfo loc = location == null ? LocationInfo.EMPTY : location;
        return loc.districtLabel() + " · " + loc.roadLabel() + " · " + x + "/" + y + "/" + z;
    }

    /** 展开态多行：城区 / 道路 / 坐标 / 维度（+ 可选状态行）。 */
    public static List<String> expanded(LocationInfo location, int x, int y, int z,
                                        String dimension, List<String> extraStatusLines) {
        LocationInfo loc = location == null ? LocationInfo.EMPTY : location;
        List<String> lines = new ArrayList<>(8);
        lines.add("城区：" + loc.districtLabel());
        lines.add("道路：" + loc.roadLabel());
        lines.add("坐标：" + x + " / " + y + " / " + z);
        lines.add("维度：" + Dimensions.labelZh(dimension) + " (" + Dimensions.shortName(dimension) + ")");
        if (loc.nearbyMarkers() > 0) {
            lines.add("附近 Marker：" + loc.nearbyMarkers());
        }
        if (extraStatusLines != null) {
            for (String s : extraStatusLines) {
                if (s != null && !s.isBlank()) {
                    lines.add(s);
                }
            }
        }
        return lines;
    }

    /**
     * 位置变化提示文案（进入新城区/新道路时本地提示，不上传）。
     *
     * @return 无变化返回 null
     */
    public static String transitionMessage(LocationInfo before, LocationInfo after) {
        if (after == null) {
            return null;
        }
        if (before == null) {
            return after.hasRoad()
                    ? "进入 " + after.districtLabel() + " · " + after.roadLabel()
                    : "进入 " + after.districtLabel();
        }
        boolean districtChanged = !eq(before.districtId(), after.districtId());
        boolean roadChanged = !eq(before.roadId(), after.roadId());
        if (!districtChanged && !roadChanged) {
            return null;
        }
        if (districtChanged && roadChanged) {
            return "进入 " + after.districtLabel() + " · " + after.roadLabel();
        }
        if (districtChanged) {
            return "进入 " + after.districtLabel();
        }
        return "进入 " + after.roadLabel();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
