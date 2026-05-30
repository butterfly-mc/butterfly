package net.butterfly.api.command;

import java.util.Collection;

/**
 * Server-wide command registry.
 *
 * <p>Lookup is case-insensitive and matches both primary names and aliases.
 * Implementations are expected to be thread-safe.
 */
public interface CommandRegistry {

    /**
     * Register a command under its name and every alias.
     *
     * @throws IllegalArgumentException if any name collides with an
     *                                  already-registered command (impl detail)
     */
    void register(Command command);

    /**
     * Look up a command by name or alias (case-insensitive).
     *
     * @return the matching command, or {@code null} if none is registered
     */
    Command get(String name);

    /**
     * Remove a command (and all its aliases) by primary name.
     *
     * @return {@code true} if a command was removed
     */
    boolean unregister(String name);

    /** Snapshot of all registered commands (deduplicated; aliases not repeated). */
    Collection<Command> all();
}
