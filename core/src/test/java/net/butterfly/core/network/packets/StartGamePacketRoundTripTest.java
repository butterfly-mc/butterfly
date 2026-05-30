package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StartGamePacketRoundTripTest {

    @Test
    void roundTripPreservesEveryByte() throws Exception {
        byte[] capture = loadCapture();
        if (capture == null) {
            fail("no BDS StartGame capture available — populate "
                + "src/test/resources/bds_start_game_capture.bin");
        }

        StartGamePacket decoded = new StartGamePacket();
        ByteBuf in = Unpooled.wrappedBuffer(capture);
        try {
            decoded.decode(in);
        } catch (RuntimeException e) {
            int consumed = in.readerIndex();
            fail("decode failed at byte offset " + consumed + ": " + e.getMessage()
                + "\n" + hexContext(capture, consumed, 32));
        }
        if (in.readableBytes() != 0) {
            int leftover = in.readableBytes();
            int consumed = capture.length - leftover;
            fail("decode left " + leftover + " trailing bytes (consumed "
                + consumed + " / " + capture.length + ")\n"
                + hexContext(capture, consumed, 32));
        }

        ByteBuf out = Unpooled.buffer(capture.length);
        try {
            decoded.encode(out);
            byte[] reEncoded = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), reEncoded);

            if (!java.util.Arrays.equals(capture, reEncoded)) {
                int firstDiff = firstDiffIndex(capture, reEncoded);
                fail("re-encoded bytes differ from BDS capture at offset " + firstDiff
                    + " (capture len=" + capture.length + ", reEncoded len="
                    + reEncoded.length + ")\n"
                    + "expected: " + hexContext(capture, firstDiff, 32) + "\n"
                    + "actual:   " + hexContext(reEncoded, firstDiff, 32));
            }
            assertArrayEquals(capture, reEncoded);
        } finally {
            out.release();
        }
    }

    @Test
    void applyBdsDefaultsRoundTrips() {
        StartGamePacket src = new StartGamePacket().applyBdsDefaults();
        ByteBuf wire = Unpooled.buffer();
        try {
            src.encode(wire);
            byte[] body = new byte[wire.readableBytes()];
            wire.getBytes(wire.readerIndex(), body);

            StartGamePacket dec = new StartGamePacket();
            dec.decode(Unpooled.wrappedBuffer(body));

            assertEquals(src.entityUniqueId(),  dec.entityUniqueId());
            assertEquals(src.entityRuntimeId(), dec.entityRuntimeId());
            assertEquals(src.gameRules().size(), dec.gameRules().size());
            assertEquals(src.gameVersion(),    dec.gameVersion());
            assertEquals(src.levelId(),        dec.levelId());
        } finally {
            wire.release();
        }
    }

    private static byte[] loadCapture() throws IOException {
        byte[] r = readResource("/bds_start_game_capture.bin");
        if (r != null) return r;
        Path direct = Path.of("/tmp/diff/from-bds/butterfly-data/v975/start_game.bin");
        if (Files.isReadable(direct)) return Files.readAllBytes(direct);
        return null;
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream in =
                 StartGamePacketRoundTripTest.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static int firstDiffIndex(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) if (a[i] != b[i]) return i;
        return len;
    }

    private static String hexContext(byte[] data, int offset, int length) {
        int start = Math.max(0, offset - length / 2);
        int end = Math.min(data.length, offset + length / 2);
        StringBuilder sb = new StringBuilder();
        sb.append('@').append(String.format("%04x", offset)).append(": ");
        for (int i = start; i < end; i++) {
            if (i == offset) sb.append('[');
            sb.append(String.format("%02x", data[i] & 0xff));
            if (i == offset) sb.append(']');
            sb.append(' ');
        }
        return sb.toString();
    }
}
