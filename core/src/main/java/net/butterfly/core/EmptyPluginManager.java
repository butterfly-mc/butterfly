package net.butterfly.core;

import net.butterfly.api.plugin.Plugin;
import net.butterfly.api.plugin.PluginManager;
import java.util.Collection;
import java.util.Collections;

/** Placeholder PluginManager used until {@link net.butterfly.core.plugin.PluginHost} is constructed. */
final class EmptyPluginManager implements PluginManager {
    static final EmptyPluginManager INSTANCE = new EmptyPluginManager();
    private EmptyPluginManager() {}
    @Override public Collection<Plugin> getPlugins() { return Collections.emptyList(); }
    @Override public Plugin getPlugin(String name) { return null; }
    @Override public boolean isEnabled(String name) { return false; }
}
