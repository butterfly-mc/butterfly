package net.butterfly.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a {@link Listener} as an event handler.
 *
 * <p>The annotated method must take exactly one parameter whose type extends
 * {@link Event}. {@link EventBus#register(Listener)} will discover and bind the
 * method at the configured priority.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {

    /** Dispatch priority for this handler. */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * If {@code false} (the default), the handler is skipped when the event is
     * cancelled. {@link EventPriority#MONITOR} handlers always run regardless
     * of this flag.
     */
    boolean ignoreCancelled() default false;
}
