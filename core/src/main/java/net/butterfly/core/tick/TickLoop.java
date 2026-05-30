package net.butterfly.core.tick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heart of the server tick. Runs a single dedicated thread that, once per tick:
 * <ol>
 *   <li>invokes the optional {@code preTickHook} (e.g. drain decoded packets),</li>
 *   <li>drains any tasks queued via {@link #submit(Runnable)},</li>
 *   <li>fires due entries from {@code delayedTasks} and {@code repeatingTasks},</li>
 *   <li>invokes the optional {@code postTickHook} (e.g. encode + publish snapshot),</li>
 *   <li>sleeps until the next tick boundary.</li>
 * </ol>
 *
 * <p>The loop targets {@code targetTps} ticks per second (default 20). When a tick exceeds
 * 50&nbsp;ms the loop logs a warning but never crashes; user-submitted runnables are wrapped
 * in try/catch so an exception in one task cannot kill the loop.
 */
public final class TickLoop {

    private static final Logger LOG = LoggerFactory.getLogger(TickLoop.class);
    private static final long TICK_BUDGET_MS = 50L;

    private final long tickPeriodNanos;
    private final Thread tickThread;
    private final AtomicLong tickCount = new AtomicLong(0L);

    /** Tasks submitted via {@link #submit(Runnable)} — drained once per tick. */
    private final LinkedBlockingQueue<Runnable> simulateTasks = new LinkedBlockingQueue<>();

    /**
     * Delayed tasks keyed by absolute fire-tick. Mutated only on the tick thread plus
     * {@link #scheduleDelayed} producers; guarded by its own monitor.
     */
    private final Map<Long, List<Runnable>> delayedTasks = new HashMap<>();

    /** Repeating tasks. Guarded by its own monitor. */
    private final List<RepeatingTask> repeatingTasks = new ArrayList<>();

    private volatile boolean running;
    private volatile Runnable preTickHook;
    private volatile Runnable postTickHook;

    public TickLoop() {
        this(20);
    }

    public TickLoop(int targetTps) {
        if (targetTps <= 0) throw new IllegalArgumentException("targetTps must be > 0");
        this.tickPeriodNanos = TimeUnit.SECONDS.toNanos(1) / targetTps;
        this.tickThread = new Thread(this::run, "butterfly-tick");
        this.tickThread.setDaemon(false);
    }

    /** Sets the hook fired at the start of each tick (after tickCount increment). May be null. */
    public void setPreTickHook(Runnable hook) {
        this.preTickHook = hook;
    }

    /** Sets the hook fired at the end of each tick (after task drain). May be null. */
    public void setPostTickHook(Runnable hook) {
        this.postTickHook = hook;
    }

    /** Spawns the tick thread. Idempotent: subsequent calls while running are no-ops. */
    public synchronized void start() {
        if (running) return;
        running = true;
        tickThread.start();
    }

    /**
     * Signals the tick thread to stop and joins it (5&nbsp;s timeout, then interrupts).
     * Idempotent.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            tickThread.join(5_000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (tickThread.isAlive()) {
            tickThread.interrupt();
        }
    }

    /** @return the number of ticks elapsed since {@link #start()}. */
    public long currentTick() {
        return tickCount.get();
    }

    /** @return {@code true} if the calling thread is the tick thread. */
    public boolean isOnTickThread() {
        return Thread.currentThread() == tickThread;
    }

    /** @return the underlying tick thread (useful for {@code WorldImpl} simulate-thread checks). */
    public Thread thread() {
        return tickThread;
    }

    /** @return {@code true} if the loop is currently running. */
    public boolean isRunning() {
        return running;
    }

    /** Queues {@code task} to run at the start of the next tick on the tick thread. */
    public void submit(Runnable task) {
        if (task == null) throw new NullPointerException("task");
        simulateTasks.add(task);
    }

    /** Schedules {@code task} to run {@code delayTicks} ticks from now (>= 0). */
    public void scheduleDelayed(Runnable task, int delayTicks) {
        if (task == null) throw new NullPointerException("task");
        if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be >= 0");
        long fireAt = tickCount.get() + Math.max(1, delayTicks);
        synchronized (delayedTasks) {
            delayedTasks.computeIfAbsent(fireAt, k -> new ArrayList<>()).add(task);
        }
    }

    /**
     * Registers a repeating task. First fire is at {@code currentTick + initialDelayTicks},
     * subsequent fires every {@code periodTicks}.
     */
    public void scheduleRepeating(Runnable task, int initialDelayTicks, int periodTicks) {
        if (task == null) throw new NullPointerException("task");
        if (initialDelayTicks < 0) throw new IllegalArgumentException("initialDelayTicks must be >= 0");
        if (periodTicks <= 0) throw new IllegalArgumentException("periodTicks must be > 0");
        long firstFire = tickCount.get() + Math.max(1, initialDelayTicks);
        synchronized (repeatingTasks) {
            repeatingTasks.add(new RepeatingTask(task, periodTicks, firstFire));
        }
    }

    private void run() {
        long nextTickDeadline = System.nanoTime();
        while (running) {
            long startNs = System.nanoTime();
            long thisTick = tickCount.incrementAndGet();

            runHook(preTickHook, "preTickHook");
            drainSimulateTasks();
            fireDelayed(thisTick);
            fireRepeating(thisTick);
            runHook(postTickHook, "postTickHook");

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            if (elapsedMs > TICK_BUDGET_MS) {
                LOG.warn("Tick {} exceeded budget: took {}ms (>{}ms)", thisTick, elapsedMs, TICK_BUDGET_MS);
            }

            nextTickDeadline += tickPeriodNanos;
            long sleepNs = nextTickDeadline - System.nanoTime();
            if (sleepNs > 0) {
                LockSupportSleep.parkNanos(sleepNs);
            } else {
                // We're behind; reset the deadline so we don't spin forever trying to catch up.
                nextTickDeadline = System.nanoTime();
            }
        }
    }

    private void runHook(Runnable hook, String label) {
        if (hook == null) return;
        try {
            hook.run();
        } catch (Throwable t) {
            LOG.error("Exception in {}", label, t);
        }
    }

    private void drainSimulateTasks() {
        Runnable task;
        while ((task = simulateTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.error("Exception in simulate task", t);
            }
        }
    }

    private void fireDelayed(long thisTick) {
        List<Runnable> due;
        synchronized (delayedTasks) {
            due = delayedTasks.remove(thisTick);
        }
        if (due == null) return;
        for (Runnable r : due) {
            try {
                r.run();
            } catch (Throwable t) {
                LOG.error("Exception in delayed task", t);
            }
        }
    }

    private void fireRepeating(long thisTick) {
        // Snapshot indices of due tasks plus the tasks themselves under the lock; release before
        // running to avoid holding the lock across user code.
        List<RepeatingTask> due;
        synchronized (repeatingTasks) {
            due = null;
            for (int i = 0; i < repeatingTasks.size(); i++) {
                RepeatingTask rt = repeatingTasks.get(i);
                if (rt.nextFireTick() <= thisTick) {
                    if (due == null) due = new ArrayList<>();
                    due.add(rt);
                    repeatingTasks.set(i, rt.advance(thisTick));
                }
            }
        }
        if (due == null) return;
        for (RepeatingTask rt : due) {
            try {
                rt.task().run();
            } catch (Throwable t) {
                LOG.error("Exception in repeating task", t);
            }
        }
    }

    /**
     * A repeating task. Records are immutable, so each fire produces a fresh
     * record with an updated {@code nextFireTick}.
     */
    record RepeatingTask(Runnable task, int period, long nextFireTick) {
        RepeatingTask advance(long firedAt) {
            // Step forward at least one period past firedAt to avoid drift if we missed ticks.
            long next = nextFireTick + period;
            while (next <= firedAt) next += period;
            return new RepeatingTask(task, period, next);
        }
    }

    /** Tiny indirection so the loop body has no static dep on {@link java.util.concurrent.locks.LockSupport}. */
    private static final class LockSupportSleep {
        static void parkNanos(long ns) {
            java.util.concurrent.locks.LockSupport.parkNanos(ns);
        }
    }
}
