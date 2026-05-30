package net.butterfly.core.event;

import net.butterfly.api.event.Cancellable;
import net.butterfly.api.event.Event;
import net.butterfly.api.event.EventBus;
import net.butterfly.api.event.EventHandler;
import net.butterfly.api.event.EventPriority;
import net.butterfly.api.event.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Production {@link EventBus} implementation.
 *
 * <p>Internally maintains a {@code Map<Class<? extends Event>, CopyOnWriteArrayList<HandlerEntry>>}.
 * Handler lists are kept sorted by {@link EventPriority#ordinal()} (LOWEST first, MONITOR last).
 *
 * <p>Threading: {@link #register(Listener)} / {@link #unregister(Listener)} synchronize on the
 * registry map; {@link #fire(Event)} reads the per-event {@code CopyOnWriteArrayList} without
 * locking. The bus is intended for the simulate thread, but registration may safely happen from
 * the bootstrap thread at startup.
 *
 * <p>Misbehaving handlers are tolerated: an exception thrown from a handler is caught and logged,
 * remaining handlers still run. A handler that throws 5 or more times is logged once at WARN
 * level and removed from the registry to avoid spam (repeat-offender auto-disable).
 */
public final class EventBusImpl implements EventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventBusImpl.class);

    /** Threshold for repeat-offender auto-disable. */
    static final int EXCEPTION_THRESHOLD = 5;

    private static final Comparator<HandlerEntry> BY_PRIORITY =
            Comparator.comparingInt(e -> e.priority().ordinal());

    private final Map<Class<? extends Event>, CopyOnWriteArrayList<HandlerEntry>> handlers =
            new HashMap<>();

    /** Per-handler exception counters for repeat-offender tracking. */
    private final Map<HandlerEntry, Integer> exceptionCounts = new ConcurrentHashMap<>();

    @Override
    public <E extends Event> void subscribe(Class<E> eventType, EventPriority priority, Consumer<E> handler) {
        if (eventType == null) throw new NullPointerException("eventType");
        if (priority == null) throw new NullPointerException("priority");
        if (handler == null) throw new NullPointerException("handler");

        @SuppressWarnings("unchecked")
        Consumer<Event> erased = (Consumer<Event>) handler;
        addEntry(eventType, new HandlerEntry(priority, erased, false, null));
    }

    @Override
    public <E extends Event> void subscribe(Class<E> eventType, Consumer<E> handler) {
        subscribe(eventType, EventPriority.NORMAL, handler);
    }

    @Override
    public void register(Listener listener) {
        if (listener == null) throw new NullPointerException("listener");

        for (Method m : listener.getClass().getMethods()) {
            EventHandler annotation = m.getAnnotation(EventHandler.class);
            if (annotation == null) continue;

            if (Modifier.isStatic(m.getModifiers())) {
                LOGGER.warn("Skipping static @EventHandler method {}.{}",
                        listener.getClass().getName(), m.getName());
                continue;
            }
            if (m.getParameterCount() != 1) {
                LOGGER.warn("Skipping @EventHandler {}.{} — must take exactly one parameter, got {}",
                        listener.getClass().getName(), m.getName(), m.getParameterCount());
                continue;
            }
            Class<?> param = m.getParameterTypes()[0];
            if (!Event.class.isAssignableFrom(param)) {
                LOGGER.warn("Skipping @EventHandler {}.{} — parameter {} is not an Event subtype",
                        listener.getClass().getName(), m.getName(), param.getName());
                continue;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Event> eventType = (Class<? extends Event>) param;

            MethodHandle mh;
            try {
                m.setAccessible(true);
                mh = MethodHandles.lookup().unreflect(m).bindTo(listener);
            } catch (IllegalAccessException ex) {
                LOGGER.warn("Cannot access @EventHandler {}.{}: {}",
                        listener.getClass().getName(), m.getName(), ex.toString());
                continue;
            }

            final MethodHandle bound = mh;
            Consumer<Event> consumer = event -> {
                try {
                    bound.invoke(event);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            };

            addEntry(eventType, new HandlerEntry(
                    annotation.priority(), consumer, annotation.ignoreCancelled(), listener));
        }
    }

    @Override
    public void unregister(Listener listener) {
        if (listener == null) throw new NullPointerException("listener");

        synchronized (handlers) {
            for (CopyOnWriteArrayList<HandlerEntry> list : handlers.values()) {
                list.removeIf(entry -> entry.owner() == listener);
            }
        }
        // Drop counter rows whose entries are gone.
        exceptionCounts.keySet().removeIf(entry -> entry.owner() == listener);
    }

    @Override
    public <E extends Event> void fire(E event) {
        if (event == null) throw new NullPointerException("event");

        CopyOnWriteArrayList<HandlerEntry> list = handlers.get(event.getClass());
        if (list == null || list.isEmpty()) return;

        // Iterating a CopyOnWriteArrayList yields a snapshot; safe without locking.
        // Track entries to disable for repeat offenses; remove after iteration to avoid mutating
        // while iterating.
        List<HandlerEntry> toDisable = null;

        for (HandlerEntry entry : list) {
            if (event instanceof Cancellable c
                    && c.isCancelled()
                    && entry.priority() != EventPriority.MONITOR
                    && !entry.ignoreCancelled()) {
                continue;
            }

            try {
                entry.handler().accept(event);
            } catch (Throwable t) {
                LOGGER.error("Handler {} threw while processing {}",
                        handlerLabel(entry), event.getClass().getName(), t);

                int count = exceptionCounts.merge(entry, 1, Integer::sum);
                if (count >= EXCEPTION_THRESHOLD) {
                    if (toDisable == null) toDisable = new ArrayList<>(1);
                    toDisable.add(entry);
                }
            }
        }

        if (toDisable != null) {
            for (HandlerEntry entry : toDisable) {
                disableEntry(event.getClass(), entry);
            }
        }
    }

    // ----------------------------------------------------------------- internals

    private void addEntry(Class<? extends Event> eventType, HandlerEntry entry) {
        synchronized (handlers) {
            CopyOnWriteArrayList<HandlerEntry> list =
                    handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
            // Find insertion point so the list stays sorted by priority ordinal.
            // New entries with equal priority sort AFTER existing ones (FIFO within a priority).
            int idx = 0;
            for (HandlerEntry existing : list) {
                if (BY_PRIORITY.compare(existing, entry) > 0) break;
                idx++;
            }
            // Single atomic insert — concurrent fire() readers always see a consistent snapshot.
            list.add(idx, entry);
        }
    }

    private void disableEntry(Class<? extends Event> eventType, HandlerEntry entry) {
        synchronized (handlers) {
            CopyOnWriteArrayList<HandlerEntry> list = handlers.get(eventType);
            if (list != null && list.remove(entry)) {
                LOGGER.warn("Auto-disabling handler {} for {} after {} exceptions",
                        handlerLabel(entry), eventType.getName(), EXCEPTION_THRESHOLD);
            }
        }
        exceptionCounts.remove(entry);
    }

    private static String handlerLabel(HandlerEntry entry) {
        if (entry.owner() != null) return entry.owner().getClass().getName();
        return entry.handler().getClass().getName();
    }

    /** Visible for testing. */
    int handlerCount(Class<? extends Event> eventType) {
        CopyOnWriteArrayList<HandlerEntry> list = handlers.get(eventType);
        return list == null ? 0 : list.size();
    }

    /**
     * Internal handler record. {@code owner} is {@code null} for handlers registered via the
     * lambda {@code subscribe(...)} overloads.
     */
    record HandlerEntry(
            EventPriority priority,
            Consumer<Event> handler,
            boolean ignoreCancelled,
            Listener owner) {
    }
}
