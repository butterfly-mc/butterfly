package net.butterfly.core.world;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.api.world.BlockState;
import net.butterfly.nbt.NbtLeReader;
import net.butterfly.nbt.NbtMap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes and decodes Bedrock LevelDB sub-chunk payloads (tag {@link ChunkKey#TAG_SUBCHUNK}).
 *
 * <p>Only sub-chunk version <b>9</b> (1.18+) is supported in either direction. Earlier
 * formats (v0/v1/v8) throw {@link UnsupportedOperationException} on read; the encoder
 * always emits v9 with a single block-storage layer and a persistent NBT palette.
 *
 * <p>v9 layout:
 * <pre>
 * byte version = 9
 * byte storageCount
 * byte subY (signed, two's-complement)
 * for each storage:
 *   byte (bitsPerBlock &lt;&lt; 1) | runtime    // runtime bit must be 0 for persistent NBT palette
 *   uint32[wordCount] LE                    // packed indices, low bits first within each word
 *   int32 LE paletteCount
 *   for each palette entry: NbtMap encoded as fixed-size LittleEndian NBT
 * </pre>
 *
 * <p>Each palette entry compound is {@code {name: <BlockState.name>, states: <properties>,
 * version: 17959425 (1.18.10 packed)}}.
 */
public final class ChunkCodec {

    /** Sub-chunk version emitted by {@link #encodeSubChunk(SubChunk)}. */
    public static final byte VERSION_9 = 9;

    /** Block-state version stamp written into each palette entry. 1.18.10 packed encoding. */
    public static final int BLOCK_STATE_VERSION = 17959425;

    private ChunkCodec() {}

    /**
     * Decodes a v9 sub-chunk blob.
     *
     * <p>The {@code subY} parameter is provided alongside the bytes because callers usually
     * already know it (it is part of the LevelDB key); the encoded value inside the blob is
     * cross-checked when present.
     *
     * @throws UnsupportedOperationException if the version byte is not {@link #VERSION_9}
     */
    public static SubChunk decodeSubChunk(byte subY, byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            throw new IllegalArgumentException("Sub-chunk blob too short: " + (bytes == null ? -1 : bytes.length));
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            int version = buf.readByte() & 0xff;
            if (version != VERSION_9) {
                throw new UnsupportedOperationException(
                        "Unsupported sub-chunk version: " + version + " (only v9 is supported)");
            }
            int storageCount = buf.readByte() & 0xff;
            byte encodedSubY = buf.readByte();
            if (encodedSubY != subY) {
                // Trust the encoded value; callers may pass 0 if they don't know.
                subY = encodedSubY;
            }
            if (storageCount < 1) {
                return SubChunk.empty(subY);
            }
            // First storage is canonical; skip extra layers (waterlog, etc.) for the MVP.
            SubChunk first = readStorage(buf, subY);
            for (int s = 1; s < storageCount; s++) {
                skipStorage(buf);
            }
            return first;
        } finally {
            buf.release();
        }
    }

    /**
     * Encodes a sub-chunk to v9 with a single block-storage layer using a persistent NBT
     * palette. The returned array is sized to the encoded length.
     */
    public static byte[] encodeSubChunk(SubChunk sub) {
        ByteBuf out = Unpooled.buffer(64);
        try {
            out.writeByte(VERSION_9);
            out.writeByte(1); // single storage
            out.writeByte(sub.subY());
            writeStorage(out, sub);
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            out.release();
        }
    }

    // -- storage helpers --------------------------------------------------------------------

    private static SubChunk readStorage(ByteBuf buf, int subY) {
        int header = buf.readByte() & 0xff;
        int bpb = header >>> 1;
        int runtime = header & 1;
        if (runtime != 0) {
            throw new UnsupportedOperationException(
                    "Runtime-id sub-chunk storages are not supported (header=0x"
                            + Integer.toHexString(header) + ")");
        }
        if (!isValidBpb(bpb)) {
            throw new IllegalArgumentException("Unexpected bits-per-block: " + bpb);
        }

        short[] indices;
        if (bpb == 0) {
            indices = new short[SubChunk.VOLUME];
        } else {
            int blocksPerWord = 32 / bpb;
            int wordCount = (SubChunk.VOLUME + blocksPerWord - 1) / blocksPerWord;
            indices = new short[SubChunk.VOLUME];
            int blockIdx = 0;
            int mask = (1 << bpb) - 1;
            for (int w = 0; w < wordCount; w++) {
                int word = buf.readIntLE();
                for (int slot = 0; slot < blocksPerWord && blockIdx < SubChunk.VOLUME; slot++) {
                    int v = (word >>> (slot * bpb)) & mask;
                    indices[blockIdx++] = (short) v;
                }
            }
        }

        int paletteCount = buf.readIntLE();
        if (paletteCount <= 0) {
            throw new IllegalArgumentException("Invalid palette count: " + paletteCount);
        }
        BlockState[] palette = new BlockState[paletteCount];
        NbtLeReader reader = new NbtLeReader(buf);
        for (int i = 0; i < paletteCount; i++) {
            NbtMap entry = reader.readCompound();
            palette[i] = paletteEntryToState(entry);
        }
        return new SubChunk(subY, palette, indices);
    }

    private static void writeStorage(ByteBuf buf, SubChunk sub) {
        int paletteSize = sub.paletteSize();
        int bpb = bitsPerBlockFor(paletteSize);
        buf.writeByte((bpb << 1) /* runtime=0 */);

        short[] indices = sub.indices();
        if (bpb > 0) {
            int blocksPerWord = 32 / bpb;
            int mask = (1 << bpb) - 1;
            int blockIdx = 0;
            int wordCount = (SubChunk.VOLUME + blocksPerWord - 1) / blocksPerWord;
            for (int w = 0; w < wordCount; w++) {
                int word = 0;
                for (int slot = 0; slot < blocksPerWord && blockIdx < SubChunk.VOLUME; slot++) {
                    word |= (indices[blockIdx++] & mask) << (slot * bpb);
                }
                buf.writeIntLE(word);
            }
        }

        buf.writeIntLE(paletteSize);
        NbtLeWriter writer = new NbtLeWriter(buf);
        for (BlockState state : sub.palette()) {
            writer.writeCompound(stateToPaletteEntry(state));
        }
    }

    private static void skipStorage(ByteBuf buf) {
        int header = buf.readByte() & 0xff;
        int bpb = header >>> 1;
        if (!isValidBpb(bpb)) {
            throw new IllegalArgumentException("Unexpected bits-per-block in extra layer: " + bpb);
        }
        if (bpb > 0) {
            int blocksPerWord = 32 / bpb;
            int wordCount = (SubChunk.VOLUME + blocksPerWord - 1) / blocksPerWord;
            buf.skipBytes(wordCount * 4);
        }
        int paletteCount = buf.readIntLE();
        NbtLeReader reader = new NbtLeReader(buf);
        for (int i = 0; i < paletteCount; i++) {
            reader.readCompound();
        }
    }

    // -- palette/NBT translation ------------------------------------------------------------

    private static BlockState paletteEntryToState(NbtMap entry) {
        String name = entry.getString("name");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Palette entry missing 'name'");
        }
        Map<String, Object> props;
        Object states = entry.get("states");
        if (states instanceof NbtMap statesMap) {
            // copy into a plain map so the BlockState isn't tied to NbtMap
            Map<String, Object> copy = new LinkedHashMap<>(statesMap.asMap());
            props = copy;
        } else {
            props = Map.of();
        }
        return new BlockState(name, props);
    }

    private static NbtMap stateToPaletteEntry(BlockState state) {
        NbtMap.Builder states = NbtMap.builder();
        for (Map.Entry<String, Object> e : state.properties().entrySet()) {
            states.put(e.getKey(), e.getValue());
        }
        return NbtMap.builder()
                .putString("name", state.name())
                .putCompound("states", states.build())
                .putInt("version", BLOCK_STATE_VERSION)
                .build();
    }

    // -- bit packing ------------------------------------------------------------------------

    /** Valid bits-per-block values from the Bedrock spec. */
    private static final int[] VALID_BPB = {0, 1, 2, 3, 4, 5, 6, 8, 16};

    private static boolean isValidBpb(int bpb) {
        for (int v : VALID_BPB) if (v == bpb) return true;
        return false;
    }

    /** Smallest bpb able to represent {@code paletteSize} distinct indices. */
    static int bitsPerBlockFor(int paletteSize) {
        if (paletteSize <= 1) return 1; // bpb=0 is reserved for "no data"; we always emit at least 1 bit
        int needed = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        for (int v : VALID_BPB) {
            if (v == 0) continue;
            if (v >= needed) return v;
        }
        throw new IllegalArgumentException("Palette too large: " + paletteSize);
    }
}
