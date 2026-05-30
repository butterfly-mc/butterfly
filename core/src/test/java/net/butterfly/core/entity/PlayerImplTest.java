package net.butterfly.core.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.codec.BatchCodec;
import net.butterfly.codec.BedrockCodecs;
import net.butterfly.codec.Packet;
import net.butterfly.codec.PacketRegistry;
import net.butterfly.codec.Strings;
import net.butterfly.codec.packets.TextPacket;
import net.butterfly.core.network.ClientSession;
import net.butterfly.core.world.LevelDb;
import net.butterfly.core.world.WorldImpl;
import net.butterfly.nbt.VarInts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlayerImpl}.
 *
 * <p>The {@code ClientSession} is constructed with {@code null} RakNet — its outbound
 * sink is replaced with a list-capturing lambda so encoded batches are observable
 * without touching the network. Compression and encryption stay off (their default
 * state on a fresh {@code SessionCipher}), so wire bytes equal plaintext batch bytes
 * and can be decoded directly via {@link BatchCodec}.
 *
 * <p>The {@code ButterflyServer} parameter to {@code PlayerImpl} is passed as
 * {@code null} — none of the under-test methods (sendMessage / sendChat / kick /
 * teleport) dereference it.
 */
class PlayerImplTest {

    private final PacketRegistry registry = BedrockCodecs.protocol975();
    private final BatchCodec codec = new BatchCodec(registry);

    @Test
    void sendMessageEncodesRawTextBatch(@TempDir Path tmp) throws IOException {
        try (Harness h = new Harness(tmp)) {
            h.player.sendMessage("hello world");

            // Exactly one batch should have been pushed through the cipher pipeline.
            assertEquals(1, h.outbound.size(), "expected one outbound batch");
            List<Packet> packets = h.decode(h.outbound.get(0));
            assertEquals(1, packets.size(), "batch should contain a single packet");

            assertInstanceOf(TextPacket.class, packets.get(0));
            TextPacket text = (TextPacket) packets.get(0);
            assertEquals(TextPacket.TYPE_RAW, text.type());
            assertEquals("hello world", text.message());
            assertFalse(text.needsTranslation());
            // Source / xuid / platform / filtered fields are unused for raw messages —
            // they should serialize as empty strings.
            assertEquals("", text.sourceName());
            assertEquals("", text.xuid());
            assertEquals("", text.platformChatId());
            assertEquals("", text.filteredMessage());
        }
    }

    @Test
    void sendChatEncodesChatTextWithSourceAndXuid(@TempDir Path tmp) throws IOException {
        try (Harness h = new Harness(tmp)) {
            h.player.sendChat("Alex", "hi everyone");

            assertEquals(1, h.outbound.size());
            List<Packet> packets = h.decode(h.outbound.get(0));
            assertEquals(1, packets.size());
            assertInstanceOf(TextPacket.class, packets.get(0));

            TextPacket text = (TextPacket) packets.get(0);
            assertEquals(TextPacket.TYPE_CHAT, text.type());
            assertEquals("Alex", text.sourceName());
            assertEquals("hi everyone", text.message());
            // xuid mirrors the player's own xuid (so the receiver knows who really sent it).
            assertEquals("steve-xuid", text.xuid());
        }
    }

    @Test
    void kickSendsDisconnectPacketWithExpectedBody(@TempDir Path tmp) throws IOException {
        try (Harness h = new Harness(tmp)) {
            h.player.kick("Bye!");

            assertEquals(1, h.outbound.size());
            List<Packet> packets = h.decode(h.outbound.get(0));
            assertEquals(1, packets.size(), "kick should produce one disconnect packet");

            // Disconnect (0x05) is not registered in the codec's PacketRegistry, so
            // BatchCodec falls back to UnknownPacket. Re-encode it to recover the body
            // and walk the layout we wrote in PlayerImpl.kick.
            Packet pkt = packets.get(0);
            assertEquals(0x05, pkt.packetId(), "expected Disconnect packet id");

            ByteBuf body = Unpooled.buffer();
            pkt.encode(body);
            assertTrue(body.isReadable(), "disconnect body must not be empty");

            long reason = VarInts.readUnsignedInt(body);
            assertEquals(0L, reason, "expected generic disconnect reason 0");
            boolean hide = body.readBoolean();
            assertFalse(hide, "kick must not hide the disconnect screen");
            String message = Strings.read(body);
            assertEquals("Bye!", message);
            body.release();
        }
    }

    @Test
    void teleportUpdatesPositionButDoesNotSendNetwork(@TempDir Path tmp) throws IOException {
        try (Harness h = new Harness(tmp)) {
            // teleport with same world reference — MVP only updates local state.
            h.player.teleport(h.world, 12.5, 64.0, -7.5);

            assertEquals(12.5, h.player.x(), 0.0);
            assertEquals(64.0, h.player.y(), 0.0);
            assertEquals(-7.5, h.player.z(), 0.0);
            // No network packets — MovePlayer is out of scope for this milestone.
            assertEquals(0, h.outbound.size(), "teleport must not emit network packets in MVP");
        }
    }

    @Test
    void identityAccessorsReturnConstructorValues(@TempDir Path tmp) throws IOException {
        try (Harness h = new Harness(tmp)) {
            assertEquals("Steve", h.player.name());
            assertEquals("steve-xuid", h.player.xuid());
            assertNotNull(h.player.uuid());
            assertSame(h.world, h.player.world());
            assertEquals(net.butterfly.api.entity.EntityType.PLAYER, h.player.type());
        }
    }

    // ---- harness ----

    /**
     * Wires up a {@link PlayerImpl} pointing at a {@link ClientSession} whose RakNet
     * is {@code null} and whose outbound sink captures encoded batches. A real
     * {@link WorldImpl} is constructed against a {@link TempDir}-backed LevelDB so
     * teleport assertions don't trip the {@code requireNonNull} in
     * {@link PlayerImpl#teleport}. Server is passed as {@code null} — the
     * under-test code paths do not touch it.
     */
    private final class Harness implements AutoCloseable {
        final ClientSession session;
        final List<byte[]> outbound = new ArrayList<>();
        final LevelDb db;
        final WorldImpl world;
        final PlayerImpl player;

        Harness(Path tmp) throws IOException {
            this.db = LevelDb.open(tmp.resolve("db"));
            this.world = new WorldImpl("overworld", db, 0, Thread.currentThread());
            this.session = new ClientSession(null);
            session.setOutboundSink(outbound::add);
            // Compression + encryption are off by default; wire bytes == plaintext batch bytes.
            this.player = new PlayerImpl(
                "Steve",
                "steve-xuid",
                UUID.randomUUID(),
                world,
                session,
                /* server = */ null,
                0.0, 64.0, 0.0);
        }

        List<Packet> decode(byte[] wire) {
            // Cipher is in pass-through mode — wire bytes are the encoded batch directly.
            return codec.decode(Unpooled.wrappedBuffer(wire));
        }

        @Override
        public void close() throws IOException {
            world.close();
        }
    }
}
