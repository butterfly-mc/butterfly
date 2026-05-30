package net.butterfly.core.command;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Production {@link CommandRegistry} implementation.
 *
 * <p>Stores registered commands in two case-folded maps:
 * <ul>
 *   <li>{@code byName} — primary names, insertion-ordered (drives {@link #all()}).</li>
 *   <li>{@code byAlias} — alias names; values point at the same {@link Command} as {@code byName}.</li>
 * </ul>
 *
 * <p>Lookups consult {@code byName} first, then {@code byAlias}. Registration rejects any name
 * or alias that collides with an already-registered key in either map. The implementation is
 * thread-safe via synchronized access on a single monitor.
 */
public final class CommandRegistryImpl implements CommandRegistry {

    private final Map<String, Command> byName = new LinkedHashMap<>();
    private final Map<String, Command> byAlias = new LinkedHashMap<>();

    @Override
    public synchronized void register(Command command) {
        if (command == null) throw new NullPointerException("command");
        String name = command.name();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("command name must be non-empty");
        }
        String key = name.toLowerCase(Locale.ROOT);

        if (byName.containsKey(key) || byAlias.containsKey(key)) {
            throw new IllegalArgumentException(key);
        }

        List<String> aliases = command.aliases();
        if (aliases == null) aliases = Collections.emptyList();
        List<String> aliasKeys = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            if (alias == null || alias.isEmpty()) continue;
            String aliasKey = alias.toLowerCase(Locale.ROOT);
            if (aliasKey.equals(key) || aliasKeys.contains(aliasKey)) {
                throw new IllegalArgumentException(aliasKey);
            }
            if (byName.containsKey(aliasKey) || byAlias.containsKey(aliasKey)) {
                throw new IllegalArgumentException(aliasKey);
            }
            aliasKeys.add(aliasKey);
        }

        // All keys are free — commit.
        byName.put(key, command);
        for (String aliasKey : aliasKeys) {
            byAlias.put(aliasKey, command);
        }
    }

    @Override
    public synchronized Command get(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT);
        Command c = byName.get(key);
        if (c != null) return c;
        return byAlias.get(key);
    }

    @Override
    public synchronized boolean unregister(String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        Command removed = byName.remove(key);
        if (removed == null) return false;

        // Drop every alias that points at the removed command.
        byAlias.values().removeIf(c -> c == removed);
        return true;
    }

    @Override
    public synchronized Collection<Command> all() {
        // Snapshot in insertion order so callers can iterate without holding the lock.
        return new ArrayList<>(byName.values());
    }
}
