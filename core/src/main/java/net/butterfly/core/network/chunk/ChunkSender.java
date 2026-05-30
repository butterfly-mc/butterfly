package net.butterfly.core.network.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.core.world.Chunk;
import net.butterfly.nbt.VarInts;

/**
 * Helpers that build the bodies of the orchestration packets used while shipping
 * the initial play-area chunks to a freshly-spawned player.
 *
 * <p>None of these methods include the leading packet-id byte — that is added
 * by the {@link net.butterfly.codec.BatchCodec} when the surrounding
 * {@link net.butterfly.codec.packets.RawCapturePacket} is encoded.
 *
 * <p>Wire layouts are transcribed from gophertunnel's {@code minecraft/protocol/packet}
 * package for protocol 975 (MCBE 1.21.x).
 *
 * <ul>
 *   <li>{@code LevelChunk}                  — 0x3a — see {@link LevelChunkEncoder}.</li>
 *   <li>{@code ChunkRadiusUpdated}          — 0x46 — single zigzag varint.</li>
 *   <li>{@code NetworkChunkPublisherUpdate} — 0x79 — block-pos + radius (in blocks)
 *       + (varuint) saved-chunk count.</li>
 * </ul>
 */
public final class ChunkSender {

    private ChunkSender() {}

    /**
     * Encode {@code chunk} as the body of a {@code LevelChunk} (0x3a) packet.
     * Thin delegate over {@link LevelChunkEncoder#encode(Chunk, int)} kept here so
     * callers don't have to reach into the encoder package directly.
     */
    public static byte[] levelChunkBody(Chunk chunk, int dim) {
        return LevelChunkEncoder.encode(chunk, dim);
    }

    /**
     * Body of {@code NetworkChunkPublisherUpdate} (id 0x79) — tells the client
     * the center of the cube of chunks the server is about to send and the
     * radius (in <em>blocks</em>, not chunks) of that cube. The client uses this
     * to gate which incoming chunks it will accept and to position the loading
     * screen / fog of war.
     *
     * <p>Wire layout (gophertunnel {@code network_chunk_publisher_update.go}):
     * <pre>
     *   varint    blockX            // zigzag, world coords
     *   varuint   blockY
     *   varint    blockZ            // zigzag, world coords
     *   varuint   radius            // in BLOCKS (not chunks)
     *   varuint   savedChunksCount  // 0 for the MVP — no client cache
     *   // (no saved chunks because count = 0)
     * </pre>
     */
    public static byte[] networkChunkPublisherUpdateBody(int blockX, int blockY, int blockZ, int radius) {
        ByteBuf buf = Unpooled.buffer(16);
        try {
            VarInts.writeInt(buf, blockX);                  // varint zigzag
            VarInts.writeUnsignedInt(buf, blockY);          // varuint
            VarInts.writeInt(buf, blockZ);                  // varint zigzag
            VarInts.writeUnsignedInt(buf, radius);          // radius in blocks
            VarInts.writeUnsignedInt(buf, 0);               // savedChunksCount = 0
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /**
     * Body of {@code ChunkRadiusUpdated} (id 0x46) — server's reply to the
     * client's {@code RequestChunkRadius}. A single zigzag varint with the
     * server-approved chunk view radius.
     */
    public static byte[] chunkRadiusUpdatedBody(int chunkRadius) {
        ByteBuf buf = Unpooled.buffer(4);
        try {
            VarInts.writeInt(buf, chunkRadius);             // varint zigzag
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }
}
