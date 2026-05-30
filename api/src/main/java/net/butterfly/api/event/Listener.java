package net.butterfly.api.event;

/**
 * Marker interface for plugin classes that expose {@link EventHandler}-annotated
 * methods. Listener instances are passed to {@link EventBus#register(Listener)}
 * which scans them for handlers.
 */
public interface Listener {
}
