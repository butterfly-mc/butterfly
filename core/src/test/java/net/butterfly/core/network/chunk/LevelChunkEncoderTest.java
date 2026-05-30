package net.butterfly.core.network.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.api.world.BlockState;
import net.butterfly.api.world.World;
import net.butterfly.core.world.Chunk;
import net.butterfly.core.world.SubChunk;
import net.butterfly.nbt.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelChunkEncoderTest {

    private static final BlockState AIR = BlockState.of("minecraft:air");
    private static final BlockState STONE = BlockState.of("minecraft:stone");

    @Test
    void encodesEmptyChunk_zeroSubchunks() {
        Chunk chunk = newChunk(2, -3, allAir());

        byte[] body = LevelChunkEncoder.encode(chunk, 0);

        // chunkX, chunkZ, dim, subChunkCount=0, cacheEnabled=0, payloadLen, payload
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        assertEquals(2, VarInts.readInt(buf));
        assertEquals(-3, VarInts.readInt(buf));
        assertEquals(0, VarInts.readInt(buf));
        assertEquals(0L, VarInts.readUnsignedInt(buf));   // sendCount
        assertEquals(0, buf.readByte());                  // cacheEnabled = false

        long payloadLen = VarInts.readUnsignedInt(buf);
        assertEquals(buf.readableBytes(), payloadLen,
                "payloadLength must match the trailing bytes");

        // Payload = 24*2 biome bytes + 1 border byte + 0 block-entity bytes
        assertEquals(LevelChunkEncoder.BIOME_LAYER_COUNT * 2 + 1, payloadLen);

        // First two bytes should be the first single-value plains biome layer.
        assertEquals((byte) 0x01, buf.readByte());        // (bpb=0)<<1 | network=1
        assertEquals((byte) 0x02, buf.readByte());        // varint zigzag(1) = 2

        // Skip the remaining 23 layers and the border byte.
        buf.skipBytes((LevelChunkEncoder.BIOME_LAYER_COUNT - 1) * 2);
        assertEquals(0, buf.readByte());                  // border block count
        assertEquals(0, buf.readableBytes(),              // no block entities
                "no trailing bytes after border");
    }

    @Test
    void encodesChunkWithStone_sendsSubchunks() {
        // Place a stone block at world (0, 64, 0). With MIN_Y = -64 the block
        // lives in sub-chunk index 8 (subY = 4). HighestNonEmpty = 8, so we
        // expect subChunkCount = 9 (indices 0..8).
        Chunk chunk = newChunk(0, 0, allAir());
        chunk.setBlock(0, 64, 0, STONE);

        byte[] body = LevelChunkEncoder.encode(chunk, 0);

        ByteBuf buf = Unpooled.wrappedBuffer(body);
        assertEquals(0, VarInts.readInt(buf));            // chunkX
        assertEquals(0, VarInts.readInt(buf));            // chunkZ
        assertEquals(0, VarInts.readInt(buf));            // dim

        long sendCount = VarInts.readUnsignedInt(buf);
        assertEquals(9L, sendCount,
                "should send sub-chunks 0..highestNonEmpty inclusive");
        assertTrue(sendCount > 0, "expected at least one sub-chunk");

        assertEquals(0, buf.readByte());                  // cacheEnabled

        long payloadLen = VarInts.readUnsignedInt(buf);
        assertEquals(buf.readableBytes(), payloadLen);
    }

    @Test
    void highestNonEmptySubChunk_emptyChunk_returnsMinusOne() {
        Chunk chunk = newChunk(0, 0, allAir());
        assertEquals(-1, LevelChunkEncoder.highestNonEmptySubChunk(chunk));
    }

    @Test
    void highestNonEmptySubChunk_topMostBlock_returns23() {
        Chunk chunk = newChunk(0, 0, allAir());
        // World.MAX_Y - 1 lands in the topmost sub-chunk (index 23, subY=19).
        chunk.setBlock(8, World.MAX_Y - 1, 8, STONE);
        assertEquals(23, LevelChunkEncoder.highestNonEmptySubChunk(chunk));
    }

    @Test
    void highestNonEmptySubChunk_bottomMostBlock_returnsZero() {
        Chunk chunk = newChunk(0, 0, allAir());
        chunk.setBlock(0, World.MIN_Y, 0, STONE);
        assertEquals(0, LevelChunkEncoder.highestNonEmptySubChunk(chunk));
    }

    @Test
    void encodingIsDeterministic() {
        Chunk a = newChunk(5, 7, allAir());
        a.setBlock(3, 64, 4, STONE);
        a.setBlock(15, 70, 0, STONE);

        Chunk b = newChunk(5, 7, allAir());
        b.setBlock(3, 64, 4, STONE);
        b.setBlock(15, 70, 0, STONE);

        byte[] first = LevelChunkEncoder.encode(a, 0);
        byte[] second = LevelChunkEncoder.encode(b, 0);
        byte[] third = LevelChunkEncoder.encode(a, 0);    // same chunk twice

        assertArrayEquals(first, second,
                "two identical chunks must encode to identical bytes");
        assertArrayEquals(first, third,
                "encoding the same chunk twice must produce identical bytes");
    }

    @Test
    void emptyChunkBiomeAndBorderBytesAreExactly49() {
        // 24 biome layers × 2 bytes + 1 border byte = 49 bytes of payload.
        Chunk chunk = newChunk(0, 0, allAir());
        byte[] body = LevelChunkEncoder.encode(chunk, 0);
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        VarInts.readInt(buf);                             // chunkX
        VarInts.readInt(buf);                             // chunkZ
        VarInts.readInt(buf);                             // dim
        VarInts.readUnsignedInt(buf);                     // sendCount
        buf.readByte();                                   // cacheEnabled
        long payloadLen = VarInts.readUnsignedInt(buf);
        assertEquals(49L, payloadLen);
    }

    // -- helpers --------------------------------------------------------------

    private static SubChunk[] allAir() {
        SubChunk[] subs = new SubChunk[Chunk.SUBCHUNK_COUNT];
        for (int i = 0; i < subs.length; i++) {
            subs[i] = SubChunk.empty(Chunk.MIN_SUB_Y + i);
        }
        return subs;
    }

    /** Constructs a Chunk without a backing world; safe because the encoder
     * never calls {@link Chunk#world()}. */
    private static Chunk newChunk(int cx, int cz, SubChunk[] subs) {
        return new Chunk(/* world = */ null, cx, cz, /* dim = */ 0, subs);
    }
}
