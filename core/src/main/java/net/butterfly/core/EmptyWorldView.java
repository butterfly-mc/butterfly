package net.butterfly.core;

import net.butterfly.api.async.WorldView;
import net.butterfly.api.world.BlockState;

/** Placeholder WorldView used before the first tick has published a snapshot. */
final class EmptyWorldView implements WorldView {
    static final EmptyWorldView INSTANCE = new EmptyWorldView();
    private static final BlockState AIR = BlockState.of("minecraft:air");

    private EmptyWorldView() {}

    @Override public long tickAtSnapshot() { return 0L; }
    @Override public BlockState getBlock(int x, int y, int z) { return AIR; }
    @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return false; }
}
