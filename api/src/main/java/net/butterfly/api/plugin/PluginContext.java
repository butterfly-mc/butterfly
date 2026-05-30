package net.butterfly.api.plugin;

import java.nio.file.Path;

/**
 * Bundle of services injected into a {@link Plugin} when it is loaded.
 *
 * @param server     the server facade
 * @param logger     a logger scoped to this plugin
 * @param dataFolder per-plugin data directory; the loader is expected to
 *                   ensure the directory exists before injection
 * @param manifest   the manifest the plugin was loaded from
 */
public record PluginContext(
        Server server,
        Logger logger,
        Path dataFolder,
        PluginManifest manifest) {
}
