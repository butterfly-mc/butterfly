package net.butterfly.api.plugin;

import java.util.Collection;

/**
 * Registry of plugins loaded into the server.
 *
 * <p>Implementations are expected to be thread-safe. Returned collections
 * should be snapshots or immutable views.
 */
public interface PluginManager {

    /** All currently-registered plugins. */
    Collection<Plugin> getPlugins();

    /**
     * Look up a plugin by manifest name.
     *
     * @return the matching plugin, or {@code null} if no plugin with that name is registered
     */
    Plugin getPlugin(String name);

    /**
     * @return {@code true} if a plugin with this name is currently enabled
     */
    boolean isEnabled(String name);
}
