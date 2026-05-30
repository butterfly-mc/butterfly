package net.butterfly.core.world;

import net.butterfly.api.world.BlockState;

import java.util.Arrays;
import java.util.Objects;

/**
 * Runtime sub-chunk: a 16x16x16 cube of blocks indexed into a per-sub-chunk palette.
 *
 * <p>For the MVP we represent each sub-chunk as a single block-storage layer (no second
 * waterlog/extra-data layer). The palette is a {@link BlockState} array; the {@link #indices}
 * array contains 4096 entries, one per local position, each indexing into the palette.
 *
 * <p>Local-coordinate layout matches Bedrock's on-disk packing: index = (x * 16 + z) * 16 + y,
 * i.e. Y is the fastest-varying axis.
 */
public final class SubChunk {

    /** Number of blocks along each axis. */
    public static final int SIZE = 16;
    /** Total number of blocks in a sub-chunk. */
    public static final int VOLUME = SIZE * SIZE * SIZE;

    private final int subY;
    private final BlockState[] palette;
    private final short[] indices;

    /**
     * Constructs a sub-chunk taking ownership of the supplied arrays. Callers must not mutate
     * them afterwards.
     */
    public SubChunk(int subY, BlockState[] palette, short[] indices) {
        if (palette == null || palette.length == 0) {
            throw new IllegalArgumentException("palette must be non-empty");
        }
        if (indices == null || indices.length != VOLUME) {
            throw new IllegalArgumentException("indices must be " + VOLUME + " entries");
        }
        this.subY = subY;
        this.palette = palette;
        this.indices = indices;
    }

    public int subY() {
        return subY;
    }

    /** Returns the palette as a defensive copy. */
    public BlockState[] palette() {
        return palette.clone();
    }

    /** Returns the underlying palette length without copying. */
    public int paletteSize() {
        return palette.length;
    }

    /** Returns a defensive copy of the index array. */
    public short[] indices() {
        return indices.clone();
    }

    /**
     * Returns the block at local coordinates ({@code 0..15} per axis).
     */
    public BlockState getBlock(int x, int y, int z) {
        checkLocal(x, y, z);
        return palette[indices[localIndex(x, y, z)] & 0xffff];
    }

    /**
     * Returns a new {@code SubChunk} with {@code state} placed at the given local coordinate.
     * The original instance is left untouched (copy-on-write).
     *
     * <p>If {@code state} already exists in the palette, the existing entry is reused;
     * otherwise the palette is extended by one entry.
     */
    public SubChunk withBlock(int x, int y, int z, BlockState state) {
        Objects.requireNonNull(state, "state");
        checkLocal(x, y, z);
        int idx = paletteIndexOf(state);
        BlockState[] newPalette;
        if (idx < 0) {
            newPalette = Arrays.copyOf(palette, palette.length + 1);
            idx = palette.length;
            newPalette[idx] = state;
        } else {
            newPalette = palette;
        }
        short[] newIndices = indices.clone();
        newIndices[localIndex(x, y, z)] = (short) idx;
        return new SubChunk(subY, newPalette, newIndices);
    }

    /** A sub-chunk filled entirely with {@code state}; palette of one. */
    public static SubChunk filledWith(int subY, BlockState state) {
        Objects.requireNonNull(state, "state");
        BlockState[] palette = new BlockState[]{state};
        short[] indices = new short[VOLUME]; // all zero
        return new SubChunk(subY, palette, indices);
    }

    /** Air-filled sub-chunk; convenience for empty world generation. */
    public static SubChunk empty(int subY) {
        return filledWith(subY, BlockState.of("minecraft:air"));
    }

    /** Computes the linear index for a local (x, y, z) using Bedrock's packing order. */
    public static int localIndex(int x, int y, int z) {
        return ((x & 0xf) << 8) | ((z & 0xf) << 4) | (y & 0xf);
    }

    private int paletteIndexOf(BlockState state) {
        for (int i = 0; i < palette.length; i++) {
            if (state.equals(palette[i])) return i;
        }
        return -1;
    }

    private static void checkLocal(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SIZE || y >= SIZE || z >= SIZE) {
            throw new IndexOutOfBoundsException("Sub-chunk local coord out of range: "
                    + x + "," + y + "," + z);
        }
    }
}
