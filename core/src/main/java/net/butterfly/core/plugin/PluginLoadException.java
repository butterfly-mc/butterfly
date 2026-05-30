package net.butterfly.core.plugin;

/**
 * Thrown when the plugin loader cannot read, validate, or order a plugin
 * jar (missing manifest, malformed YAML, dependency cycle, etc.).
 */
public class PluginLoadException extends RuntimeException {

    public PluginLoadException(String message) {
        super(message);
    }

    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
