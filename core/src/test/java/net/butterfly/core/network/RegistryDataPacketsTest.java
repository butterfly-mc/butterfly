package net.butterfly.core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryDataPacketsTest {
    // Source byte counts captured from the BDS asset directory at the time the
    // resources were embedded. Update these if the assets are re-captured.
    private static final int EXPECTED_ITEM_REGISTRY_LEN = 107311;
    private static final int EXPECTED_BIOME_DEFINITIONS_LEN = 7405;
    private static final int EXPECTED_CREATIVE_CONTENT_LEN = 53417;
    private static final int EXPECTED_CRAFTING_DATA_LEN = 416154;
    private static final int EXPECTED_ACTOR_IDENTIFIERS_LEN = 8777;

    @Test
    void itemRegistry_loadsExpectedSize() {
        byte[] body = RegistryDataPackets.itemRegistry();
        assertNotNull(body);
        assertEquals(EXPECTED_ITEM_REGISTRY_LEN, body.length);
    }

    @Test
    void biomeDefinitions_loadsExpectedSize() {
        byte[] body = RegistryDataPackets.biomeDefinitions();
        assertNotNull(body);
        assertEquals(EXPECTED_BIOME_DEFINITIONS_LEN, body.length);
    }

    @Test
    void creativeContent_loadsExpectedSize() {
        byte[] body = RegistryDataPackets.creativeContent();
        assertNotNull(body);
        assertEquals(EXPECTED_CREATIVE_CONTENT_LEN, body.length);
    }

    @Test
    void craftingData_loadsExpectedSize() {
        byte[] body = RegistryDataPackets.craftingData();
        assertNotNull(body);
        assertEquals(EXPECTED_CRAFTING_DATA_LEN, body.length);
    }

    @Test
    void actorIdentifiers_loadsExpectedSize() {
        byte[] body = RegistryDataPackets.actorIdentifiers();
        assertNotNull(body);
        assertEquals(EXPECTED_ACTOR_IDENTIFIERS_LEN, body.length);
    }

    @Test
    void allLoaders_returnNonEmptyAndAreCached() {
        byte[][] firstCalls = {
            RegistryDataPackets.itemRegistry(),
            RegistryDataPackets.biomeDefinitions(),
            RegistryDataPackets.creativeContent(),
            RegistryDataPackets.craftingData(),
            RegistryDataPackets.actorIdentifiers(),
        };
        for (byte[] body : firstCalls) {
            assertTrue(body.length > 0, "registry body must be non-empty");
        }
        // Cached: second call returns the same reference.
        assertTrue(RegistryDataPackets.itemRegistry()      == firstCalls[0]);
        assertTrue(RegistryDataPackets.biomeDefinitions()  == firstCalls[1]);
        assertTrue(RegistryDataPackets.creativeContent()   == firstCalls[2]);
        assertTrue(RegistryDataPackets.craftingData()      == firstCalls[3]);
        assertTrue(RegistryDataPackets.actorIdentifiers()  == firstCalls[4]);
    }
}
