package net.butterfly.core.plugin;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.butterfly.api.plugin.Plugin;
import net.butterfly.api.plugin.PluginContext;
import net.butterfly.api.plugin.PluginManager;
import net.butterfly.api.plugin.PluginManifest;
import net.butterfly.api.plugin.Server;

import org.slf4j.LoggerFactory;

/**
 * Lifecycle owner for the JAR drop-in plugin loader.
 *
 * <p>Typical usage from the bootstrap (e.g. {@code ButterflyServer}):
 * <pre>{@code
 * PluginHost host = new PluginHost(server, Path.of("plugins"), Path.of("data"));
 * host.loadAll();
 * host.enableAll();
 * // ... server runs ...
 * host.disableAll();
 * }</pre>
 *
 * <p>{@link #loadAll()} fails fast on a broken plugin (bad manifest, missing
 * main class, wrong type, no no-arg constructor, etc.) — startup should not
 * silently skip a plugin the operator dropped in. {@link #enableAll()} is
 * fault-tolerant: if {@code onEnable} throws, the plugin is logged, marked
 * disabled, and the next plugin still gets a chance to start.
 */
public final class PluginHost {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(PluginHost.class);

    private final Server server;
    private final Path pluginsDir;
    private final Path dataDirRoot;
    private final PluginManagerImpl manager = new PluginManagerImpl();

    public PluginHost(Server server, Path pluginsDir, Path dataDirRoot) {
        // Server may be null in some early-bootstrap scenarios; the manager
        // facade does not require it. Plugins that dereference null will fail
        // fast on first use, which is preferable to silently swallowing it.
        if (pluginsDir == null) throw new NullPointerException("pluginsDir");
        if (dataDirRoot == null) throw new NullPointerException("dataDirRoot");
        this.server = server;
        this.pluginsDir = pluginsDir;
        this.dataDirRoot = dataDirRoot;
    }

    public PluginManager pluginManager() {
        return manager;
    }

    /**
     * Scan {@code pluginsDir} for jars, parse each manifest, sort by
     * dependencies, and instantiate every plugin (calling {@code init} and
     * {@code onLoad}). Plugins are not yet enabled — call {@link #enableAll()}.
     */
    public void loadAll() {
        List<Path> jars = PluginLoader.scanJars(pluginsDir);
        if (jars.isEmpty()) {
            LOGGER.info("No plugins found in {}", pluginsDir);
            return;
        }

        List<PluginManifest> manifests = new ArrayList<>(jars.size());
        List<Path> jarsForManifests = new ArrayList<>(jars.size());
        for (Path jar : jars) {
            PluginManifest manifest = PluginLoader.readManifest(jar);
            manifests.add(manifest);
            jarsForManifests.add(jar);
        }

        List<PluginManifest> ordered = PluginLoader.sortByDependencies(manifests);

        // Map back from manifest -> jar so loading respects sort order.
        for (PluginManifest manifest : ordered) {
            int idx = manifests.indexOf(manifest);
            Path jar = jarsForManifests.get(idx);
            loadOne(jar, manifest);
        }
    }

    private void loadOne(Path jar, PluginManifest manifest) {
        URL[] urls;
        try {
            urls = new URL[]{jar.toUri().toURL()};
        } catch (MalformedURLException ex) {
            throw new PluginLoadException("could not derive URL for " + jar, ex);
        }

        ClassLoader apiLoader = Plugin.class.getClassLoader();
        IsolatedPluginClassLoader loader = new IsolatedPluginClassLoader(urls, apiLoader);

        Plugin instance;
        try {
            Class<?> mainClass = Class.forName(manifest.mainClass(), true, loader);
            if (!Plugin.class.isAssignableFrom(mainClass)) {
                throw new PluginLoadException(
                        "plugin '" + manifest.name() + "' main class " + manifest.mainClass()
                                + " does not extend " + Plugin.class.getName());
            }
            Constructor<?> ctor;
            try {
                ctor = mainClass.getDeclaredConstructor();
            } catch (NoSuchMethodException ex) {
                throw new PluginLoadException(
                        "plugin '" + manifest.name() + "' main class " + manifest.mainClass()
                                + " is missing a public no-arg constructor", ex);
            }
            ctor.setAccessible(true);
            instance = (Plugin) ctor.newInstance();
        } catch (ClassNotFoundException ex) {
            closeQuietly(loader);
            throw new PluginLoadException(
                    "plugin '" + manifest.name() + "' main class not found: " + manifest.mainClass(), ex);
        } catch (InvocationTargetException ex) {
            closeQuietly(loader);
            throw new PluginLoadException(
                    "plugin '" + manifest.name() + "' constructor threw: " + ex.getCause(), ex.getCause());
        } catch (ReflectiveOperationException ex) {
            closeQuietly(loader);
            throw new PluginLoadException(
                    "plugin '" + manifest.name() + "' could not be instantiated: " + ex.getMessage(), ex);
        } catch (PluginLoadException ex) {
            closeQuietly(loader);
            throw ex;
        }

        Path dataFolder = dataDirRoot.resolve(manifest.name());
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException ex) {
            closeQuietly(loader);
            throw new PluginLoadException(
                    "could not create data folder for plugin '" + manifest.name() + "': " + dataFolder, ex);
        }

        Slf4jLogger logger = new Slf4jLogger("plugin." + manifest.name());
        PluginContext ctx = new PluginContext(server, logger, dataFolder, manifest);

        try {
            instance.init(ctx);
            instance.onLoad();
        } catch (RuntimeException ex) {
            closeQuietly(loader);
            throw new PluginLoadException(
                    "plugin '" + manifest.name() + "' threw during onLoad: " + ex.getMessage(), ex);
        }

        manager.register(new PluginManagerImpl.LoadedPlugin(instance, manifest, loader));
        LOGGER.info("Loaded plugin {} v{}", manifest.name(), manifest.version());
    }

    /**
     * Call {@code onEnable} on every loaded plugin in load order. A plugin
     * that throws is logged and skipped; the rest still get enabled so a
     * single broken plugin can't take the server down at startup.
     */
    public void enableAll() {
        for (PluginManagerImpl.LoadedPlugin loaded : manager.loadedInOrder()) {
            if (loaded.enabled) continue;
            try {
                loaded.instance.onEnable();
                loaded.enabled = true;
                LOGGER.info("Enabled plugin {}", loaded.manifest.name());
            } catch (Throwable t) {
                loaded.enabled = false;
                LOGGER.error("Plugin {} threw during onEnable; skipping",
                        loaded.manifest.name(), t);
            }
        }
    }

    /**
     * Disable plugins in reverse load order, then close each plugin's
     * classloader. Plugins that were never enabled (e.g. failed onEnable)
     * skip {@code onDisable} but still get their classloader closed.
     */
    public void disableAll() {
        List<PluginManagerImpl.LoadedPlugin> reverse = new ArrayList<>(manager.loadedInOrder());
        Collections.reverse(reverse);

        for (PluginManagerImpl.LoadedPlugin loaded : reverse) {
            if (loaded.enabled) {
                try {
                    loaded.instance.onDisable();
                } catch (Throwable t) {
                    LOGGER.error("Plugin {} threw during onDisable",
                            loaded.manifest.name(), t);
                } finally {
                    loaded.enabled = false;
                }
            }
            closeQuietly(loaded.loader);
        }
    }

    private static void closeQuietly(IsolatedPluginClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ex) {
            LOGGER.warn("Failed to close plugin classloader: {}", ex.toString());
        }
    }
}
