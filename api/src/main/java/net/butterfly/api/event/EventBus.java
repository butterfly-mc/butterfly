package net.butterfly.api.event;

import java.util.function.Consumer;

/**
 * Central dispatcher for {@link Event}s. Implementations live outside the API
 * module (e.g. in {@code core}); plugins consume this interface only.
 */
public interface EventBus {

    /**
     * Subscribes {@code handler} to events of {@code type} at the given
     * {@code priority}. Cancelled events are skipped unless the priority is
     * {@link EventPriority#MONITOR}.
     */
    <E extends Event> void subscribe(Class<E> type, EventPriority priority, Consumer<E> handler);

    /** Subscribes {@code handler} at {@link EventPriority#NORMAL}. */
    <E extends Event> void subscribe(Class<E> type, Consumer<E> handler);

    /**
     * Discovers all {@link EventHandler}-annotated methods on {@code listener}
     * and binds them as subscribers.
     */
    void register(Listener listener);

    /** Removes every handler previously bound via {@link #register(Listener)}. */
    void unregister(Listener listener);

    /** Dispatches {@code event} to every subscribed handler in priority order. */
    <E extends Event> void fire(E event);
}
