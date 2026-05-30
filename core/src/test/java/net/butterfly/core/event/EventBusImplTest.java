package net.butterfly.core.event;

import net.butterfly.api.event.Cancellable;
import net.butterfly.api.event.Event;
import net.butterfly.api.event.EventHandler;
import net.butterfly.api.event.EventPriority;
import net.butterfly.api.event.Listener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusImplTest {

    /** Minimal cancellable Event subtype for tests. */
    static final class TestEvent extends Event implements Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }

    @Test
    void subscribeAndFireDelivers() {
        EventBusImpl bus = new EventBusImpl();
        AtomicInteger seen = new AtomicInteger();

        bus.subscribe(TestEvent.class, e -> seen.incrementAndGet());
        bus.fire(new TestEvent());

        assertEquals(1, seen.get());
    }

    @Test
    void priorityOrdering() {
        EventBusImpl bus = new EventBusImpl();
        List<EventPriority> calls = new ArrayList<>();

        // Register in mixed order to prove the bus sorts.
        bus.subscribe(TestEvent.class, EventPriority.HIGH, e -> calls.add(EventPriority.HIGH));
        bus.subscribe(TestEvent.class, EventPriority.LOWEST, e -> calls.add(EventPriority.LOWEST));
        bus.subscribe(TestEvent.class, EventPriority.MONITOR, e -> calls.add(EventPriority.MONITOR));
        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> calls.add(EventPriority.NORMAL));
        bus.subscribe(TestEvent.class, EventPriority.HIGHEST, e -> calls.add(EventPriority.HIGHEST));
        bus.subscribe(TestEvent.class, EventPriority.LOW, e -> calls.add(EventPriority.LOW));

        bus.fire(new TestEvent());

        assertIterableEquals(
                List.of(
                        EventPriority.LOWEST,
                        EventPriority.LOW,
                        EventPriority.NORMAL,
                        EventPriority.HIGH,
                        EventPriority.HIGHEST,
                        EventPriority.MONITOR),
                calls);
    }

    @Test
    void cancelledSkipsNonMonitor() {
        EventBusImpl bus = new EventBusImpl();
        AtomicInteger normalAfterCancel = new AtomicInteger();
        AtomicInteger monitorCalls = new AtomicInteger();

        // First NORMAL handler cancels the event.
        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> ((TestEvent) e).setCancelled(true));
        // Second NORMAL handler must NOT be called once cancelled.
        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> normalAfterCancel.incrementAndGet());
        // MONITOR must always run.
        bus.subscribe(TestEvent.class, EventPriority.MONITOR, e -> monitorCalls.incrementAndGet());

        bus.fire(new TestEvent());

        assertEquals(0, normalAfterCancel.get(), "second NORMAL handler should be skipped");
        assertEquals(1, monitorCalls.get(), "MONITOR handler should still run");
    }

    @Test
    void ignoreCancelledTrueStillRuns() {
        EventBusImpl bus = new EventBusImpl();
        AtomicInteger ignoreCancelledCalls = new AtomicInteger();

        // Cancel the event at NORMAL.
        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> ((TestEvent) e).setCancelled(true));

        // Listener-style handler with ignoreCancelled = true.
        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onTest(TestEvent e) {
                ignoreCancelledCalls.incrementAndGet();
            }
        };
        bus.register(listener);

        bus.fire(new TestEvent());

        assertEquals(1, ignoreCancelledCalls.get());
    }

    @Test
    void registerListenerWithEventHandler() {
        EventBusImpl bus = new EventBusImpl();
        AtomicInteger calls = new AtomicInteger();

        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void onTest(TestEvent e) {
                calls.incrementAndGet();
            }
        };

        bus.register(listener);
        bus.fire(new TestEvent());
        assertEquals(1, calls.get(), "handler should fire after register");

        bus.unregister(listener);
        bus.fire(new TestEvent());
        assertEquals(1, calls.get(), "handler should not fire after unregister");
    }

    @Test
    void handlerExceptionIsCaught() {
        EventBusImpl bus = new EventBusImpl();
        AtomicInteger second = new AtomicInteger();

        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> {
            throw new RuntimeException("boom");
        });
        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> second.incrementAndGet());

        bus.fire(new TestEvent());

        assertEquals(1, second.get(), "second handler must still run when first throws");
    }

    @Test
    void repeatOffenderAutoDisable() {
        EventBusImpl bus = new EventBusImpl();
        AtomicInteger throwingCalls = new AtomicInteger();

        bus.subscribe(TestEvent.class, EventPriority.NORMAL, e -> {
            throwingCalls.incrementAndGet();
            throw new RuntimeException("always throws");
        });

        // Fires 1..5 — handler throws, counter increments. On the 5th throw it gets disabled.
        for (int i = 0; i < EventBusImpl.EXCEPTION_THRESHOLD; i++) {
            bus.fire(new TestEvent());
        }
        assertEquals(EventBusImpl.EXCEPTION_THRESHOLD, throwingCalls.get(),
                "handler should have been called the threshold number of times");
        assertEquals(0, bus.handlerCount(TestEvent.class),
                "handler should be removed after hitting the threshold");

        // 6th fire — handler is gone, counter must not advance.
        bus.fire(new TestEvent());
        assertEquals(EventBusImpl.EXCEPTION_THRESHOLD, throwingCalls.get(),
                "removed handler should not be invoked again");
        assertFalse(throwingCalls.get() > EventBusImpl.EXCEPTION_THRESHOLD);
        assertTrue(throwingCalls.get() == EventBusImpl.EXCEPTION_THRESHOLD);
    }
}
