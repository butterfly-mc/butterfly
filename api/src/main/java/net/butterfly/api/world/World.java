package net.butterfly.api.world;

/**
 * A loaded world (dimension) on the server.
 *
 * <p>Worlds are the entry point for reading and mutating block data and for managing
 * chunk loading. Most mutating operations must be performed on the server's simulate
 * thread; see individual method JavaDoc for threading requirements.
 */
public interface World {

    /**
     * Minimum (inclusive) world Y coordinate. Below this, no blocks exist.
     */
    int MIN_Y = -64;

    /**
     * Maximum (exclusive) world Y coordinate. Block positions are valid for
     * {@code MIN_Y <= y < MAX_Y}.
     */
    int MAX_Y = 320;

    /**
     * Width and depth of a chunk in blocks.
     */
    int CHUNK_SIZE = 16;

    /**
     * @return the world's identifier name (e.g. {@code "overworld"}, {@code "nether"});
     *         never {@code null}
     */
    String name();

    /**
     * Returns the block state at an absolute world position.
     *
     * <p>If the chunk containing the position is not loaded, implementations should
     * return a sensible default (typically {@code minecraft:air}) rather than block
     * the caller. To force-load a chunk first, call {@link #loadChunk(int, int)}.
     *
     * @param x absolute world X
     * @param y absolute world Y, in the range {@code [MIN_Y, MAX_Y)}
     * @param z absolute world Z
     * @return the {@link BlockState} at that position; never {@code null}
     */
    BlockState getBlock(int x, int y, int z);

    /**
     * Sets the block state at an absolute world position.
     *
     * <p><b>Threading:</b> must be called on the simulate thread; the implementation
     * may throw {@link IllegalStateException} if called from another thread.
     *
     * <p>The change is applied to the loaded chunk and broadcast to nearby viewers.
     * If the chunk is not loaded, implementations may either load it synchronously,
     * queue the change, or throw — see implementation notes.
     *
     * @param x     absolute world X
     * @param y     absolute world Y, in the range {@code [MIN_Y, MAX_Y)}
     * @param z     absolute world Z
     * @param state the new {@link BlockState}; must not be {@code null}
     * @throws IllegalStateException if called off the simulate thread
     */
    void setBlock(int x, int y, int z, BlockState state);

    /**
     * Returns the {@link Chunk} at the given chunk coordinates if it is currently loaded.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return the loaded {@link Chunk}, or {@code null} if the chunk is not loaded
     */
    Chunk getChunk(int chunkX, int chunkZ);

    /**
     * Reports whether the chunk at the given coordinates is currently loaded.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code true} if the chunk is loaded and accessible via {@link #getChunk(int, int)}
     */
    boolean isChunkLoaded(int chunkX, int chunkZ);

    /**
     * Requests that the chunk at the given coordinates be loaded.
     *
     * <p>This is an asynchronous hint: the chunk may not be loaded yet when this method
     * returns. Poll {@link #isChunkLoaded(int, int)} or wait for a chunk-load event to
     * confirm completion.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    void loadChunk(int chunkX, int chunkZ);
}
