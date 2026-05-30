package net.butterfly.api.event;

/**
 * Base class for all events fired through the {@link EventBus}.
 *
 * <p>Subclasses are typically simple data carriers. Events that can be cancelled
 * should additionally implement {@link Cancellable}; this base class returns
 * {@code false} from {@link #isCancelled()} so non-cancellable events behave
 * correctly without extra boilerplate.
 */
public abstract class Event {

    /**
     * Returns whether this event has been cancelled.
     *
     * <p>The default implementation always returns {@code false}. Events that
     * support cancellation must implement {@link Cancellable} and override this
     * method to return their cancellation state.
     */
    public boolean isCancelled() {
        return false;
    }

    /**
     * Returns a human-readable identifier for this event, defaulting to the
     * declaring class's simple name.
     */
    public String name() {
        return getClass().getSimpleName();
    }
}
