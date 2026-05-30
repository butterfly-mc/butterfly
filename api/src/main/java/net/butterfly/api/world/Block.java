package net.butterfly.api.world;

/**
 * A read-only handle to a block at a specific position in a {@link World}.
 *
 * <p>{@code Block} is a value handle — it does not mutate world state. To change a block,
 * call {@link World#setBlock(int, int, int, BlockState)} on the owning world.
 *
 * <p>The data returned by accessor methods reflects the world state at the moment of the
 * underlying read. Implementations may return cached values; callers needing fresh data
 * should re-fetch via {@link World#getBlock(int, int, int)}.
 */
public interface Block {

    /**
     * @return the absolute world X coordinate of this block
     */
    int x();

    /**
     * @return the absolute world Y coordinate of this block
     */
    int y();

    /**
     * @return the absolute world Z coordinate of this block
     */
    int z();

    /**
     * @return the {@link BlockState} at this position; never {@code null}
     */
    BlockState state();

    /**
     * @return the {@link World} that contains this block; never {@code null}
     */
    World world();
}
