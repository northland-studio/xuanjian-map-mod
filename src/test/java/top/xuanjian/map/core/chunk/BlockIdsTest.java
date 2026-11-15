package top.xuanjian.map.core.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlockIdsTest {

    @Test
    void keepsNamespace() {
        assertEquals("minecraft:grass_block", BlockIds.normalize("minecraft:grass_block"));
        assertEquals("create:shaft", BlockIds.normalize("create:shaft"));
    }

    @Test
    void addsDefaultNamespaceForBarePath() {
        // 历史 bug：1.21 用 ResourceLocation.getPath() 拿到的是裸 path
        assertEquals("minecraft:grass_block", BlockIds.normalize("grass_block"));
        assertEquals("minecraft:dirt", BlockIds.normalize("dirt"));
        assertEquals("minecraft:oak_planks", BlockIds.normalize("oak_planks"));
    }

    @Test
    void parsesResourceKeyToString() {
        assertEquals("minecraft:grass_block",
                BlockIds.normalize("ResourceKey[minecraft:block / minecraft:grass_block]"));
        assertEquals("minecraft:stone",
                BlockIds.normalize("ResourceKey[minecraft:block / minecraft:stone]"));
    }

    @Test
    void rejectsGarbage() {
        assertNull(BlockIds.normalize(null));
        assertNull(BlockIds.normalize(""));
        assertNull(BlockIds.normalize("   "));
        assertNull(BlockIds.normalize("null"));
        assertNull(BlockIds.normalize("Block{minecraft:stone}"));
        assertNull(BlockIds.normalize("minecraft:"));
    }

    @Test
    void dualHashPaletteCompatibilityDuringMigration() {
        // 服务端双登记兼容期：同一个方块，契约（带命名空间）hash 与历史（裸 path）hash 都命中 palette
        int contractGrass = BlockHash.hash16(BlockIds.normalize("minecraft:grass_block"));
        int legacyGrass = BlockHash.hash16("grass_block"); // 旧版直接 hash 裸 path（服务端别名）
        assertEquals(54839, contractGrass, "契约向量 grass_block");
        assertEquals(29901, legacyGrass, "旧版无命名空间向量 grass_block（服务端别名）");
        assertNotEquals(contractGrass, legacyGrass);

        assertEquals(9188, BlockHash.hash16(BlockIds.normalize("minecraft:water")));
        assertEquals(5338, BlockHash.hash16("water"));
        assertEquals(36847, BlockHash.hash16(BlockIds.normalize("minecraft:dirt")));
        assertEquals(57782, BlockHash.hash16("dirt"));
    }
    @Test
    void namespaceStrippedHashesMatchProductionLegacyData() {
        // 线上旧数据是「无命名空间」hash：dirt=57782 / water=5338 / grass_block=29901
        assertEquals(57782, BlockHash.hash16("dirt"));
        assertEquals(5338, BlockHash.hash16("water"));
        assertEquals(29901, BlockHash.hash16("grass_block"));
        // normalize 之后（契约要求）带命名空间，hash 完全不同
        assertEquals("minecraft:dirt", BlockIds.normalize("dirt"));
        assertEquals(54839, BlockHash.hash16(BlockIds.normalize("minecraft:grass_block")));
        assertEquals(25387, BlockHash.hash16(BlockIds.normalize("minecraft:dirt")));
    }
}
