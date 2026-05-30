package net.butterfly.api.entity;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An entity type identifier (e.g. {@code "minecraft:zombie"}).
 *
 * <p>Static fields are provided for common types. Custom types are registered implicitly
 * by constructing a new {@code EntityType}; the constructor records the instance in the
 * lookup table accessed via {@link #byIdentifier(String)}.
 *
 * <p>Equality and hashCode follow the record contract (based on {@code identifier}).
 *
 * @param identifier the type identifier in {@code "namespace:path"} form, never {@code null}
 */
public record EntityType(String identifier) {

    private static final Map<String, EntityType> KNOWN = new ConcurrentHashMap<>();

    public EntityType {
        Objects.requireNonNull(identifier, "identifier");
        KNOWN.putIfAbsent(identifier, this);
    }

    /** {@code minecraft:player} — a human-controlled player entity. */
    public static final EntityType PLAYER = new EntityType("minecraft:player");

    /** {@code minecraft:zombie}. */
    public static final EntityType ZOMBIE = new EntityType("minecraft:zombie");

    /** {@code minecraft:item} — a dropped item stack on the ground. */
    public static final EntityType ITEM = new EntityType("minecraft:item");

    /** {@code minecraft:arrow}. */
    public static final EntityType ARROW = new EntityType("minecraft:arrow");

    /**
     * Looks up a previously registered entity type by identifier.
     *
     * @param identifier the type identifier (e.g. {@code "minecraft:zombie"})
     * @return the registered {@link EntityType}, or {@code null} if no type with that
     *         identifier has been constructed
     */
    public static EntityType byIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        return KNOWN.get(identifier);
    }
}
