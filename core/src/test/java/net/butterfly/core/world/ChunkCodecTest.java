package net.butterfly.core.world;

import net.butterfly.api.world.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkCodecTest {

    private static final BlockState AIR = BlockState.of("minecraft:air");
    private static final BlockState STONE = BlockState.of("minecraft:stone");

    @Test
    void roundTrip_filledStone() {
        SubChunk original = SubChunk.filledWith(0, STONE);
        byte[] bytes = ChunkCodec.encodeSubChunk(original);
        SubChunk decoded = ChunkCodec.decodeSubChunk((byte) 0, bytes);

        assertEquals(0, decoded.subY());
        assertEquals(1, decoded.paletteSize());
        assertEquals(STONE, decoded.getBlock(0, 0, 0));
        assertEquals(STONE, decoded.getBlock(15, 15, 15));
        assertSubChunksEqual(original, decoded);
    }

    @Test
    void roundTrip_twoPaletteEntries() {
        SubChunk original = SubChunk.filledWith(2, AIR)
                .withBlock(0, 0, 0, STONE)
                .withBlock(1, 0, 0, STONE)
                .withBlock(15, 15, 15, STONE)
                .withBlock(7, 3, 11, STONE);

        byte[] bytes = ChunkCodec.encodeSubChunk(original);
        SubChunk decoded = ChunkCodec.decodeSubChunk((byte) 2, bytes);

        assertEquals(2, decoded.subY());
        assertEquals(2, decoded.paletteSize());
        assertEquals(STONE, decoded.getBlock(0, 0, 0));
        assertEquals(STONE, decoded.getBlock(1, 0, 0));
        assertEquals(STONE, decoded.getBlock(15, 15, 15));
        assertEquals(STONE, decoded.getBlock(7, 3, 11));
        assertEquals(AIR, decoded.getBlock(2, 0, 0));
        assertEquals(AIR, decoded.getBlock(8, 8, 8));
        assertSubChunksEqual(original, decoded);
    }

    @Test
    void roundTrip_preservesProperties() {
        BlockState log = new BlockState("minecraft:oak_log",
                Map.of("pillar_axis", "y"));
        SubChunk original = SubChunk.filledWith(-4, AIR).withBlock(3, 4, 5, log);
        byte[] bytes = ChunkCodec.encodeSubChunk(original);
        SubChunk decoded = ChunkCodec.decodeSubChunk((byte) -4, bytes);

        BlockState got = decoded.getBlock(3, 4, 5);
        assertEquals("minecraft:oak_log", got.name());
        assertEquals("y", got.properties().get("pillar_axis"));
    }

    private static void assertSubChunksEqual(SubChunk expected, SubChunk actual) {
        assertEquals(expected.subY(), actual.subY());
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(
                            expected.getBlock(x, y, z),
                            actual.getBlock(x, y, z),
                            "mismatch at (" + x + "," + y + "," + z + ")");
                }
            }
        }
    }
}
