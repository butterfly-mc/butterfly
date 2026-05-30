package net.butterfly.core.tick;

import net.butterfly.core.world.Chunk;
import net.butterfly.core.world.SubChunk;
import net.butterfly.core.world.WorldImpl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between a {@link WorldImpl} and {@link WorldSnapshot}. Holds the latest
 * published snapshot in a volatile field so any thread can read it without locking.
 *
 * <p>Wire-up: install {@link #publish(long, WorldImpl)} as part of the
 * {@link TickLoop} post-tick hook so a fresh snapshot is published once per tick on
 * the simulate thread.
 */
public final class WorldSnapshotPublisher {

    private volatile WorldSnapshot current;

    /**
     * Captures a fresh snapshot of {@code world} for tick {@code tick}. Must be called on
     * the simulate thread (typically from {@link TickLoop}'s post-tick hook).
     *
     * <p>Per chunk, the snapshot reads the volatile {@code subchunks} reference and
     * {@code version} once — since {@code SubChunk}s are immutable, the captured array
     * remains a valid view of that chunk at this tick even after the live chunk continues
     * to mutate.
     */
    public void publish(long tick, WorldImpl world) {
        Collection<Chunk> chunks = world.loadedChunks();
        Map<Long, WorldSnapshot.ChunkSnapshot> snap = new HashMap<>(chunks.size());
        for (Chunk chunk : chunks) {
            // subChunks() returns a defensive clone of the volatile array — safe to share.
            SubChunk[] subs = chunk.subChunks();
            long version = chunk.version();
            snap.put(WorldSnapshot.packChunkKey(chunk.chunkX(), chunk.chunkZ()),
                    new WorldSnapshot.ChunkSnapshot(chunk.chunkX(), chunk.chunkZ(), subs, version));
        }
        this.current = new WorldSnapshot(tick, snap);
    }

    /**
     * @return the latest published snapshot, or an empty placeholder if no snapshot has
     *         been published yet
     */
    public WorldSnapshot snapshot() {
        WorldSnapshot snap = current;
        return snap != null ? snap : WorldSnapshot.empty();
    }
}
