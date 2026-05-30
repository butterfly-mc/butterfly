package net.butterfly.core.network;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loaders for the bundled BDS registry packet bodies (protocol 975).
 *
 * <p>Each method returns the raw <em>body</em> of one of the post-StartGame
 * registry packets — exactly the bytes captured from BDS, with no packet header.
 * Callers are expected to wrap the returned byte[] in a
 * {@link net.butterfly.codec.packets.RawCapturePacket} keyed by the appropriate
 * {@link net.butterfly.codec.PacketIds} constant before sending.
 *
 * <p>All bodies are loaded from {@code /butterfly_data/v975/} on the classpath
 * and cached after the first successful load. The cached arrays are read-only —
 * callers must not mutate the returned reference.
 */
public final class RegistryDataPackets {
    private static final String BASE = "/butterfly_data/v975/";

    private static volatile byte[] itemRegistry;
    private static volatile byte[] biomeDefinitions;
    private static volatile byte[] creativeContent;
    private static volatile byte[] craftingData;
    private static volatile byte[] actorIdentifiers;

    private RegistryDataPackets() {}

    /** ItemRegistry body (packet id 0xa2). */
    public static byte[] itemRegistry() {
        byte[] local = itemRegistry;
        if (local == null) itemRegistry = local = loadResource("item_registry.bin");
        return local;
    }

    /** BiomeDefinitionList body (packet id 0x7a). */
    public static byte[] biomeDefinitions() {
        byte[] local = biomeDefinitions;
        if (local == null) biomeDefinitions = local = loadResource("biome_definitions.bin");
        return local;
    }

    /** CreativeContent body (packet id 0x91). */
    public static byte[] creativeContent() {
        byte[] local = creativeContent;
        if (local == null) creativeContent = local = loadResource("creative_items.bin");
        return local;
    }

    /** CraftingData body (packet id 0x34). */
    public static byte[] craftingData() {
        byte[] local = craftingData;
        if (local == null) craftingData = local = loadResource("recipes.bin");
        return local;
    }

    /** AvailableActorIdentifiers body (packet id 0x77). */
    public static byte[] actorIdentifiers() {
        byte[] local = actorIdentifiers;
        if (local == null) actorIdentifiers = local = loadResource("entity_identifiers.bin");
        return local;
    }

    /** AvailableCommands body (packet id 0x4c). MVP: lift the BDS capture verbatim. */
    public static byte[] availableCommands() {
        byte[] local = availableCommands;
        if (local == null) availableCommands = local = loadResource("available_commands.bin");
        return local;
    }
    private static volatile byte[] availableCommands;

    private static byte[] loadResource(String name) {
        String path = BASE + name;
        try (InputStream in = RegistryDataPackets.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("registry data missing: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + path, e);
        }
    }
}
