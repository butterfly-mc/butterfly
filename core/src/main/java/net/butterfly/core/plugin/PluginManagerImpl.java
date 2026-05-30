package net.butterfly.core.plugin;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.butterfly.api.plugin.Plugin;
import net.butterfly.api.plugin.PluginManager;
import net.butterfly.api.plugin.PluginManifest;

/**
 * In-memory registry of loaded plugins. Iteration order matches load order.
 *
 * <p>This implementation is intended to be driven exclusively by
 * {@link PluginHost}; the registration / enable methods are package-private
 * for that reason.
 */
public final class PluginManagerImpl implements PluginManager {

    /**
     * Bundle of state per loaded plugin.
     *
     * <p>{@code enabled} is mutable because {@code onEnable} / {@code onDisable}
     * flip the flag. The other fields are immutable once registered.
     */
    static final class LoadedPlugin {
        final Plugin instance;
        final PluginManifest manifest;
        final IsolatedPluginClassLoader loader;
        boolean enabled;

        LoadedPlugin(Plugin instance, PluginManifest manifest, IsolatedPluginClassLoader loader) {
            this.instance = instance;
            this.manifest = manifest;
            this.loader = loader;
            this.enabled = false;
        }
    }

    /** LinkedHashMap to preserve registration (load) order. */
    private final Map<String, LoadedPlugin> plugins = new LinkedHashMap<>();

    @Override
    public synchronized Collection<Plugin> getPlugins() {
        // Snapshot so callers can iterate without locking.
        return plugins.values().stream()
                .map(lp -> lp.instance)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public synchronized Plugin getPlugin(String name) {
        if (name == null) return null;
        LoadedPlugin loaded = plugins.get(name);
        return loaded == null ? null : loaded.instance;
    }

    @Override
    public synchronized boolean isEnabled(String name) {
        if (name == null) return false;
        LoadedPlugin loaded = plugins.get(name);
        return loaded != null && loaded.enabled;
    }

    // ----- internal API used by PluginHost ---------------------------------

    /** Register a plugin in load order. Throws if a plugin with the same name is already registered. */
    synchronized void register(LoadedPlugin loaded) {
        if (plugins.containsKey(loaded.manifest.name())) {
            throw new PluginLoadException("duplicate plugin name: " + loaded.manifest.name());
        }
        plugins.put(loaded.manifest.name(), loaded);
    }

    synchronized List<LoadedPlugin> loadedInOrder() {
        return List.copyOf(plugins.values());
    }

    synchronized LoadedPlugin loaded(String name) {
        return plugins.get(name);
    }

    synchronized Map<String, LoadedPlugin> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(plugins));
    }
}
