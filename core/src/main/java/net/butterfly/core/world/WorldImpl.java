package net.butterfly.core.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.butterfly.api.world.BlockState;
import net.butterfly.api.world.World;

import java.io.IOException;
import java.util.Objects;

/**
 * In-memory world backed by a Bedrock-format {@link LevelDb}.
 *
 * <p>Read access ({@link #getBlock(int, int, int)}) is non-blocking: chunks that aren't yet
 * loaded resolve to {@code minecraft:air} so that off-thread observers (network senders,
 * pathfinders) can read freely without triggering disk I/O.
 *
 * <p>Mutations ({@link #setBlock(int, int, int, BlockState)}) must run on the simulate
 * thread supplied at construction; the world will load the affected chunk synchronously
 * if it is not already in memory.
 */
public final class WorldImpl implements World {

    private static final BlockState AIR = BlockState.of("minecraft:air");

    private final String name;
    private final LevelDb db;
    private final int dim;
    private final Thread simulateThread;
    private final Long2ObjectMap<Chunk> loadedChunks = new Long2ObjectOpenHashMap<>();

    /**
     * @param name           identifier for this world (e.g. {@code "overworld"})
     * @param db             the LevelDB instance backing this world
     * @param dim            dimension id (0 overworld, 1 nether, 2 end)
     * @param simulateThread the thread on which mutations may be performed; if {@code null},
     *                       defaults to the constructing thread
     */
    public WorldImpl(String name, LevelDb db, int dim, Thread simulateThread) {
        this.name = Objects.requireNonNull(name, "name");
        this.db = Objects.requireNonNull(db, "db");
        this.dim = dim;
        this.simulateThread = simulateThread != null ? simulateThread : Thread.currentThread();
    }

    @Override
    public String name() {
        return name;
    }

    /** Dimension id (0 overworld, 1 nether, 2 end). */
    public int dim() {
        return dim;
    }

    /** The underlying LevelDB handle. */
    public LevelDb db() {
        return db;
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        if (y < MIN_Y || y >= MAX_Y) return AIR;
        int cx = x >> 4;
        int cz = z >> 4;
        Chunk chunk;
        synchronized (loadedChunks) {
            chunk = loadedChunks.get(packChunkKey(cx, cz));
        }
        if (chunk == null) return AIR;
        return chunk.getBlock(x & 0xf, y, z & 0xf);
    }

    @Override
    public void setBlock(int x, int y, int z, BlockState state) {
        Objects.requireNonNull(state, "state");
        if (Thread.currentThread() != simulateThread) {
            throw new IllegalStateException(
                    "World.setBlock must be called on the simulate thread (was '"
                            + Thread.currentThread().getName() + "')");
        }
        if (y < MIN_Y || y >= MAX_Y) {
            throw new IndexOutOfBoundsException("y out of range: " + y);
        }
        int cx = x >> 4;
        int cz = z >> 4;
        Chunk chunk = ensureLoaded(cx, cz);
        chunk.setBlock(x & 0xf, y, z & 0xf, state);
    }

    @Override
    public Chunk getChunk(int chunkX, int chunkZ) {
        synchronized (loadedChunks) {
            return loadedChunks.get(packChunkKey(chunkX, chunkZ));
        }
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        synchronized (loadedChunks) {
            return loadedChunks.containsKey(packChunkKey(chunkX, chunkZ));
        }
    }

    /**
     * Loads the chunk at {@code (chunkX, chunkZ)} synchronously if it isn't already loaded.
     * Returns the resulting in-memory chunk.
     */
    @Override
    public void loadChunk(int chunkX, int chunkZ) {
        ensureLoaded(chunkX, chunkZ);
    }

    /**
     * Saves the chunk to LevelDB if dirty, then evicts it from the loaded map. Silently
     * ignores requests for chunks that are not loaded.
     */
    public void unloadChunk(int chunkX, int chunkZ) {
        Chunk chunk;
        synchronized (loadedChunks) {
            chunk = loadedChunks.remove(packChunkKey(chunkX, chunkZ));
        }
        if (chunk == null) return;
        if (chunk.isDirty()) {
            saveChunk(chunk);
        }
    }

    private Chunk ensureLoaded(int cx, int cz) {
        long key = packChunkKey(cx, cz);
        synchronized (loadedChunks) {
            Chunk existing = loadedChunks.get(key);
            if (existing != null) return existing;
            Chunk loaded = readChunk(cx, cz);
            loadedChunks.put(key, loaded);
            return loaded;
        }
    }

    private Chunk readChunk(int cx, int cz) {
        SubChunk[] subs = new SubChunk[Chunk.SUBCHUNK_COUNT];
        for (int i = 0; i < Chunk.SUBCHUNK_COUNT; i++) {
            int subY = Chunk.MIN_SUB_Y + i;
            byte[] key = ChunkKey.subChunk(cx, cz, dim, subY);
            byte[] value = db.get(key);
            if (value == null) {
                subs[i] = SubChunk.empty(subY);
            } else {
                try {
                    subs[i] = ChunkCodec.decodeSubChunk((byte) subY, value);
                } catch (UnsupportedOperationException uoe) {
                    // Unknown version: treat as empty to keep the world readable.
                    subs[i] = SubChunk.empty(subY);
                }
            }
        }
        return new Chunk(this, cx, cz, dim, subs);
    }

    private void saveChunk(Chunk chunk) {
        SubChunk[] subs = chunk.subChunks();
        for (int i = 0; i < subs.length; i++) {
            int subY = Chunk.MIN_SUB_Y + i;
            byte[] key = ChunkKey.subChunk(chunk.chunkX(), chunk.chunkZ(), dim, subY);
            byte[] value = ChunkCodec.encodeSubChunk(subs[i]);
            db.put(key, value);
        }
        chunk.markClean();
    }

    /** Convenience: persist all dirty chunks without unloading them. */
    public void flush() {
        synchronized (loadedChunks) {
            for (Chunk chunk : loadedChunks.values()) {
                if (chunk.isDirty()) saveChunk(chunk);
            }
        }
    }

    /** Closes the underlying database after flushing dirty chunks. */
    public void close() throws IOException {
        flush();
        db.close();
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx & 0xffffffffL) << 32 | ((long) cz & 0xffffffffL);
    }
}
