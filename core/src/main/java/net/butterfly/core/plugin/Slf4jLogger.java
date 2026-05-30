package net.butterfly.core.plugin;

import net.butterfly.api.plugin.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards {@link Logger} calls to an SLF4J logger created by name. Used by
 * {@link PluginHost} to give each plugin a logger scoped to {@code plugin.<name>}.
 */
final class Slf4jLogger implements Logger {

    private final org.slf4j.Logger delegate;

    Slf4jLogger(String name) {
        if (name == null) throw new NullPointerException("name");
        this.delegate = LoggerFactory.getLogger(name);
    }

    Slf4jLogger(org.slf4j.Logger delegate) {
        if (delegate == null) throw new NullPointerException("delegate");
        this.delegate = delegate;
    }

    @Override
    public void info(String message) {
        delegate.info(message);
    }

    @Override
    public void warn(String message) {
        delegate.warn(message);
    }

    @Override
    public void error(String message) {
        delegate.error(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        delegate.error(message, throwable);
    }

    @Override
    public void debug(String message) {
        delegate.debug(message);
    }
}
