package net.butterfly.core.tick;

import net.butterfly.api.async.WorldView;
import net.butterfly.api.world.BlockState;
import net.butterfly.api.world.World;
import net.butterfly.core.world.Chunk;
import net.butterfly.core.world.SubChunk;

import java.util.Map;

/**
 * Immutable, thread-safe snapshot of a world at one tick. Built by
 * {@link WorldSnapshotPublisher#publish(long, net.butterfly.core.world.WorldImpl)}.
 *
 * <p>{@link SubChunk} is itself immutable, so the snapshot can share refs with the
 * live chunks without locking — by the time the consumer reads, the chunk's
 * {@code subchunks} array reference may have moved on, but the snapshot still holds
 * the old (consistent) state.
 */
public final class WorldSnapshot implements WorldView {

    private static final BlockState AIR = BlockState.of("minecraft:air");
    /** Lowest sub-chunk index used (subY for index 0); duplicated from {@link Chunk}. */
    private static final int MIN_SUB_Y = Chunk.MIN_SUB_Y;

    private final long tick;
    private final Map<Long, ChunkSnapshot> chunks;

    public WorldSnapshot(long tick, Map<Long, ChunkSnapshot> chunks) {
        this.tick = tick;
        this.chunks = Map.copyOf(chunks);
    }

    /** Empty snapshot (no chunks, tick 0); useful as a default before the first publish. */
    public static WorldSnapshot empty() {
        return new WorldSnapshot(0L, Map.of());
    }

    @Override
    public long tickAtSnapshot() {
        return tick;
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        if (y < World.MIN_Y || y >= World.MAX_Y) return AIR;
        int cx = x >> 4;
        int cz = z >> 4;
        ChunkSnapshot cs = chunks.get(packChunkKey(cx, cz));
        if (cs == null) return AIR;
        int subIdx = (y - World.MIN_Y) >> 4;
        SubChunk[] subs = cs.subchunks();
        if (subIdx < 0 || subIdx >= subs.length) return AIR;
        SubChunk sub = subs[subIdx];
        if (sub == null) return AIR;
        return sub.getBlock(x & 0xf, y & 0xf, z & 0xf);
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return chunks.containsKey(packChunkKey(chunkX, chunkZ));
    }

    static long packChunkKey(int cx, int cz) {
        return ((long) cx & 0xffffffffL) << 32 | ((long) cz & 0xffffffffL);
    }

    /**
     * Snapshot of a single chunk: its coordinates, an immutable view of its sub-chunk
     * stack at the moment of capture, and the version at capture (for downstream cache
     * invalidation).
     */
    public record ChunkSnapshot(int chunkX, int chunkZ, SubChunk[] subchunks, long version) {
        public ChunkSnapshot {
            if (subchunks == null) throw new NullPointerException("subchunks");
        }
    }
}
