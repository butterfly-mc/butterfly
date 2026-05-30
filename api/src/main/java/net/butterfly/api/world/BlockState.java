package net.butterfly.api.world;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a block: its identifier and any state properties.
 *
 * <p>Examples of {@code name}: {@code "minecraft:stone"}, {@code "minecraft:oak_log"}.
 *
 * <p>{@code properties} is an immutable map of state values keyed by name (e.g.
 * {@code {"facing": "north"}}). The map returned from {@link #properties()} is unmodifiable.
 *
 * <p>Equality and hashCode are derived from {@code name} and {@code properties} (record default).
 *
 * @param name       block identifier in {@code "namespace:path"} form, never {@code null}
 * @param properties immutable map of state properties, never {@code null}
 */
public record BlockState(String name, Map<String, Object> properties) {

    public BlockState {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(properties, "properties");
        properties = Collections.unmodifiableMap(Map.copyOf(properties));
    }

    /**
     * Creates a {@link BlockState} with no state properties.
     *
     * @param name block identifier (e.g. {@code "minecraft:stone"}); must not be {@code null}
     * @return a new {@link BlockState} with an empty, immutable properties map
     */
    public static BlockState of(String name) {
        return new BlockState(name, Collections.emptyMap());
    }
}
