package top.xuanjian.map.core.hud;

import org.junit.jupiter.api.Test;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.geo.LocationInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudTextTest {

    @Test
    void compact_withRoad() {
        LocationInfo loc = new LocationInfo("d1", "青云城区", "r1", "中央大道", 3);
        assertEquals("青云城区 · 中央大道 · 12/64/-30", HudText.compact(loc, 12, 64, -30));
    }

    @Test
    void compact_noRoadMatchesSpecFormat() {
        // 规范第十二节：无道路时 `当前城区 · 无道路 · X/Y/Z`
        LocationInfo loc = new LocationInfo("d1", "青云城区", null, null, 0);
        assertEquals("青云城区 · 无道路 · 128/70/-256", HudText.compact(loc, 128, 70, -256));
    }

    @Test
    void compact_unknownDistrict() {
        assertEquals("未知区域 · 无道路 · 0/0/0", HudText.compact(LocationInfo.EMPTY, 0, 0, 0));
        assertEquals("未知区域 · 无道路 · 0/0/0", HudText.compact(null, 0, 0, 0));
    }

    @Test
    void expanded_containsAllFourRequiredFields() {
        List<String> lines = HudText.expanded(
                new LocationInfo("d1", "青云城区", null, null, 2),
                128, 70, -256, Dimensions.THE_NETHER, List.of("上传：3 待处理"));
        assertEquals("城区：青云城区", lines.get(0));
        assertEquals("道路：无道路", lines.get(1));
        assertEquals("坐标：128 / 70 / -256", lines.get(2));
        assertTrue(lines.get(3).contains("下界"));
        assertTrue(lines.get(4).contains("附近 Marker：2"));
        assertTrue(lines.get(5).contains("上传：3 待处理"));
    }

    @Test
    void transitionMessage() {
        LocationInfo before = new LocationInfo("d1", "青云城区", null, null, 0);
        assertNull(HudText.transitionMessage(before, before));
        LocationInfo afterDistrict = new LocationInfo("d2", "玄剑城区", null, null, 0);
        assertEquals("进入 玄剑城区", HudText.transitionMessage(before, afterDistrict));
        LocationInfo afterRoad = new LocationInfo("d1", "青云城区", "r9", "天枢路", 0);
        assertEquals("进入 天枢路", HudText.transitionMessage(before, afterRoad));
        assertEquals("进入 玄剑城区 · 天枢路",
                HudText.transitionMessage(before, new LocationInfo("d2", "玄剑城区", "r9", "天枢路", 0)));
        assertEquals("进入 青云城区", HudText.transitionMessage(null, before));
    }
}
