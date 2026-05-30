package net.butterfly.core.tick;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerImplTest {

    private TickLoop loop;
    private ForkJoinPool pool;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.stop();
        if (pool != null) pool.shutdownNow();
    }

    @Test
    void runAsync_runsOnDifferentThread() throws Exception {
        loop = new TickLoop(100);
        pool = new ForkJoinPool(2);
        SchedulerImpl scheduler = new SchedulerImpl(loop, pool);
        loop.start();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ranOn = new AtomicReference<>();
        scheduler.runAsync(() -> {
            ranOn.set(Thread.currentThread().getName());
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotEquals("butterfly-tick", ranOn.get());
    }

    @Test
    void runRepeating_firesAtExpectedCadence() throws Exception {
        loop = new TickLoop(200); // 5ms per tick — keeps the test well under a second
        pool = new ForkJoinPool(1);
        SchedulerImpl scheduler = new SchedulerImpl(loop, pool);
        AtomicInteger fires = new AtomicInteger();
        loop.start();

        // Schedule from on-tick-thread so cadence is anchored deterministically.
        AtomicReference<Long> startRef = new AtomicReference<>();
        CountDownLatch scheduled = new CountDownLatch(1);
        loop.submit(() -> {
            startRef.set(loop.currentTick());
            scheduler.runRepeating(fires::incrementAndGet, 2, 3);
            scheduled.countDown();
        });
        assertTrue(scheduled.await(2, TimeUnit.SECONDS));

        long startTick = startRef.get();
        // Run for ~12 ticks past startTick so we cover at least 4 fires (at +2, +5, +8, +11).
        long deadline = startTick + 13;
        while (loop.currentTick() < deadline) Thread.sleep(2L);

        int got = fires.get();
        // Expected ~4 fires at startTick+{2,5,8,11}; allow 3..5 to absorb scheduler jitter.
        assertTrue(got >= 3 && got <= 5,
                "expected 3..5 fires after ~12 ticks, got " + got);
    }
}
