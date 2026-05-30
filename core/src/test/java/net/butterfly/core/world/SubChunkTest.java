package net.butterfly.core.world;

import net.butterfly.api.world.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class SubChunkTest {

    private static final BlockState STONE = BlockState.of("minecraft:stone");
    private static final BlockState DIRT = BlockState.of("minecraft:dirt");

    @Test
    void filledWith_returnsSameStateAtAnyPosition() {
        SubChunk sc = SubChunk.filledWith(0, STONE);
        assertEquals(STONE, sc.getBlock(0, 0, 0));
        assertEquals(STONE, sc.getBlock(15, 15, 15));
        assertEquals(STONE, sc.getBlock(7, 3, 11));
        assertEquals(1, sc.paletteSize());
    }

    @Test
    void withBlock_isCopyOnWrite() {
        SubChunk original = SubChunk.filledWith(0, STONE);
        SubChunk modified = original.withBlock(1, 2, 3, DIRT);

        assertNotSame(original, modified);
        assertEquals(STONE, original.getBlock(1, 2, 3));
        assertEquals(DIRT, modified.getBlock(1, 2, 3));
        assertEquals(STONE, modified.getBlock(0, 0, 0));
    }

    @Test
    void withBlock_growsPaletteForNewState() {
        SubChunk sc = SubChunk.filledWith(0, STONE);
        assertEquals(1, sc.paletteSize());

        SubChunk dirtAt = sc.withBlock(1, 2, 3, DIRT);
        assertEquals(2, dirtAt.paletteSize());

        // Reusing an existing palette entry must not grow.
        SubChunk dirtAgain = dirtAt.withBlock(4, 5, 6, DIRT);
        assertEquals(2, dirtAgain.paletteSize());
        assertEquals(DIRT, dirtAgain.getBlock(4, 5, 6));
    }

    @Test
    void empty_isAllAir() {
        SubChunk sc = SubChunk.empty(-4);
        assertEquals(BlockState.of("minecraft:air"), sc.getBlock(8, 8, 8));
        assertEquals(-4, sc.subY());
    }
}
