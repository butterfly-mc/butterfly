package net.butterfly.api.async;

import net.butterfly.api.world.BlockState;

/**
 * Read-only snapshot of the world at a given tick. Safe to call from any
 * thread. To write, submit a Runnable via {@link Scheduler#runOnMain(Runnable)}.
 */
public interface WorldView {

    /** Server tick at which this snapshot was taken. */
    long tickAtSnapshot();

    /**
     * Look up a block by world coordinates.
     *
     * @return the block state, or {@code null} / air if the chunk is not loaded
     */
    BlockState getBlock(int x, int y, int z);

    /**
     * @return {@code true} if the chunk at {@code (chunkX, chunkZ)} was loaded
     *         when the snapshot was taken
     */
    boolean isChunkLoaded(int chunkX, int chunkZ);
}
