package net.butterfly.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.nbt.VarInts;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartGameTemplateTest {
    /** Captured BDS body length for protocol 975 — change if the asset is recaptured. */
    private static final int EXPECTED_TEMPLATE_LEN = 1002;

    @Test
    void load_returnsExpectedSize() {
        byte[] template = StartGameTemplate.load();
        assertNotNull(template);
        assertEquals(EXPECTED_TEMPLATE_LEN, template.length);
    }

    @Test
    void load_isCached() {
        // Two consecutive calls should return the same array reference.
        byte[] a = StartGameTemplate.load();
        byte[] b = StartGameTemplate.load();
        assertTrue(a == b, "load() must return the cached array on the second call");
    }

    @Test
    void withDynamicFields_patchesHeaderAndPreservesTrailing() throws Exception {
        long newUid = 123L;
        long newRid = 456L;
        float newX = 1f, newY = 2f, newZ = 3f;

        byte[] patched = StartGameTemplate.withDynamicFields(newUid, newRid, newX, newY, newZ);

        // Decode the patched header and confirm the dynamic fields round-trip.
        ByteBuf decoded = Unpooled.wrappedBuffer(patched);
        assertEquals(newUid, VarInts.readLong(decoded));
        assertEquals(newRid, VarInts.readUnsignedLong(decoded));
        int patchedGameMode = VarInts.readInt(decoded);
        assertEquals(newX, decoded.readFloatLE());
        assertEquals(newY, decoded.readFloatLE());
        assertEquals(newZ, decoded.readFloatLE());
        float patchedPitch = decoded.readFloatLE();
        float patchedYaw = decoded.readFloatLE();
        int patchedTrailingLen = decoded.readableBytes();
        byte[] patchedTrailing = new byte[patchedTrailingLen];
        decoded.readBytes(patchedTrailing);

        // Read the same fields off the original template — gameMode/pitch/yaw must
        // round-trip identically; the trailing block must be byte-for-byte equal.
        byte[] original = loadOriginalTemplate();
        ByteBuf src = Unpooled.wrappedBuffer(original);
        VarInts.readLong(src);                  // entityUniqueId — discarded
        VarInts.readUnsignedLong(src);          // entityRuntimeId — discarded
        int origGameMode = VarInts.readInt(src);
        src.readFloatLE();                      // x
        src.readFloatLE();                      // y
        src.readFloatLE();                      // z
        float origPitch = src.readFloatLE();
        float origYaw = src.readFloatLE();
        int origTrailingLen = src.readableBytes();
        byte[] origTrailing = new byte[origTrailingLen];
        src.readBytes(origTrailing);

        assertEquals(origGameMode, patchedGameMode, "playerGameMode must round-trip");
        assertEquals(origPitch, patchedPitch, "pitch must round-trip");
        assertEquals(origYaw, patchedYaw, "yaw must round-trip");
        assertArrayEquals(origTrailing, patchedTrailing,
            "trailing world data must be byte-identical to the source template");
    }

    @Test
    void withDynamicFields_returnsFreshArrayPerCall() {
        byte[] a = StartGameTemplate.withDynamicFields(1L, 1L, 0f, 0f, 0f);
        byte[] b = StartGameTemplate.withDynamicFields(1L, 1L, 0f, 0f, 0f);
        assertTrue(a != b, "each call must produce a fresh byte[]");
        assertArrayEquals(a, b, "calls with identical args must produce identical bytes");
    }

    @Test
    void withDynamicFields_patchedDiffersFromOriginal() throws Exception {
        // Sanity: the patched body must not be byte-identical to the captured one
        // (the captured template uses non-zero entityUniqueId / runtimeId / pos).
        byte[] original = loadOriginalTemplate();
        byte[] patched = StartGameTemplate.withDynamicFields(1L, 1L, 0f, 100f, 0f);
        assertTrue(!Arrays.equals(original, patched),
            "patched body should differ from captured template at the header");
    }

    private static byte[] loadOriginalTemplate() throws Exception {
        try (InputStream in = StartGameTemplate.class.getResourceAsStream(
                "/butterfly_data/v975/start_game.bin")) {
            assertNotNull(in, "embedded start_game.bin resource missing");
            return in.readAllBytes();
        }
    }
}
