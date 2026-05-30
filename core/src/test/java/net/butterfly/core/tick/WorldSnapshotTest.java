package net.butterfly.core.tick;

import net.butterfly.api.world.BlockState;
import net.butterfly.core.world.LevelDb;
import net.butterfly.core.world.WorldImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSnapshotTest {

    private static final BlockState AIR = BlockState.of("minecraft:air");
    private static final BlockState STONE = BlockState.of("minecraft:stone");
    private static final BlockState DIAMOND = BlockState.of("minecraft:diamond_block");

    @Test
    void publishedSnapshot_reflectsBlockState(@TempDir Path tmp) throws IOException {
        try (LevelDb db = LevelDb.open(tmp.resolve("db"))) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            world.setBlock(3, 64, 7, STONE);

            WorldSnapshotPublisher publisher = new WorldSnapshotPublisher();
            publisher.publish(42L, world);

            WorldSnapshot snap = publisher.snapshot();
            assertEquals(42L, snap.tickAtSnapshot());
            assertTrue(snap.isChunkLoaded(0, 0));
            assertEquals(STONE, snap.getBlock(3, 64, 7));
            assertEquals(AIR, snap.getBlock(3, 65, 7));
        }
    }

    @Test
    void snapshot_isolatesFromSubsequentMutations(@TempDir Path tmp) throws IOException {
        try (LevelDb db = LevelDb.open(tmp.resolve("db"))) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            world.setBlock(0, 64, 0, STONE);

            WorldSnapshotPublisher publisher = new WorldSnapshotPublisher();
            publisher.publish(1L, world);
            WorldSnapshot first = publisher.snapshot();

            // Mutate after snapshot — first snapshot must still see STONE.
            world.setBlock(0, 64, 0, DIAMOND);
            assertEquals(STONE, first.getBlock(0, 64, 0));
            assertEquals(1L, first.tickAtSnapshot());

            // Live world reflects the new state.
            assertEquals(DIAMOND, world.getBlock(0, 64, 0));

            // Until we publish again, the publisher still hands out the OLD snapshot.
            WorldSnapshot stillFirst = publisher.snapshot();
            assertEquals(STONE, stillFirst.getBlock(0, 64, 0));

            // After re-publish, new readers see the updated state.
            publisher.publish(2L, world);
            WorldSnapshot second = publisher.snapshot();
            assertEquals(DIAMOND, second.getBlock(0, 64, 0));
            assertEquals(2L, second.tickAtSnapshot());

            // The original captured snapshot is still isolated.
            assertEquals(STONE, first.getBlock(0, 64, 0));
        }
    }

    @Test
    void snapshotBeforePublish_returnsEmpty() {
        WorldSnapshotPublisher publisher = new WorldSnapshotPublisher();
        WorldSnapshot snap = publisher.snapshot();
        assertEquals(0L, snap.tickAtSnapshot());
        assertEquals(AIR, snap.getBlock(0, 64, 0));
        assertTrue(!snap.isChunkLoaded(0, 0));
    }
}
