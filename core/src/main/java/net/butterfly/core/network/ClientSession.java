package net.butterfly.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.codec.BatchCodec;
import net.butterfly.codec.BedrockCodecs;
import net.butterfly.codec.Packet;
import net.butterfly.codec.PacketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-connected-peer state owned by {@link ConnectionManager}.
 *
 * <p>Holds:
 * <ul>
 *   <li>{@link RakSession} — the transport.</li>
 *   <li>{@link SessionCipher} — compression + encryption codec, mutated by
 *       {@link LoginFlowHandler} as the handshake progresses.</li>
 *   <li>{@link LoginState} — current state of the login state machine.</li>
 *   <li>Captured identity (xuid, displayName, client identity public key) populated by
 *       {@link LoginFlowHandler} once the Login packet has been parsed.</li>
 * </ul>
 *
 * <p>Wraps and unwraps Bedrock batches via the codec for protocol 975, exposing
 * {@link #handleInboundBatch(byte[])} as a hook that bypasses RakNet — used in tests and
 * by the listener's {@code onGameBatch} callback.
 */
public final class ClientSession {
    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    /** Login state machine — tracks where we are in the post-RakNet Bedrock handshake. */
    public enum LoginState {
        AWAIT_REQUEST_NETWORK_SETTINGS,
        AWAIT_LOGIN,
        AWAIT_C2S_HANDSHAKE,
        AWAIT_RESOURCE_PACK_RESPONSE,
        INGAME
    }

    private final RakSession rak;
    private final SessionCipher cipher = new SessionCipher();
    private final PacketRegistry registry = BedrockCodecs.protocol975();
    private final BatchCodec codec = new BatchCodec(registry);

    private LoginState state = LoginState.AWAIT_REQUEST_NETWORK_SETTINGS;

    private String displayName = "";
    private String xuid = "";
    private java.security.PublicKey clientIdentityKey;

    /** Sink for decoded packets in an inbound batch — set by {@link LoginFlowHandler}. */
    private Consumer<List<Packet>> onPackets = ps -> {};

    /**
     * Sink for wire-format outbound batches (post-cipher.wrap). Defaults to
     * {@code rak::sendGameBatch}; tests replace it with a list-capturing lambda
     * to drive the state machine without touching Netty.
     */
    private Consumer<byte[]> outboundSink;

    public ClientSession(RakSession rak) {
        this.rak = rak;
        this.outboundSink = rak != null ? rak::sendGameBatch : bytes -> {};
    }

    public RakSession rak() { return rak; }
    public SessionCipher cipher() { return cipher; }
    public PacketRegistry registry() { return registry; }
    public BatchCodec codec() { return codec; }

    public LoginState state() { return state; }
    public void setState(LoginState s) { this.state = s; }

    public String displayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }

    public String xuid() { return xuid; }
    public void setXuid(String v) { this.xuid = v; }

    public java.security.PublicKey clientIdentityKey() { return clientIdentityKey; }
    public void setClientIdentityKey(java.security.PublicKey k) { this.clientIdentityKey = k; }

    public void onPackets(Consumer<List<Packet>> sink) { this.onPackets = sink; }

    /** Test hook: replace the wire sink so encoded batches go to a list instead of RakNet. */
    public void setOutboundSink(Consumer<byte[]> sink) { this.outboundSink = sink; }

    /**
     * Test/listener entry point: take a batch as it arrived on the wire (post-RakNet
     * reassembly), unwrap it through {@link SessionCipher} and decode all contained
     * packets, dispatching them to whoever subscribed via {@link #onPackets}.
     */
    public void handleInboundBatch(byte[] wireBytes) {
        byte[] plain;
        try { plain = cipher.unwrap(wireBytes); }
        catch (Exception e) {
            log.warn("inbound unwrap fail ({} bytes): {}", wireBytes.length, e.toString());
            return;
        }
        List<Packet> packets;
        try { packets = codec.decode(Unpooled.wrappedBuffer(plain)); }
        catch (Exception e) {
            log.warn("inbound decode fail ({} bytes): {}", plain.length, e.toString());
            return;
        }
        onPackets.accept(packets);
    }

    /** Encode a list of packets into a single batch, wrap via cipher, ship via RakNet. */
    public void sendPackets(List<Packet> packets) {
        ByteBuf out = Unpooled.buffer();
        codec.encode(out, packets);
        byte[] plain = new byte[out.readableBytes()];
        out.readBytes(plain);
        out.release();
        sendBatchPlain(plain);
    }

    /** Send already-encoded plaintext batch bytes — wrap (compress+encrypt) and ship. */
    public void sendBatchPlain(byte[] plainBatch) {
        byte[] wire = cipher.wrap(plainBatch);
        outboundSink.accept(wire);
    }
}
