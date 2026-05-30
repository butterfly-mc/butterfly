package net.butterfly.api.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockStateTest {

    @Test
    void ofReturnsNameAndEmptyProperties() {
        BlockState state = BlockState.of("minecraft:stone");
        assertEquals("minecraft:stone", state.name());
        assertTrue(state.properties().isEmpty(), "properties() should be empty");
    }

    @Test
    void equalsAndHashCodeFollowRecordContract() {
        BlockState a = BlockState.of("minecraft:stone");
        BlockState b = BlockState.of("minecraft:stone");
        BlockState c = BlockState.of("minecraft:dirt");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void equalsConsidersProperties() {
        Map<String, Object> propsA = new HashMap<>();
        propsA.put("facing", "north");
        Map<String, Object> propsB = new HashMap<>();
        propsB.put("facing", "north");
        Map<String, Object> propsC = new HashMap<>();
        propsC.put("facing", "south");

        BlockState a = new BlockState("minecraft:furnace", propsA);
        BlockState b = new BlockState("minecraft:furnace", propsB);
        BlockState c = new BlockState("minecraft:furnace", propsC);

        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void propertiesMapIsImmutable() {
        BlockState state = BlockState.of("minecraft:stone");
        assertThrows(UnsupportedOperationException.class,
                () -> state.properties().put("facing", "north"));
    }

    @Test
    void propertiesMapIsImmutableEvenWhenConstructedWithMutableMap() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("facing", "north");

        BlockState state = new BlockState("minecraft:furnace", mutable);

        assertThrows(UnsupportedOperationException.class,
                () -> state.properties().put("lit", true));

        // Mutating the source map after construction must not affect the state.
        mutable.put("lit", true);
        assertEquals(1, state.properties().size());
        assertEquals("north", state.properties().get("facing"));
    }
}
