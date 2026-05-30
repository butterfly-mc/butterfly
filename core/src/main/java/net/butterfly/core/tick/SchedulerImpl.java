package net.butterfly.core.tick;

import net.butterfly.api.async.Scheduler;

import java.util.Objects;
import java.util.concurrent.ForkJoinPool;

/**
 * Production {@link Scheduler} backed by a {@link TickLoop} and a shared
 * {@link ForkJoinPool}.
 *
 * <ul>
 *   <li>{@link #runOnMain(Runnable)} — runs inline if already on the tick thread,
 *       otherwise queues into the loop's task queue.</li>
 *   <li>{@link #runAsync(Runnable)} — dispatches to the supplied {@link ForkJoinPool}.</li>
 *   <li>{@link #runLater(Runnable, int)} — schedules at {@code currentTick + delayTicks}.</li>
 *   <li>{@link #runRepeating(Runnable, int, int)} — registers a repeating task.</li>
 * </ul>
 */
public final class SchedulerImpl implements Scheduler {

    private final TickLoop tickLoop;
    private final ForkJoinPool asyncPool;

    public SchedulerImpl(TickLoop tickLoop, ForkJoinPool asyncPool) {
        this.tickLoop = Objects.requireNonNull(tickLoop, "tickLoop");
        this.asyncPool = Objects.requireNonNull(asyncPool, "asyncPool");
    }

    @Override
    public void runOnMain(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (tickLoop.isOnTickThread()) {
            task.run();
        } else {
            tickLoop.submit(task);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        asyncPool.execute(task);
    }

    @Override
    public void runLater(Runnable task, int delayTicks) {
        tickLoop.scheduleDelayed(task, delayTicks);
    }

    @Override
    public void runRepeating(Runnable task, int initialDelayTicks, int periodTicks) {
        tickLoop.scheduleRepeating(task, initialDelayTicks, periodTicks);
    }
}
