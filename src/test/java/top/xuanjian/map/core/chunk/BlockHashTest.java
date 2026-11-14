package top.xuanjian.map.core.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockHashTest {

    @Test
    void hash16MatchesProductionAlgorithm() {
        // 契约：CRC32(方块资源名) & 0xFFFF（与站点 palette 同算法）
        assertEquals(57453, BlockHash.hash16("minecraft:grass_block"));
        assertEquals(58446, BlockHash.hash16("minecraft:water"));
        assertEquals(10343, BlockHash.hash16("minecraft:stone"));
        assertEquals(6678, BlockHash.hash16("minecraft:sand"));
        assertEquals(24466, BlockHash.hash16("minecraft:air"));
        assertEquals(45054, BlockHash.hash16("minecraft:oak_log"));
        assertEquals(25387, BlockHash.hash16("minecraft:dirt"));
        assertEquals(46571, BlockHash.hash16("minecraft:short_grass"));
    }

    @Test
    void hash16IsStableAcrossCallsAndTrimmed() {
        int first = BlockHash.hash16("minecraft:grass_block");
        int second = BlockHash.hash16("minecraft:grass_block");
        assertEquals(first, second, "同一资源名必须稳定（跨分支/跨 JVM 一致）");
        assertEquals(first, BlockHash.hash16("  minecraft:grass_block  "), "应容忍前后空白");
        assertNotEquals(first, BlockHash.hash16("minecraft:dirt"));
    }

    @Test
    void noSurfaceSentinel() {
        assertEquals(0xFFFF, BlockHash.NO_SURFACE);
        assertEquals(BlockHash.NO_SURFACE, BlockHash.hash16(null));
        assertEquals(BlockHash.NO_SURFACE, BlockHash.hash16(""));
        assertEquals(BlockHash.NO_SURFACE, BlockHash.hash16("   "));
        assertTrue(BlockHash.isNoSurface(0xFFFF));
        assertFalse(BlockHash.isNoSurface(BlockHash.hash16("minecraft:stone")));
    }

    @Test
    void hash16FitsInUnsignedShort() {
        for (String name : new String[]{"minecraft:grass_block", "minecraft:water",
                "minecraft:deepslate", "minecraft:andesite", "minecraft:podzol",
                "minecraft:snow_block", "minecraft:lava", "minecraft:bedrock"}) {
            int hash = BlockHash.hash16(name);
            assertTrue(hash >= 0 && hash <= 0xFFFF, "hash 必须落在 u16: " + name + " -> " + hash);
        }
    }
}
