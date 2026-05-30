package net.butterfly.api.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reflective in-memory {@link EventBus} used only by tests in this module. The
 * production implementation lives in {@code core}; this stand-in exists so the
 * API contracts can be exercised without depending on it.
 */
final class TestEventBus implements EventBus {

    private static final EventPriority[] ORDER = EventPriority.values();

    private final Map<Class<? extends Event>, EnumMap<EventPriority, List<Subscription<?>>>> handlers = new HashMap<>();
    private final Map<Listener, List<Subscription<?>>> registered = new HashMap<>();

    @Override
    public <E extends Event> void subscribe(Class<E> type, EventPriority priority, Consumer<E> handler) {
        addSubscription(type, new Subscription<>(type, priority, handler, false));
    }

    @Override
    public <E extends Event> void subscribe(Class<E> type, Consumer<E> handler) {
        subscribe(type, EventPriority.NORMAL, handler);
    }

    @Override
    public void register(Listener listener) {
        List<Subscription<?>> subs = new ArrayList<>();
        for (Method method : listener.getClass().getDeclaredMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                throw new IllegalArgumentException(
                        "@EventHandler method " + method + " must take exactly one parameter");
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!Event.class.isAssignableFrom(param)) {
                throw new IllegalArgumentException(
                        "@EventHandler method " + method + " parameter must extend Event");
            }
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventType = (Class<? extends Event>) param;
            Consumer<Event> invoker = event -> {
                try {
                    method.invoke(listener, event);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
            Subscription<Event> sub = new Subscription<>(
                    eventType, annotation.priority(), invoker, annotation.ignoreCancelled());
            addSubscription(eventType, sub);
            subs.add(sub);
        }
        registered.put(listener, subs);
    }

    @Override
    public void unregister(Listener listener) {
        List<Subscription<?>> subs = registered.remove(listener);
        if (subs == null) {
            return;
        }
        for (Subscription<?> sub : subs) {
            EnumMap<EventPriority, List<Subscription<?>>> byPriority = handlers.get(sub.type);
            if (byPriority == null) {
                continue;
            }
            List<Subscription<?>> bucket = byPriority.get(sub.priority);
            if (bucket != null) {
                bucket.remove(sub);
            }
        }
    }

    @Override
    public <E extends Event> void fire(E event) {
        EnumMap<EventPriority, List<Subscription<?>>> byPriority = handlers.get(event.getClass());
        if (byPriority == null) {
            return;
        }
        boolean cancellable = event instanceof Cancellable;
        for (EventPriority priority : ORDER) {
            List<Subscription<?>> bucket = byPriority.get(priority);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (Subscription<?> raw : new ArrayList<>(bucket)) {
                @SuppressWarnings("unchecked")
                Subscription<E> sub = (Subscription<E>) raw;
                if (priority != EventPriority.MONITOR
                        && cancellable
                        && ((Cancellable) event).isCancelled()
                        && !sub.ignoreCancelled) {
                    continue;
                }
                sub.handler.accept(event);
            }
        }
    }

    private <E extends Event> void addSubscription(Class<E> type, Subscription<?> sub) {
        handlers.computeIfAbsent(type, k -> new EnumMap<>(EventPriority.class))
                .computeIfAbsent(sub.priority, k -> new ArrayList<>())
                .add(sub);
    }

    private static final class Subscription<E extends Event> {
        final Class<? extends Event> type;
        final EventPriority priority;
        final Consumer<E> handler;
        final boolean ignoreCancelled;

        Subscription(Class<? extends Event> type, EventPriority priority, Consumer<E> handler, boolean ignoreCancelled) {
            this.type = type;
            this.priority = priority;
            this.handler = handler;
            this.ignoreCancelled = ignoreCancelled;
        }
    }
}
