package net.butterfly.api.world;

/**
 * A 16x16 column of blocks in a {@link World}, identified by chunk coordinates.
 *
 * <p>Chunk coordinates are absolute block coordinates divided by {@link World#CHUNK_SIZE}.
 * For example, the chunk containing block {@code (35, 0, -2)} is chunk
 * {@code (chunkX=2, chunkZ=-1)}.
 *
 * <p>Local coordinates passed to {@link #getBlock(int, int, int)} are within the chunk
 * (0..15 inclusive on the X and Z axes); the Y coordinate is the absolute world Y.
 */
public interface Chunk {

    /**
     * @return the chunk X coordinate (absolute block X divided by {@link World#CHUNK_SIZE})
     */
    int chunkX();

    /**
     * @return the chunk Z coordinate (absolute block Z divided by {@link World#CHUNK_SIZE})
     */
    int chunkZ();

    /**
     * @return the {@link World} that owns this chunk; never {@code null}
     */
    World world();

    /**
     * Returns the block state at the given local position within this chunk.
     *
     * @param localX X within the chunk, in the range {@code [0, 15]}
     * @param y      absolute world Y, in the range {@code [World.MIN_Y, World.MAX_Y)}
     * @param localZ Z within the chunk, in the range {@code [0, 15]}
     * @return the {@link BlockState} at that position; never {@code null}
     * @throws IndexOutOfBoundsException if {@code localX}, {@code localZ}, or {@code y} are out of range
     */
    BlockState getBlock(int localX, int y, int localZ);
}
