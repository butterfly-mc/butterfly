package net.butterfly.core.world;

import net.butterfly.api.world.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorldImplTest {

    private static final BlockState AIR = BlockState.of("minecraft:air");
    private static final BlockState STONE = BlockState.of("minecraft:stone");

    @Test
    void emptyWorld_returnsAir(@TempDir Path tmp) throws IOException {
        try (LevelDb db = LevelDb.open(tmp.resolve("db"))) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            assertEquals(AIR, world.getBlock(0, 64, 0));
            assertFalse(world.isChunkLoaded(0, 0));
        }
    }

    @Test
    void setBlock_thenGetBlock_returnsState(@TempDir Path tmp) throws IOException {
        try (LevelDb db = LevelDb.open(tmp.resolve("db"))) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            world.setBlock(3, 64, 7, STONE);
            assertEquals(STONE, world.getBlock(3, 64, 7));
            assertEquals(AIR, world.getBlock(3, 65, 7));
            assertNotNull(world.getChunk(0, 0));
        }
    }

    @Test
    void setBlock_persistsThroughUnloadAndReload(@TempDir Path tmp) throws IOException {
        Path dbDir = tmp.resolve("db");
        try (LevelDb db = LevelDb.open(dbDir)) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            world.setBlock(1, 70, 2, STONE);
            world.unloadChunk(0, 0);
            assertFalse(world.isChunkLoaded(0, 0));
        }
        try (LevelDb db = LevelDb.open(dbDir)) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            world.loadChunk(0, 0);
            assertEquals(STONE, world.getBlock(1, 70, 2));
        }
    }

    @Test
    void setBlock_offSimulateThread_throws(@TempDir Path tmp) throws Exception {
        try (LevelDb db = LevelDb.open(tmp.resolve("db"))) {
            WorldImpl world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    world.setBlock(0, 64, 0, STONE);
                } catch (Throwable t) {
                    caught.set(t);
                }
            }, "other");
            other.start();
            other.join();
            assertInstanceOf(IllegalStateException.class, caught.get());
        }
    }
}
