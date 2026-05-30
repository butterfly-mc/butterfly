package net.butterfly.api.plugin;

import java.nio.file.Path;

/**
 * Base class plugins extend.
 *
 * <p>The plugin loader instantiates a subclass via its public no-arg
 * constructor, then calls {@link #init(PluginContext)} exactly once before
 * dispatching {@link #onLoad()}, {@link #onEnable()}, and {@link #onDisable()}.
 */
public abstract class Plugin {

    private PluginContext context;

    /** Required no-arg constructor used by the loader. */
    protected Plugin() {
    }

    /**
     * Inject the plugin context. Called by the loader exactly once before
     * {@link #onLoad()}. Plugins should not call this themselves.
     *
     * @throws IllegalArgumentException if {@code ctx} is null
     * @throws IllegalStateException    if called more than once
     */
    public final void init(PluginContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("PluginContext must not be null");
        }
        if (this.context != null) {
            throw new IllegalStateException("Plugin already initialised");
        }
        this.context = ctx;
    }

    /** Called once after construction and {@link #init(PluginContext)}. */
    public abstract void onLoad();

    /** Called when the plugin is enabled. */
    public abstract void onEnable();

    /** Called when the plugin is being disabled (server shutdown or unload). */
    public abstract void onDisable();

    /**
     * @return the server facade
     * @throws IllegalStateException if the plugin has not been initialised yet
     */
    protected final Server getServer() {
        return requireContext().server();
    }

    /**
     * @return this plugin's logger
     * @throws IllegalStateException if the plugin has not been initialised yet
     */
    protected final Logger getLogger() {
        return requireContext().logger();
    }

    /**
     * @return this plugin's data folder
     * @throws IllegalStateException if the plugin has not been initialised yet
     */
    protected final Path getDataFolder() {
        return requireContext().dataFolder();
    }

    /**
     * @return this plugin's manifest
     * @throws IllegalStateException if the plugin has not been initialised yet
     */
    protected final PluginManifest getManifest() {
        return requireContext().manifest();
    }

    private PluginContext requireContext() {
        PluginContext ctx = this.context;
        if (ctx == null) {
            throw new IllegalStateException(
                    "Plugin not initialised; init(PluginContext) has not been called yet");
        }
        return ctx;
    }
}
