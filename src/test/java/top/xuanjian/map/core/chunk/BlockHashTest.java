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
        // 用标准 CRC32 复算（校验基准：CRC32("123456789")==0xCBF43926；Java 实际输出已与 CI 对齐）
        assertEquals(54839, BlockHash.hash16("minecraft:grass_block"));
        assertEquals(9188, BlockHash.hash16("minecraft:water"));
        assertEquals(12820, BlockHash.hash16("minecraft:stone"));
        assertEquals(64584, BlockHash.hash16("minecraft:sand"));
        assertEquals(26504, BlockHash.hash16("minecraft:air"));
        assertEquals(31742, BlockHash.hash16("minecraft:oak_log"));
        assertEquals(36847, BlockHash.hash16("minecraft:dirt"));
        assertEquals(40204, BlockHash.hash16("minecraft:short_grass"));
    }

    /**
     * 与契约自校验：直接用标准 java.util.zip.CRC32 复算，再和 BlockHash 比较。
     * 这样即使 docs/gis-api.md 的示例值调整，也能一眼看出客户端算法是否漂移。
     */
    @Test
    void hash16EqualsStandardCrc32LowBits() {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update("minecraft:grass_block".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long raw = crc.getValue();
        // 服务端口径：CRC32(resourceName UTF-8) & 0xFFFF
        assertEquals(1430378039L, raw, "minecraft:grass_block 的标准 CRC32 十进制值");
        assertEquals(54839, (int) (raw & 0xFFFFL), "CRC32 低 16 位");
        assertEquals(54839, BlockHash.hash16("minecraft:grass_block"),
                "BlockHash 必须等于 java.util.zip.CRC32 & 0xFFFF（与服务端 palette 同算法）");
        // 排序/行优先与 heightMatrix 相同由 codec/采集实现保证，这里再确认 noSurface 哨兵不参与 hash 计算
        for (String name : new String[]{"minecraft:water", "minecraft:stone", "minecraft:oak_planks"}) {
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            c.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals((int) (c.getValue() & 0xFFFFL), BlockHash.hash16(name), name);
        }
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
