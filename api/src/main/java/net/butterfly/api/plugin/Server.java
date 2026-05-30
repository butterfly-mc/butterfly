package net.butterfly.api.plugin;

import java.util.Collection;

import net.butterfly.api.async.Scheduler;
import net.butterfly.api.async.WorldView;
import net.butterfly.api.command.CommandRegistry;
import net.butterfly.api.entity.Player;
import net.butterfly.api.event.EventBus;
import net.butterfly.api.world.World;

/**
 * Plugin-facing facade for the running server.
 *
 * <p>This is the entry point plugins use to interact with the rest of the
 * platform. Methods returning collections or facade objects are expected to
 * be thread-safe; methods that mutate world state should be dispatched
 * through the {@link Scheduler}.
 */
public interface Server {

    /** Human-readable server software version (e.g. "0.1.0"). */
    String version();

    /** Bedrock protocol version this build speaks (e.g. {@code 975}). */
    int protocolVersion();

    /** The default (primary) world for this server. */
    World defaultWorld();

    /** A read-only snapshot of the world; safe to read from any thread. */
    WorldView worldSnapshot();

    /** Server-wide event bus. */
    EventBus eventBus();

    /** Plugin manager controlling loaded plugins. */
    PluginManager pluginManager();

    /** Command registry for registering and looking up commands. */
    CommandRegistry commandRegistry();

    /** Server scheduler for main-thread / async / delayed work. */
    Scheduler scheduler();

    /** Snapshot of currently online players. */
    Collection<? extends Player> onlinePlayers();

    /**
     * Look up an online player by display name.
     *
     * @param name the player's name (case sensitivity is implementation-defined)
     * @return the matching player, or {@code null} if no such player is online
     */
    Player playerByName(String name);

    /** Initiate an orderly server shutdown. */
    void shutdown();
}
