package net.butterfly.core.tick;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickLoopTest {

    private TickLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.stop();
    }

    @Test
    void runsAtTargetTps() throws Exception {
        loop = new TickLoop(100); // 10ms per tick
        loop.start();
        Thread.sleep(150L);
        loop.stop();
        long ticks = loop.currentTick();
        assertTrue(ticks >= 8, "expected >= 8 ticks in ~150ms at 100tps, got " + ticks);
    }

    @Test
    void submitFromOffThread_runsOnTickThread() throws Exception {
        loop = new TickLoop(100);
        loop.start();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean onTickThread = new AtomicBoolean();
        loop.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            onTickThread.set(loop.isOnTickThread());
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "task did not run within 2s");
        assertEquals("butterfly-tick", threadName.get());
        assertTrue(onTickThread.get());
    }

    @Test
    void runOnMainFromTickThread_runsInline() throws Exception {
        loop = new TickLoop(100);
        SchedulerImpl scheduler = new SchedulerImpl(loop, java.util.concurrent.ForkJoinPool.commonPool());
        loop.start();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger orderInner = new AtomicInteger(-1);
        AtomicInteger orderOuter = new AtomicInteger(-1);
        AtomicInteger seq = new AtomicInteger(0);
        loop.submit(() -> {
            // Already on tick thread — runOnMain should run inline (synchronously).
            scheduler.runOnMain(() -> orderInner.set(seq.getAndIncrement()));
            orderOuter.set(seq.getAndIncrement());
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        // Inner ran before outer set its order → inline execution.
        assertEquals(0, orderInner.get());
        assertEquals(1, orderOuter.get());
    }

    @Test
    void runLater_firesAtCorrectTick() throws Exception {
        loop = new TickLoop(100); // 10ms per tick
        loop.start();
        // Schedule from on-thread so currentTick sample and scheduleDelayed are atomic w.r.t. ticks.
        AtomicReference<Long> firedAt = new AtomicReference<>();
        AtomicReference<Long> startTick = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        loop.submit(() -> {
            startTick.set(loop.currentTick());
            loop.scheduleDelayed(() -> {
                firedAt.set(loop.currentTick());
                fired.countDown();
            }, 5);
        });
        assertTrue(fired.await(2, TimeUnit.SECONDS));
        long delta = firedAt.get() - startTick.get();
        assertEquals(5L, delta, "delayed task should fire exactly 5 ticks later");
    }

    @Test
    void exceptionInTask_doesNotKillLoop() throws Exception {
        loop = new TickLoop(100);
        loop.start();
        AtomicBoolean afterRan = new AtomicBoolean();
        CountDownLatch crashed = new CountDownLatch(1);
        CountDownLatch survived = new CountDownLatch(1);
        loop.submit(() -> {
            crashed.countDown();
            throw new RuntimeException("boom");
        });
        loop.submit(() -> {
            afterRan.set(true);
            survived.countDown();
        });
        assertTrue(crashed.await(2, TimeUnit.SECONDS));
        assertTrue(survived.await(2, TimeUnit.SECONDS));
        assertTrue(afterRan.get());
        assertTrue(loop.isRunning());
        assertNotNull(loop.thread());
        assertTrue(loop.thread().isAlive());
    }

    @Test
    void runOnMainFromOffThread_runsOnDifferentThread() throws Exception {
        loop = new TickLoop(100);
        SchedulerImpl scheduler = new SchedulerImpl(loop, java.util.concurrent.ForkJoinPool.commonPool());
        loop.start();
        AtomicReference<String> ranOn = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.runOnMain(() -> {
            ranOn.set(Thread.currentThread().getName());
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("butterfly-tick", ranOn.get());
        assertNotEquals(Thread.currentThread().getName(), ranOn.get());
    }
}
