package top.xuanjian.map.core.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DimensionsTest {

    @Test
    void normalizeAcceptsVariousForms() {
        assertEquals("minecraft:overworld", Dimensions.normalize("overworld"));
        assertEquals("minecraft:overworld", Dimensions.normalize("world"));
        assertEquals("minecraft:overworld", Dimensions.normalize("主世界"));
        assertEquals("minecraft:the_nether", Dimensions.normalize("nether"));
        assertEquals("minecraft:the_nether", Dimensions.normalize("下界"));
        assertEquals("minecraft:the_end", Dimensions.normalize("end"));
        assertEquals("minecraft:custom_void", Dimensions.normalize("minecraft:custom_void"));
        assertEquals("minecraft:overworld", Dimensions.normalize(null));
    }

    @Test
    void codesFollowProductionContract() {
        assertEquals(0, Dimensions.code("minecraft:overworld"));
        assertEquals(1, Dimensions.code("minecraft:the_nether"));
        assertEquals(2, Dimensions.code("minecraft:the_end"));
        assertEquals(3, Dimensions.code("minecraft:custom_void"));
        assertEquals("minecraft:overworld", Dimensions.fromCode(0));
        assertEquals("minecraft:the_nether", Dimensions.fromCode(1));
        assertEquals("minecraft:the_end", Dimensions.fromCode(2));
    }

    @Test
    void sanitizeHandlesResourceKeyToString() {
        // 26/1.21 的 ResourceKey.toString() 形如 "ResourceKey[minecraft:dimension / minecraft:overworld]"
        assertEquals("minecraft:overworld",
                Dimensions.normalize(Dimensions.sanitize(
                        "ResourceKey[minecraft:dimension / minecraft:overworld]")));
        assertEquals("minecraft:the_nether",
                Dimensions.normalize(Dimensions.sanitize(
                        "ResourceKey[minecraft:dimension / minecraft:the_nether]")));
        assertEquals("minecraft:overworld", Dimensions.normalize(Dimensions.sanitize("minecraft:overworld")));
        assertEquals("minecraft:overworld", Dimensions.normalize(Dimensions.sanitize(null)));
        // 兜底正则
        assertEquals("minecraft:the_end",
                Dimensions.normalize(Dimensions.sanitize("key(minecraft:the_end)")));
    }

    @Test
    void labelsAndShortNames() {
        assertEquals("主世界", Dimensions.labelZh("overworld"));
        assertEquals("下界", Dimensions.labelZh("the_nether"));
        assertEquals("末地", Dimensions.labelZh("the_end"));
        assertEquals("overworld", Dimensions.shortName("minecraft:overworld"));
    }
}
