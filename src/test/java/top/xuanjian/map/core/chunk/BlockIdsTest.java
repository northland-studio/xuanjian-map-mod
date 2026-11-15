package top.xuanjian.map.core.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void namespaceStrippedHashesMatchProductionLegacyData() {
        // 线上旧数据是「无命名空间」hash：dirt=57782 / water=5338 / grass_block=29901
        assertEquals(57782, BlockHash.hash16(BlockIds.normalize("dirt")));
        assertEquals(5338, BlockHash.hash16(BlockIds.normalize("water")));
        assertEquals(29901, BlockHash.hash16(BlockIds.normalize("grass_block")));
        // 而契约/服务端 palette 用的是带命名空间 hash
        assertEquals(54839, BlockHash.hash16(BlockIds.normalize("minecraft:grass_block")));
        assertEquals(25387, BlockHash.hash16(BlockIds.normalize("minecraft:dirt")));
    }
}
