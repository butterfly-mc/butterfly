package net.butterfly.core.network.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.nbt.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trips the helper bodies built by {@link ChunkSender}. The encoder is the
 * only thing under test here — the decode side is hand-rolled with
 * {@link VarInts} so the test asserts the wire layout, not just that the encoder
 * agrees with itself.
 */
class ChunkSenderTest {

    @Test
    void chunkRadiusUpdatedBody_emitsZigZagVarint() {
        byte[] body = ChunkSender.chunkRadiusUpdatedBody(8);
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        assertEquals(8, VarInts.readInt(buf));
        assertEquals(0, buf.readableBytes(),
            "ChunkRadiusUpdated body has exactly one varint");
    }

    @Test
    void chunkRadiusUpdatedBody_negativeRadius_zigZagWorks() {
        byte[] body = ChunkSender.chunkRadiusUpdatedBody(-3);
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        assertEquals(-3, VarInts.readInt(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void networkChunkPublisherUpdateBody_layoutMatchesProtocol() {
        // Spawn-ish coords with a 128-block radius (8 chunks * 16 blocks).
        int blockX = 12;
        int blockY = 100;
        int blockZ = -34;
        int radius = 128;

        byte[] body = ChunkSender.networkChunkPublisherUpdateBody(blockX, blockY, blockZ, radius);
        ByteBuf buf = Unpooled.wrappedBuffer(body);

        assertEquals(blockX, VarInts.readInt(buf));               // varint zigzag
        assertEquals(blockY, (int) VarInts.readUnsignedInt(buf)); // varuint
        assertEquals(blockZ, VarInts.readInt(buf));               // varint zigzag
        assertEquals(radius, (int) VarInts.readUnsignedInt(buf)); // varuint
        assertEquals(0L, VarInts.readUnsignedInt(buf));           // savedChunksCount
        assertEquals(0, buf.readableBytes(),
            "no trailing bytes when savedChunksCount = 0");
    }

    @Test
    void networkChunkPublisherUpdateBody_negativeXAndZ() {
        // Sanity-check the zigzag varints carry sign correctly.
        byte[] body = ChunkSender.networkChunkPublisherUpdateBody(-1024, 64, -1, 16);
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        assertEquals(-1024, VarInts.readInt(buf));
        assertEquals(64, (int) VarInts.readUnsignedInt(buf));
        assertEquals(-1, VarInts.readInt(buf));
        assertEquals(16, (int) VarInts.readUnsignedInt(buf));
        assertEquals(0L, VarInts.readUnsignedInt(buf));
        assertEquals(0, buf.readableBytes());
    }
}
