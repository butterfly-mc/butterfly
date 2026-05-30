package net.butterfly.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.nbt.VarInts;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loader / patcher for the captured BDS StartGame template.
 *
 * <p>The captured body is bundled as a classpath resource at
 * {@code /butterfly_data/v975/start_game.bin} (1002 bytes for protocol 975). It
 * encodes a real, full StartGame body — game rules, blocks palette, propertyData
 * NBT, world template UUID, and so on — for a freshly-spawned player on BDS.
 *
 * <p>Three leading fields are session-specific and must be replaced before we
 * ship the body to a client:
 * <ol>
 *   <li>{@code varlong  entityUniqueId}   — the unique id of the joining player.</li>
 *   <li>{@code varulong entityRuntimeId}  — the runtime id of the joining player.</li>
 *   <li>{@code float[5] playerPosition + angles} — spawn coordinates &amp; facing.</li>
 * </ol>
 * {@code playerGameMode} (a single zigzag varint between the runtime id and the
 * floats) is round-tripped from the template — we don't change it.
 *
 * <p>Everything after the trailing pitch/yaw is copied verbatim. The trailing
 * portion contains world seed, biome registry, game rules, blocks palette,
 * NBT property data, etc. — all of which are static for a given protocol version
 * and must not be perturbed.
 *
 * <p>This class is thread-safe: the cached template byte[] is read-only after
 * publication via the volatile field, and {@link #withDynamicFields} produces a
 * fresh byte[] per call.
 */
public final class StartGameTemplate {
    private static final String RESOURCE_PATH = "/butterfly_data/v975/start_game.bin";

    /** Cached template bytes — loaded lazily, never mutated. */
    private static volatile byte[] cached;

    private StartGameTemplate() {}

    /**
     * Read the embedded StartGame template from the classpath. Cached after the
     * first successful load; subsequent calls reuse the same array (callers must
     * not mutate the returned reference).
     *
     * @throws IllegalStateException if the resource is missing from the classpath.
     */
    public static byte[] load() {
        byte[] local = cached;
        if (local != null) return local;
        synchronized (StartGameTemplate.class) {
            if (cached != null) return cached;
            try (InputStream in = StartGameTemplate.class.getResourceAsStream(RESOURCE_PATH)) {
                if (in == null) {
                    throw new IllegalStateException("StartGame template missing: " + RESOURCE_PATH);
                }
                cached = in.readAllBytes();
                return cached;
            } catch (IOException e) {
                throw new IllegalStateException("failed to load StartGame template", e);
            }
        }
    }

    /**
     * Build a per-session StartGame body by patching the leading session fields
     * onto the cached template. The trailing portion (world data) is copied
     * verbatim.
     *
     * @param entityUniqueId  the new {@code entityUniqueId} (varlong, zigzag).
     * @param entityRuntimeId the new {@code entityRuntimeId} (varulong).
     * @param x               new spawn X (LE float).
     * @param y               new spawn Y (LE float).
     * @param z               new spawn Z (LE float).
     * @return a fresh byte[] with the patched header followed by the original tail.
     */
    public static byte[] withDynamicFields(long entityUniqueId, long entityRuntimeId,
                                           float x, float y, float z) {
        byte[] template = load();
        ByteBuf src = Unpooled.wrappedBuffer(template);

        // Read & discard the session-specific leading fields, recovering the
        // values we want to round-trip (playerGameMode, pitch, yaw).
        VarInts.readLong(src);                      // original entityUniqueId
        VarInts.readUnsignedLong(src);              // original entityRuntimeId
        int playerGameMode = VarInts.readInt(src);  // round-trip
        src.readFloatLE();                          // original x — discard
        src.readFloatLE();                          // original y — discard
        src.readFloatLE();                          // original z — discard
        float pitch = src.readFloatLE();            // round-trip
        float yaw = src.readFloatLE();              // round-trip

        // The remainder of `src` is the trailing world data we copy verbatim.
        int trailingLen = src.readableBytes();
        byte[] trailing = new byte[trailingLen];
        src.readBytes(trailing);

        // Compose the new body: patched header + original tail.
        ByteBuf out = Unpooled.buffer(template.length + 16);
        try {
            VarInts.writeLong(out, entityUniqueId);
            VarInts.writeUnsignedLong(out, entityRuntimeId);
            VarInts.writeInt(out, playerGameMode);
            out.writeFloatLE(x);
            out.writeFloatLE(y);
            out.writeFloatLE(z);
            out.writeFloatLE(pitch);
            out.writeFloatLE(yaw);
            out.writeBytes(trailing);

            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            out.release();
        }
    }
}
