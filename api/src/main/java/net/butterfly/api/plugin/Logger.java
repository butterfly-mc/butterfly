package net.butterfly.api.plugin;

/**
 * Plugin-facing logger contract.
 *
 * <p>Plugins call into this interface; the core module supplies an
 * implementation that forwards to SLF4J. Implementations are expected to be
 * thread-safe.
 */
public interface Logger {

    /** Log an informational message. */
    void info(String message);

    /** Log a warning message. */
    void warn(String message);

    /** Log an error message. */
    void error(String message);

    /** Log an error message with an associated throwable. */
    void error(String message, Throwable throwable);

    /** Log a debug message. May be filtered by the underlying logger. */
    void debug(String message);
}
