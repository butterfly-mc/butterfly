package net.butterfly.api.entity;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class EntityTypeTest {

    @Test
    void byIdentifierReturnsRegisteredPlayerInstance() {
        assertSame(EntityType.PLAYER, EntityType.byIdentifier("minecraft:player"));
    }

    @Test
    void byIdentifierReturnsRegisteredCommonTypes() {
        assertSame(EntityType.ZOMBIE, EntityType.byIdentifier("minecraft:zombie"));
        assertSame(EntityType.ITEM, EntityType.byIdentifier("minecraft:item"));
        assertSame(EntityType.ARROW, EntityType.byIdentifier("minecraft:arrow"));
    }

    @Test
    void byIdentifierReturnsNullForUnknown() {
        assertNull(EntityType.byIdentifier("minecraft:does_not_exist"));
    }

    @Test
    void byIdentifierReturnsNullForNull() {
        assertNull(EntityType.byIdentifier(null));
    }
}
