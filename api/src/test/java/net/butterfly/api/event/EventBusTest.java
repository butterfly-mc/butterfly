package net.butterfly.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.butterfly.api.event.events.PlayerChatEvent;
import net.butterfly.api.event.events.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

class EventBusTest {

    @Test
    void subscribeThenFireDeliversEvent() {
        TestEventBus bus = new TestEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(PlayerJoinEvent.class, e -> seen.add(e.getPlayerName()));

        bus.fire(new PlayerJoinEvent("steve", "xuid-1", "joined"));

        assertEquals(List.of("steve"), seen);
    }

    @Test
    void priorityOrderingRunsLowestFirstAndMonitorLast() {
        TestEventBus bus = new TestEventBus();
        List<EventPriority> order = new ArrayList<>();

        bus.subscribe(PlayerJoinEvent.class, EventPriority.NORMAL, e -> order.add(EventPriority.NORMAL));
        bus.subscribe(PlayerJoinEvent.class, EventPriority.MONITOR, e -> order.add(EventPriority.MONITOR));
        bus.subscribe(PlayerJoinEvent.class, EventPriority.LOWEST, e -> order.add(EventPriority.LOWEST));
        bus.subscribe(PlayerJoinEvent.class, EventPriority.HIGH, e -> order.add(EventPriority.HIGH));
        bus.subscribe(PlayerJoinEvent.class, EventPriority.LOW, e -> order.add(EventPriority.LOW));
        bus.subscribe(PlayerJoinEvent.class, EventPriority.HIGHEST, e -> order.add(EventPriority.HIGHEST));

        bus.fire(new PlayerJoinEvent("alex", "xuid-2", "hi"));

        assertEquals(
                List.of(
                        EventPriority.LOWEST,
                        EventPriority.LOW,
                        EventPriority.NORMAL,
                        EventPriority.HIGH,
                        EventPriority.HIGHEST,
                        EventPriority.MONITOR),
                order);
    }

    @Test
    void cancelledEventsSkipNonMonitorHandlersByDefault() {
        TestEventBus bus = new TestEventBus();
        List<EventPriority> seen = new ArrayList<>();

        bus.subscribe(PlayerChatEvent.class, EventPriority.LOWEST, e -> e.setCancelled(true));
        bus.subscribe(PlayerChatEvent.class, EventPriority.NORMAL, e -> seen.add(EventPriority.NORMAL));
        bus.subscribe(PlayerChatEvent.class, EventPriority.HIGH, e -> seen.add(EventPriority.HIGH));
        bus.subscribe(PlayerChatEvent.class, EventPriority.MONITOR, e -> seen.add(EventPriority.MONITOR));

        bus.fire(new PlayerChatEvent("steve", "hello"));

        assertEquals(List.of(EventPriority.MONITOR), seen);
    }

    @Test
    void monitorAlwaysFiresEvenWhenCancelled() {
        TestEventBus bus = new TestEventBus();
        List<String> seen = new ArrayList<>();

        bus.subscribe(PlayerChatEvent.class, EventPriority.LOWEST, e -> e.setCancelled(true));
        bus.subscribe(PlayerChatEvent.class, EventPriority.MONITOR, e -> seen.add("monitor"));

        bus.fire(new PlayerChatEvent("steve", "hi"));

        assertEquals(List.of("monitor"), seen);
    }

    @Test
    void ignoreCancelledHandlerStillRunsOnCancelledEvent() {
        TestEventBus bus = new TestEventBus();
        List<String> seen = new ArrayList<>();

        IgnoreCancelledListener listener = new IgnoreCancelledListener(seen);
        bus.subscribe(PlayerChatEvent.class, EventPriority.LOWEST, e -> e.setCancelled(true));
        bus.register(listener);

        bus.fire(new PlayerChatEvent("steve", "hi"));

        assertTrue(seen.contains("ignore-cancelled"));
    }

    @Test
    void registerScansEventHandlerMethods() {
        TestEventBus bus = new TestEventBus();
        RecordingListener listener = new RecordingListener();
        bus.register(listener);

        bus.fire(new PlayerJoinEvent("steve", "xuid-3", "joined"));
        bus.fire(new PlayerChatEvent("steve", "hello"));

        assertEquals(List.of("join:steve", "chat:steve:hello"), listener.calls);
    }

    @Test
    void unregisterRemovesHandlersFromListener() {
        TestEventBus bus = new TestEventBus();
        RecordingListener listener = new RecordingListener();
        bus.register(listener);
        bus.unregister(listener);

        bus.fire(new PlayerJoinEvent("steve", "xuid-4", "joined"));

        assertTrue(listener.calls.isEmpty());
    }

    @Test
    void eventBaseDefaultsAreSane() {
        PlayerJoinEvent join = new PlayerJoinEvent("steve", "xuid", "msg");

        assertFalse(join.isCancelled(), "non-cancellable events default to not cancelled");
        assertEquals("PlayerJoinEvent", join.name());
    }

    private static final class RecordingListener implements Listener {
        final List<String> calls = new ArrayList<>();

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            calls.add("join:" + event.getPlayerName());
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onChat(PlayerChatEvent event) {
            calls.add("chat:" + event.getPlayerName() + ":" + event.getMessage());
        }
    }

    private static final class IgnoreCancelledListener implements Listener {
        private final List<String> seen;

        IgnoreCancelledListener(List<String> seen) {
            this.seen = seen;
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onChat(PlayerChatEvent event) {
            seen.add("ignore-cancelled");
        }
    }
}
