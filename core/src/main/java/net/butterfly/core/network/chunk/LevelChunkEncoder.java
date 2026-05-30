package net.butterfly.core.network.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.api.world.BlockState;
import net.butterfly.core.world.Chunk;
import net.butterfly.core.world.ChunkCodec;
import net.butterfly.core.world.SubChunk;
import net.butterfly.nbt.VarInts;

/**
 * Encodes a {@link Chunk} into the body of a {@code LevelChunk} (id {@code 0x3a})
 * packet using the non-cached, vanilla full-send mode (no {@code SubChunkRequest}
 * mode). The returned bytes are the packet body only — the BatchCodec prepends
 * the packet-id header.
 *
 * <p>Wire layout (protocol 975, transcribed from gophertunnel
 * {@code minecraft/protocol/packet/level_chunk.go}):
 * <pre>
 * varint   chunkX                // zigzag
 * varint   chunkZ                // zigzag
 * varint   dimension             // zigzag (0 = overworld)
 * varuint  subChunkCount         // number of sub-chunks in the payload
 * byte     cacheEnabled          // 0 = no client cache (we never use it)
 * // (cacheEnabled = 1 only) blob hashes — omitted because cacheEnabled = 0
 * varuint  payloadLength
 * bytes    payload
 * </pre>
 *
 * <p>The non-cached payload is the concatenation of, in order:
 * <ol>
 *   <li>{@code subChunkCount} sub-chunk blobs (each encoded by
 *       {@link ChunkCodec#encodeSubChunk(SubChunk)}).</li>
 *   <li>3D biome data: 24 paletted-storage layers, one per sub-biome. For the
 *       MVP every layer is a single-value <em>plains</em> layer
 *       ({@code 0x01 0x02}: header {@code (bpb=0)<<1 | network=1}, then a
 *       single varint-zigzag biome id = 1).</li>
 *   <li>1 byte border block count = {@code 0x00}.</li>
 *   <li>Block entities — concatenated NBT compounds, no length prefix.
 *       MVP writes none, i.e. emits zero bytes here.</li>
 * </ol>
 *
 * <p><b>Deviations from the original plan</b> (verified against gophertunnel
 * {@code level_chunk.go} and dragonfly's {@code chunk/encoding.go}):
 * <ul>
 *   <li>The byte after {@code subChunkCount} is a {@code Bool} ("cache enabled"),
 *       not a {@code varuint} blob-count. We write {@code 0x00} (false).</li>
 *   <li>Each single-value biome layer is <b>2 bytes</b> ({@code 0x01 0x02}),
 *       not the 3 bytes the plan derived. The header low bit is the
 *       {@code network} flag — set to {@code 1} for over-the-wire encoding —
 *       and for {@code bitsPerBlock = 0} the palette size is implicitly 1 and
 *       is <em>not</em> written.</li>
 * </ul>
 */
public final class LevelChunkEncoder {

    /** Numeric runtime id of the plains biome (varint zigzag = {@code 0x02}). */
    private static final int PLAINS_BIOME_ID = 1;

    /** Number of sub-biome layers per chunk (one per sub-chunk in the column). */
    static final int BIOME_LAYER_COUNT = Chunk.SUBCHUNK_COUNT;

    private static final BlockState AIR = BlockState.of("minecraft:air");

    private LevelChunkEncoder() {}

    /**
     * Encode {@code chunk} as a LevelChunk packet body.
     *
     * @param chunk     the chunk to send; must not be {@code null}
     * @param dimension dimension id (0 overworld, 1 nether, 2 end)
     * @return the encoded body bytes (no packet-id header)
     */
    public static byte[] encode(Chunk chunk, int dimension) {
        SubChunk[] subs = chunk.subChunks();              // single snapshot
        int sendCount = highestNonEmptySubChunkInSnapshot(subs) + 1; // -1 → 0

        ByteBuf body = Unpooled.buffer(256);
        try {
            VarInts.writeInt(body, chunk.chunkX());
            VarInts.writeInt(body, chunk.chunkZ());
            VarInts.writeInt(body, dimension);
            VarInts.writeUnsignedInt(body, sendCount);
            body.writeByte(0);                            // cacheEnabled = false

            byte[] payload = buildPayload(subs, sendCount);
            VarInts.writeUnsignedInt(body, payload.length);
            body.writeBytes(payload);

            byte[] out = new byte[body.readableBytes()];
            body.readBytes(out);
            return out;
        } finally {
            body.release();
        }
    }

    /**
     * Returns the highest sub-chunk array index that contains at least one
     * non-air block, or {@code -1} if every sub-chunk is air.
     *
     * <p>Walks indices from {@code 23} downwards, stopping at the first
     * non-air sub-chunk.
     */
    static int highestNonEmptySubChunk(Chunk chunk) {
        return highestNonEmptySubChunkInSnapshot(chunk.subChunks());
    }

    private static int highestNonEmptySubChunkInSnapshot(SubChunk[] subs) {
        for (int i = subs.length - 1; i >= 0; i--) {
            if (!isAllAir(subs[i])) return i;
        }
        return -1;
    }

    private static boolean isAllAir(SubChunk sub) {
        // A sub-chunk is "empty" iff every palette entry is air. The indices
        // array still references the palette, so a palette of one air entry
        // means every block in the sub-chunk is air.
        for (BlockState entry : sub.palette()) {
            if (!AIR.equals(entry)) return false;
        }
        return true;
    }

    // -- payload assembly -----------------------------------------------------

    private static byte[] buildPayload(SubChunk[] subs, int sendCount) {
        ByteBuf payload = Unpooled.buffer(2048);
        try {
            // 1) sub-chunk blobs (sendCount of them, indices 0..sendCount-1)
            for (int i = 0; i < sendCount; i++) {
                payload.writeBytes(ChunkCodec.encodeSubChunk(subs[i]));
            }
            // 2) 3D biomes — 24 trivial single-value plains layers
            writeBiomes(payload);
            // 3) border block count = 0
            payload.writeByte(0);
            // 4) block entities (none for MVP — emit no bytes)

            byte[] out = new byte[payload.readableBytes()];
            payload.readBytes(out);
            return out;
        } finally {
            payload.release();
        }
    }

    /**
     * Emit 24 single-value paletted-storage biome layers for the trivial
     * "all plains" world. Per-layer encoding follows gophertunnel/dragonfly's
     * {@code networkEncoding.encodePalette} for a {@code bitsPerIndex = 0}
     * layer:
     *
     * <ul>
     *   <li>header byte: {@code (bpb << 1) | network} = {@code (0 << 1) | 1}
     *       = {@code 0x01}</li>
     *   <li>no packed-indices array (bpb=0 means the storage carries no
     *       indices)</li>
     *   <li>no palette size (network encoding skips it when {@code p.size == 0})</li>
     *   <li>one varint-zigzag for the single biome id ({@code 1 → 0x02})</li>
     * </ul>
     *
     * Each layer is 2 bytes; 24 layers = 48 bytes total.
     */
    static void writeBiomes(ByteBuf buf) {
        for (int i = 0; i < BIOME_LAYER_COUNT; i++) {
            buf.writeByte(0x01);                         // header: bpb=0, network=1
            VarInts.writeInt(buf, PLAINS_BIOME_ID);      // 1 → varint zigzag 0x02
        }
    }
}
