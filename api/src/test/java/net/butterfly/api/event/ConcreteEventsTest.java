package net.butterfly.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.butterfly.api.event.events.BlockBreakEvent;
import net.butterfly.api.event.events.BlockPlaceEvent;
import net.butterfly.api.event.events.PlayerChatEvent;
import net.butterfly.api.event.events.PlayerJoinEvent;
import net.butterfly.api.event.events.PlayerPreLoginEvent;
import net.butterfly.api.event.events.PlayerQuitEvent;
import net.butterfly.api.event.events.ServerStartEvent;
import net.butterfly.api.event.events.ServerStopEvent;
import org.junit.jupiter.api.Test;

class ConcreteEventsTest {

    @Test
    void playerChatEventMessageIsMutableAndCancellable() {
        PlayerChatEvent event = new PlayerChatEvent("steve", "hello");

        assertEquals("steve", event.getPlayerName());
        assertEquals("hello", event.getMessage());
        assertFalse(event.isCancelled(), "defaults to not cancelled");

        event.setMessage("world");
        assertEquals("world", event.getMessage());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    @Test
    void playerPreLoginEventCarriesKickReason() {
        PlayerPreLoginEvent event = new PlayerPreLoginEvent("steve", "xuid-1");

        assertEquals("steve", event.getPlayerName());
        assertEquals("xuid-1", event.getXuid());
        assertNull(event.getKickReason());
        assertFalse(event.isCancelled());

        event.setKickReason("banned");
        event.setCancelled(true);

        assertEquals("banned", event.getKickReason());
        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakEventDefensivelyCopiesDropsAndAllowsMutation() {
        java.util.ArrayList<String> source = new java.util.ArrayList<>(List.of("stone"));
        BlockBreakEvent event = new BlockBreakEvent("steve", 1, 64, -2, "minecraft:stone", source);

        assertNotSame(source, event.getDrops(), "constructor should defensively copy drops");

        source.add("mutated");
        assertEquals(List.of("stone"), event.getDrops(), "external mutation must not leak in");

        event.getDrops().add("cobblestone");
        assertEquals(List.of("stone", "cobblestone"), event.getDrops(), "drops list itself is mutable");

        event.setDrops(List.of());
        assertEquals(List.of(), event.getDrops());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakEventAcceptsNullDrops() {
        BlockBreakEvent event = new BlockBreakEvent("steve", 0, 0, 0, "minecraft:air", null);
        assertEquals(List.of(), event.getDrops());
    }

    @Test
    void blockPlaceEventGettersAndCancellation() {
        BlockPlaceEvent event = new BlockPlaceEvent("steve", 10, 70, -5, "minecraft:dirt");

        assertEquals("steve", event.getPlayerName());
        assertEquals(10, event.getX());
        assertEquals(70, event.getY());
        assertEquals(-5, event.getZ());
        assertEquals("minecraft:dirt", event.getBlockName());
        assertFalse(event.isCancelled());

        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    @Test
    void playerJoinAndQuitMessagesAreMutable() {
        PlayerJoinEvent join = new PlayerJoinEvent("steve", "xuid", "joined");
        join.setJoinMessage("welcome");
        assertEquals("welcome", join.getJoinMessage());
        assertFalse(join.isCancelled(), "join is not cancellable");

        PlayerQuitEvent quit = new PlayerQuitEvent("steve", "xuid", "left");
        quit.setQuitMessage("goodbye");
        assertEquals("goodbye", quit.getQuitMessage());
        assertFalse(quit.isCancelled(), "quit is not cancellable");
    }

    @Test
    void serverLifecycleEventsExposeNameOnly() {
        ServerStartEvent start = new ServerStartEvent();
        ServerStopEvent stop = new ServerStopEvent();

        assertEquals("ServerStartEvent", start.name());
        assertEquals("ServerStopEvent", stop.name());
        assertFalse(start.isCancelled());
        assertFalse(stop.isCancelled());
    }
}
