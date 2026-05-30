package net.butterfly.api.entity;

import java.util.UUID;
import net.butterfly.api.world.World;

/**
 * A live entity in a {@link World} — players, mobs, items, projectiles, etc.
 *
 * <p>An {@code Entity} handle stays valid until the entity is despawned. Position and
 * rotation accessors return the latest known state at the moment of the call;
 * implementations may serialize observations through the simulate thread.
 */
public interface Entity {

    /**
     * @return the entity's globally unique identifier; stable for the entity's lifetime
     */
    UUID uuid();

    /**
     * @return this entity's {@link EntityType}; never {@code null}
     */
    EntityType type();

    /**
     * @return the {@link World} this entity is currently in; never {@code null}
     */
    World world();

    /**
     * @return the entity's current X position in world coordinates
     */
    double x();

    /**
     * @return the entity's current Y position in world coordinates (feet position)
     */
    double y();

    /**
     * @return the entity's current Z position in world coordinates
     */
    double z();

    /**
     * @return the entity's current yaw (horizontal rotation) in degrees
     */
    float yaw();

    /**
     * @return the entity's current pitch (vertical rotation) in degrees
     */
    float pitch();

    /**
     * Teleports the entity to the given position, optionally crossing worlds.
     *
     * <p><b>Threading:</b> must be called on the simulate thread; the implementation
     * may throw {@link IllegalStateException} if called from another thread.
     *
     * <p>If the destination chunk is not loaded, implementations should load it before
     * placing the entity. The entity's yaw and pitch are preserved.
     *
     * @param world destination world (may be the same as the current world); not {@code null}
     * @param x     destination X
     * @param y     destination Y (feet position)
     * @param z     destination Z
     * @throws IllegalStateException if called off the simulate thread
     */
    void teleport(World world, double x, double y, double z);
}
