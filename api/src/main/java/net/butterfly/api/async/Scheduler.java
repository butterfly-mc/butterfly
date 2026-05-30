package net.butterfly.api.async;

/**
 * Server scheduler.
 *
 * <p>The simulate (main) thread owns world state. Anything that mutates the
 * world must run on the simulate thread, dispatched via
 * {@link #runOnMain(Runnable)}, {@link #runLater(Runnable, int)}, or
 * {@link #runRepeating(Runnable, int, int)}. Use {@link #runAsync(Runnable)}
 * for CPU- or IO-bound work that does not touch world state.
 */
public interface Scheduler {

    /** Queue {@code task} for the next simulate phase. */
    void runOnMain(Runnable task);

    /** Run {@code task} on a shared {@link java.util.concurrent.ForkJoinPool}. */
    void runAsync(Runnable task);

    /**
     * Run {@code task} on the simulate thread after {@code delayTicks} ticks
     * have elapsed.
     */
    void runLater(Runnable task, int delayTicks);

    /**
     * Run {@code task} repeatedly on the simulate thread, starting after
     * {@code initialDelayTicks} and re-firing every {@code periodTicks}
     * thereafter.
     */
    void runRepeating(Runnable task, int initialDelayTicks, int periodTicks);
}
