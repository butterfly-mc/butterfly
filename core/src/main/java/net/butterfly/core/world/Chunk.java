package net.butterfly.core.world;

import net.butterfly.api.world.BlockState;
import net.butterfly.api.world.World;

import java.util.Objects;

/**
 * Concrete chunk implementation backed by a fixed-height stack of {@link SubChunk}s.
 *
 * <p>The world height range is {@link World#MIN_Y} to {@link World#MAX_Y}, sliced into 24
 * sub-chunks (subY = {@code -4..19} mapped to indices {@code 0..23}).
 *
 * <p>Chunks are mutated by replacing whole sub-chunks via copy-on-write; reads do not lock
 * but observe a stable snapshot by reading the volatile {@link #subchunks} reference once.
 */
public final class Chunk implements net.butterfly.api.world.Chunk {

    /** Lowest sub-chunk index used (subY for index 0). */
    public static final int MIN_SUB_Y = World.MIN_Y / SubChunk.SIZE; // -4
    /** Number of sub-chunks per chunk column. */
    public static final int SUBCHUNK_COUNT = (World.MAX_Y - World.MIN_Y) / SubChunk.SIZE; // 24

    private final int chunkX;
    private final int chunkZ;
    private final int dim;
    private final WorldImpl world;

    private volatile SubChunk[] subchunks;
    private volatile long version;
    private volatile boolean dirty;

    public Chunk(WorldImpl world, int chunkX, int chunkZ, int dim, SubChunk[] subchunks) {
        if (subchunks.length != SUBCHUNK_COUNT) {
            throw new IllegalArgumentException(
                    "subchunks must have " + SUBCHUNK_COUNT + " entries, got " + subchunks.length);
        }
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dim = dim;
        this.subchunks = subchunks.clone();
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public WorldImpl world() {
        return world;
    }

    /** Dimension id (0 overworld, 1 nether, 2 end). */
    public int dim() {
        return dim;
    }

    /** Monotonically increasing version counter; bumped each time {@link #setBlock} succeeds. */
    public long version() {
        return version;
    }

    /** True if this chunk has unsaved changes since its last save (or initial load). */
    public boolean isDirty() {
        return dirty;
    }

    /** Marks this chunk as clean (call after a successful save). */
    public void markClean() {
        this.dirty = false;
    }

    /** Returns the sub-chunk at array slot {@code idx}; never {@code null}. */
    public SubChunk subChunk(int idx) {
        return subchunks[idx];
    }

    /** Returns a snapshot copy of the sub-chunk array. */
    public SubChunk[] subChunks() {
        return subchunks.clone();
    }

    /**
     * API-level read using local X/Z (0..15) and absolute Y. Returns air if Y is out of range.
     */
    @Override
    public BlockState getBlock(int localX, int y, int localZ) {
        if (y < World.MIN_Y || y >= World.MAX_Y) {
            return BlockState.of("minecraft:air");
        }
        int subIdx = (y - World.MIN_Y) >> 4;
        int localY = y & 0xf;
        return subchunks[subIdx].getBlock(localX & 0xf, localY, localZ & 0xf);
    }

    /**
     * Replaces the block at local X/Z, absolute Y with {@code state}; bumps {@link #version}
     * and marks the chunk dirty.
     */
    public void setBlock(int localX, int y, int localZ, BlockState state) {
        Objects.requireNonNull(state, "state");
        if (y < World.MIN_Y || y >= World.MAX_Y) {
            throw new IndexOutOfBoundsException("y out of range: " + y);
        }
        int subIdx = (y - World.MIN_Y) >> 4;
        int localY = y & 0xf;
        SubChunk current = subchunks[subIdx];
        SubChunk replaced = current.withBlock(localX & 0xf, localY, localZ & 0xf, state);
        SubChunk[] copy = subchunks.clone();
        copy[subIdx] = replaced;
        this.subchunks = copy;
        this.version = version + 1;
        this.dirty = true;
    }
}
