package net.butterfly.core.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.codec.BatchCodec;
import net.butterfly.codec.BedrockCodecs;
import net.butterfly.codec.Packet;
import net.butterfly.codec.PacketIds;
import net.butterfly.codec.PacketRegistry;
import net.butterfly.codec.packets.ClientToServerHandshakePacket;
import net.butterfly.codec.packets.NetworkSettingsPacket;
import net.butterfly.codec.packets.PlayStatusPacket;
import net.butterfly.codec.packets.RawCapturePacket;
import net.butterfly.codec.packets.RequestNetworkSettingsPacket;
import net.butterfly.codec.packets.ServerToClientHandshakePacket;
import net.butterfly.crypto.EcdhKeyExchange;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginFlowHandlerTest {

    private final PacketRegistry registry = BedrockCodecs.protocol975();
    private final BatchCodec codec = new BatchCodec(registry);

    @Test
    void requestNetworkSettingsAdvancesAndRepliesNetworkSettings() {
        Harness h = new Harness();
        h.sendInbound(makeRequestNetworkSettings());

        // One outbound batch should have been captured: NetworkSettings.
        assertEquals(1, h.outbound.size(), "expected exactly one outbound batch");
        List<Packet> reply = h.popOutbound();
        assertEquals(1, reply.size());
        assertInstanceOf(NetworkSettingsPacket.class, reply.get(0));
        NetworkSettingsPacket ns = (NetworkSettingsPacket) reply.get(0);
        assertEquals(0, ns.compressionAlgorithm());
        assertEquals(1, ns.compressionThreshold());

        assertEquals(ClientSession.LoginState.AWAIT_LOGIN, h.session.state());
        assertTrue(h.session.cipher().compressionEnabled(), "compression flipped on after reply");

        // Mirror the compression flip on the test client so subsequent batches encode the same way.
        h.clientCipher.enableCompression(0, 1);
    }

    @Test
    void loginSendsHandshakeJwtAndEnablesEncryption() {
        Harness h = new Harness();
        // Advance to AWAIT_LOGIN.
        h.sendInbound(makeRequestNetworkSettings());
        h.popOutbound();
        h.clientCipher.enableCompression(0, 1);

        KeyPair clientKey = EcdhKeyExchange.generateKeyPair();
        h.sendInbound(makeLogin(clientKey));

        // One outbound batch: the server-to-client handshake JWT.
        assertEquals(1, h.outbound.size());
        List<Packet> reply = h.popOutbound();
        assertEquals(1, reply.size());
        assertInstanceOf(ServerToClientHandshakePacket.class, reply.get(0));
        ServerToClientHandshakePacket hs = (ServerToClientHandshakePacket) reply.get(0);
        assertNotNull(hs.jwt());
        assertFalse(hs.jwt().isEmpty());

        // State + cipher should have advanced; both encrypt and decrypt are now armed.
        assertEquals(ClientSession.LoginState.AWAIT_C2S_HANDSHAKE, h.session.state());
        assertTrue(h.session.cipher().outboundEncryptionEnabled());
        assertTrue(h.session.cipher().inboundEncryptionEnabled());

        // Verify identity captured from the chain.
        assertEquals("Steve", h.session.displayName());
        assertEquals("1234567890", h.session.xuid());
        assertNotNull(h.session.clientIdentityKey());
    }

    @Test
    void clientToServerHandshakeSendsPlayStatusAndResourcePacksInfo() {
        Harness h = new Harness();
        // Advance to AWAIT_LOGIN.
        h.sendInbound(makeRequestNetworkSettings());
        h.popOutbound();
        h.clientCipher.enableCompression(0, 1);

        // Advance to AWAIT_C2S_HANDSHAKE — and derive the client-side AES key.
        KeyPair clientKey = EcdhKeyExchange.generateKeyPair();
        h.sendInbound(makeLogin(clientKey));
        List<Packet> serverHandshake = h.popOutbound();
        ServerToClientHandshakePacket hsPkt = (ServerToClientHandshakePacket) serverHandshake.get(0);

        ParsedHs parsed = parseHandshakeJwt(hsPkt.jwt());
        byte[] clientAesKey = EcdhKeyExchange.deriveKey(clientKey.getPrivate(), parsed.signerKey, parsed.salt);
        h.clientCipher.enableEncryption(clientAesKey);

        // Send C2S handshake (empty body) — server should reply with PlayStatus + ResourcePacksInfo.
        h.sendInbound(new ClientToServerHandshakePacket());

        assertEquals(1, h.outbound.size());
        List<Packet> reply = h.popOutbound();
        assertEquals(2, reply.size());
        assertInstanceOf(PlayStatusPacket.class, reply.get(0));
        assertEquals(PlayStatusPacket.LOGIN_SUCCESS, ((PlayStatusPacket) reply.get(0)).status());
        assertInstanceOf(RawCapturePacket.class, reply.get(1));
        assertEquals(PacketIds.RESOURCE_PACKS_INFO, reply.get(1).packetId());
        assertEquals(ClientSession.LoginState.AWAIT_RESOURCE_PACK_RESPONSE, h.session.state());
    }

    // ---- harness ----

    /**
     * Drives a {@link LoginFlowHandler} at the batch-byte level. Owns a server-side
     * {@link ClientSession} (with no real RakNet attached) and a mirror {@link SessionCipher}
     * for the test client side so cipher state is symmetric.
     */
    private final class Harness {
        final ClientSession session;
        final LoginFlowHandler handler;
        final SessionCipher clientCipher = new SessionCipher();
        final List<byte[]> outbound = new ArrayList<>();

        Harness() {
            this.session = new ClientSession(null);
            this.handler = new LoginFlowHandler(session, LoginFlowHandler.ServerIdentity.generate());
            session.onPackets(handler::handleInboundPackets);
            session.setOutboundSink(outbound::add);
        }

        /** Encode a packet on the test side, wrap with the mirrored client cipher, ship to the server. */
        void sendInbound(Packet packet) {
            ByteBuf buf = Unpooled.buffer();
            codec.encode(buf, List.of(packet));
            byte[] plain = new byte[buf.readableBytes()];
            buf.readBytes(plain);
            buf.release();
            byte[] wire = clientCipher.wrap(plain);
            session.handleInboundBatch(wire);
        }

        /** Pop the oldest captured outbound wire batch, unwrap with the mirrored client cipher, decode it. */
        List<Packet> popOutbound() {
            if (outbound.isEmpty()) throw new AssertionError("no outbound captured");
            byte[] wire = outbound.remove(0);
            byte[] plain = clientCipher.unwrap(wire);
            return codec.decode(Unpooled.wrappedBuffer(plain));
        }
    }

    // ---- packet builders ----

    private static RequestNetworkSettingsPacket makeRequestNetworkSettings() {
        RequestNetworkSettingsPacket p = new RequestNetworkSettingsPacket();
        p.setClientProtocol(975);
        return p;
    }

    /** Build a Login packet wrapping a legacy single-token chain signed by clientKey. */
    private static RawCapturePacket makeLogin(KeyPair clientKey) {
        String pub = EcdhKeyExchange.encodePublicKey(clientKey.getPublic());

        Map<String, Object> extraData = new LinkedHashMap<>();
        extraData.put("XUID", "1234567890");
        extraData.put("identity", "00000000-0000-0000-0000-000000000001");
        extraData.put("displayName", "Steve");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identityPublicKey", pub);
        body.put("extraData", extraData);

        String token;
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
            jws.setHeader("x5u", pub);
            jws.setPayload(toJson(body));
            jws.setKey(clientKey.getPrivate());
            token = jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }

        JsonObject root = new JsonObject();
        JsonArray chain = new JsonArray();
        chain.add(token);
        root.add("chain", chain);
        byte[] loginBody = LoginParserTest.encodeLoginBody(975, root.toString(), "");

        RawCapturePacket pkt = new RawCapturePacket(PacketIds.LOGIN);
        pkt.decode(Unpooled.wrappedBuffer(loginBody));
        return pkt;
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('\"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Map) sb.append(toJson((Map<String, Object>) v));
            else if (v instanceof Number) sb.append(v);
            else sb.append('\"').append(v.toString().replace("\"", "\\\"")).append('\"');
        }
        sb.append('}');
        return sb.toString();
    }

    private record ParsedHs(PublicKey signerKey, byte[] salt) {}

    /** Parse the server's handshake JWT to extract its public key + salt — mirror of HandshakeJwt.parse. */
    private static ParsedHs parseHandshakeJwt(String token) {
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(token);
            jws.setDoKeyValidation(false);
            String x5u = jws.getHeader("x5u");
            PublicKey signerKey = EcdhKeyExchange.decodePublicKey(x5u);
            jws.setKey(signerKey);
            assertTrue(jws.verifySignature(), "server handshake JWT must verify with its own x5u");
            Map<String, Object> body = JsonUtil.parseJson(jws.getUnverifiedPayload());
            byte[] salt = Base64.getDecoder().decode((String) body.get("salt"));
            return new ParsedHs(signerKey, salt);
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }
}
