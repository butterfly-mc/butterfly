package net.butterfly.core.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Builds and parses LevelDB keys for chunk-scoped data in a Bedrock world.
 *
 * <p>Chunk-keyed entries use the layout
 * <pre>
 *   int32 LE chunkX
 *   int32 LE chunkZ
 *   int32 LE dimension     (omitted for the overworld, dim == 0)
 *   byte   tag
 *   int32 LE subY          (only for sub-chunk keys, tag 0x2c)
 * </pre>
 * Entity keys use the prefix {@code "~~"} followed by 16 bytes of a big-endian UUID.
 */
public final class ChunkKey {

    /** Sub-chunk prefix tag. */
    public static final byte TAG_SUBCHUNK = 0x2c;
    /** 3D biomes/heightmap tag. */
    public static final byte TAG_BIOMES_3D = 0x39;
    /** Block-entity (tile-entity) NBT list tag. */
    public static final byte TAG_BLOCK_ENTITIES = 0x31;
    /** Finalized-state marker (generation/population stage). */
    public static final byte TAG_FINALIZED_STATE = 0x36;

    private static final int LEN_BASE = 4 + 4 + 1; // cx + cz + tag
    private static final int LEN_SUBCHUNK_NO_DIM = 4 + 4 + 1 + 4; // 13
    private static final int LEN_SUBCHUNK_WITH_DIM = 4 + 4 + 4 + 1 + 4; // 17

    private ChunkKey() {}

    /**
     * Builds a sub-chunk key for the given chunk and vertical sub-chunk index.
     *
     * @param cx   chunk X
     * @param cz   chunk Z
     * @param dim  dimension id (0=overworld, 1=nether, 2=end). When 0, the dimension field
     *             is omitted producing a 13-byte key.
     * @param subY signed sub-chunk Y, written as int32 LE
     */
    public static byte[] subChunk(int cx, int cz, int dim, int subY) {
        boolean hasDim = dim != 0;
        int len = hasDim ? LEN_SUBCHUNK_WITH_DIM : LEN_SUBCHUNK_NO_DIM;
        ByteBuffer buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cx);
        buf.putInt(cz);
        if (hasDim) buf.putInt(dim);
        buf.put(TAG_SUBCHUNK);
        buf.putInt(subY);
        return buf.array();
    }

    /** 3D biomes column key. */
    public static byte[] biomes3d(int cx, int cz, int dim) {
        return chunkScopedKey(cx, cz, dim, TAG_BIOMES_3D);
    }

    /** Block-entity (tile entity) NBT list key. */
    public static byte[] blockEntities(int cx, int cz, int dim) {
        return chunkScopedKey(cx, cz, dim, TAG_BLOCK_ENTITIES);
    }

    /** Finalized-state (generation stage) key. */
    public static byte[] finalizedState(int cx, int cz, int dim) {
        return chunkScopedKey(cx, cz, dim, TAG_FINALIZED_STATE);
    }

    /**
     * Builds an entity key. Entities are stored under {@code "~~" + 16 bytes UUID big-endian}.
     */
    public static byte[] entity(UUID uuid) {
        byte[] out = new byte[2 + 16];
        out[0] = '~';
        out[1] = '~';
        ByteBuffer.wrap(out, 2, 16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return out;
    }

    private static byte[] chunkScopedKey(int cx, int cz, int dim, byte tag) {
        boolean hasDim = dim != 0;
        int len = LEN_BASE + (hasDim ? 4 : 0);
        ByteBuffer buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cx);
        buf.putInt(cz);
        if (hasDim) buf.putInt(dim);
        buf.put(tag);
        return buf.array();
    }

    /**
     * Parsed location of a sub-chunk key.
     *
     * @param cx  chunk X
     * @param cz  chunk Z
     * @param dim dimension (0 if dimension field was absent)
     */
    public record ChunkLocation(int cx, int cz, int dim) {}

    /**
     * Decodes a sub-chunk key (tag {@link #TAG_SUBCHUNK}) into its chunk-coordinate
     * components.
     *
     * @throws IllegalArgumentException if {@code key} has an unexpected length or the tag byte
     *                                  is not {@link #TAG_SUBCHUNK}.
     */
    public static ChunkLocation parseSubChunkKey(byte[] key) {
        if (key == null
                || (key.length != LEN_SUBCHUNK_NO_DIM && key.length != LEN_SUBCHUNK_WITH_DIM)) {
            throw new IllegalArgumentException(
                    "Not a sub-chunk key: length=" + (key == null ? -1 : key.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
        int cx = buf.getInt();
        int cz = buf.getInt();
        int dim = 0;
        if (key.length == LEN_SUBCHUNK_WITH_DIM) {
            dim = buf.getInt();
        }
        byte tag = buf.get();
        if (tag != TAG_SUBCHUNK) {
            throw new IllegalArgumentException(
                    "Not a sub-chunk key: tag=0x" + Integer.toHexString(tag & 0xff));
        }
        return new ChunkLocation(cx, cz, dim);
    }
}
