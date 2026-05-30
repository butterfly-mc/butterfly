package net.butterfly.api.event;

/**
 * Dispatch priority for event handlers. Handlers are invoked in declaration
 * order: {@link #LOWEST} first, {@link #MONITOR} last.
 *
 * <p>{@link #MONITOR} handlers must not modify event state and are always
 * invoked, even when the event has been cancelled by an earlier handler.
 */
public enum EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST,
    MONITOR
}
