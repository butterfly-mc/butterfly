package net.butterfly.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkKeyTest {

    @Test
    void overworld_subChunkKey_isThirteenBytes() {
        byte[] key = ChunkKey.subChunk(0, 0, 0, 0);
        assertEquals(13, key.length);
        // 4 bytes cx (0) + 4 bytes cz (0) + tag 0x2c + 4 bytes subY (0)
        byte[] expected = {0, 0, 0, 0, 0, 0, 0, 0, 0x2c, 0, 0, 0, 0};
        assertArrayEquals(expected, key);
    }

    @Test
    void nonOverworld_subChunkKey_isSeventeenBytes() {
        byte[] key = ChunkKey.subChunk(0, 0, 1, 0);
        assertEquals(17, key.length);
    }

    @Test
    void parseSubChunkKey_roundTripsLocation() {
        byte[] key = ChunkKey.subChunk(5, -3, 0, 4);
        ChunkKey.ChunkLocation loc = ChunkKey.parseSubChunkKey(key);
        assertEquals(5, loc.cx());
        assertEquals(-3, loc.cz());
        assertEquals(0, loc.dim());
    }

    @Test
    void parseSubChunkKey_withDimensionField() {
        byte[] key = ChunkKey.subChunk(7, 2, 1, -1);
        ChunkKey.ChunkLocation loc = ChunkKey.parseSubChunkKey(key);
        assertEquals(7, loc.cx());
        assertEquals(2, loc.cz());
        assertEquals(1, loc.dim());
    }

    @Test
    void parseSubChunkKey_rejectsBlockEntitiesKey() {
        byte[] key = ChunkKey.blockEntities(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> ChunkKey.parseSubChunkKey(key));
    }

    @Test
    void parseSubChunkKey_rejectsArbitraryBytes() {
        byte[] junk = new byte[13];
        // valid length but wrong tag byte
        junk[8] = 0x39;
        assertThrows(IllegalArgumentException.class, () -> ChunkKey.parseSubChunkKey(junk));
    }
}
