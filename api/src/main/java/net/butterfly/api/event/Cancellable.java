package net.butterfly.api.event;

/**
 * Marks an {@link Event} that can be cancelled. Cancelled events are skipped by
 * handlers registered with {@code ignoreCancelled = false} except for those at
 * {@link EventPriority#MONITOR}, which always run.
 */
public interface Cancellable {

    /** Returns {@code true} if this event has been cancelled. */
    boolean isCancelled();

    /** Sets the cancellation state of this event. */
    void setCancelled(boolean cancelled);
}
