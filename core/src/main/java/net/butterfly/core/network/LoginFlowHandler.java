package net.butterfly.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.codec.Packet;
import net.butterfly.codec.PacketIds;
import net.butterfly.codec.packets.ClientToServerHandshakePacket;
import net.butterfly.codec.packets.NetworkSettingsPacket;
import net.butterfly.codec.packets.PlayStatusPacket;
import net.butterfly.codec.packets.RawCapturePacket;
import net.butterfly.codec.packets.RequestNetworkSettingsPacket;
import net.butterfly.codec.packets.ServerToClientHandshakePacket;
import net.butterfly.crypto.EcdhKeyExchange;
import net.butterfly.nbt.VarInts;
import net.butterfly.raknet.RakConstants;
import net.butterfly.raknet.online.ConnectedHandshake;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_C2S_HANDSHAKE;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_LOGIN;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_REQUEST_NETWORK_SETTINGS;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_RESOURCE_PACK_RESPONSE;
import static net.butterfly.core.network.ClientSession.LoginState.INGAME;

/**
 * Drives the post-RakNet Bedrock login state machine for a single {@link ClientSession}.
 *
 * <p>State transitions follow the canonical Bedrock handshake:
 * <ol>
 *   <li>{@code AWAIT_REQUEST_NETWORK_SETTINGS}: handle Connection Request (control packet,
 *       reply with Connection Request Accepted) and RequestNetworkSettings (game packet,
 *       reply with NetworkSettings + flip outbound compression on AFTER the reply).
 *       Transitions to {@code AWAIT_LOGIN}.</li>
 *   <li>{@code AWAIT_LOGIN}: parse the Login chain via {@link LoginParser}, build a
 *       handshake JWT carrying our P-384 public key + a fresh 16-byte salt, send it
 *       AS-IS (unencrypted), then derive the AES-256 key and enable encryption — outbound
 *       AFTER the handshake JWT is sent, inbound immediately. Transitions to
 *       {@code AWAIT_C2S_HANDSHAKE}.</li>
 *   <li>{@code AWAIT_C2S_HANDSHAKE}: on ClientToServerHandshake, send PlayStatus
 *       (LOGIN_SUCCESS) followed by ResourcePacksInfo. Transitions to
 *       {@code AWAIT_RESOURCE_PACK_RESPONSE}.</li>
 *   <li>{@code AWAIT_RESOURCE_PACK_RESPONSE}: on ResourcePackClientResponse with
 *       status=COMPLETED, send ResourcePackStack. The client then sends a second
 *       ResourcePackClientResponse with status=COMPLETED again, on which we send a
 *       placeholder StartGame and transition to {@code INGAME}.</li>
 * </ol>
 *
 * <p>The handler subscribes to the session's RakNet control channel (via
 * {@code rak.onControlPacket}) for Connection Request / Connected Ping, and to the
 * decoded packet stream (via {@code session.onPackets}) for game-flow packets. RakNet
 * wiring is the caller's responsibility — see {@link #attach()}.
 */
public final class LoginFlowHandler {
    private static final Logger log = LoggerFactory.getLogger(LoginFlowHandler.class);

    /** Server-wide entity runtime id allocator. Player 1 gets 1, etc. */
    private static final AtomicLong RUNTIME_ID_SEQ = new AtomicLong(0L);

    /** Server identity shared across all sessions — generated once at startup. */
    public record ServerIdentity(KeyPair keyPair) {
        public static ServerIdentity generate() { return new ServerIdentity(EcdhKeyExchange.generateKeyPair()); }
    }

    private final ClientSession session;
    private final ServerIdentity serverIdentity;

    /** Fresh ECDH salt generated at the handshake step; kept until enableEncryption fires. */
    private byte[] handshakeSalt;

    /**
     * How many ResourcePackClientResponse(status=COMPLETED) we've seen. The client sends
     * one in response to ResourcePacksInfo and a second in response to ResourcePackStack;
     * we send the stack on the first and StartGame on the second.
     */
    private int resourcePackCompletedCount = 0;

    public LoginFlowHandler(ClientSession session, ServerIdentity serverIdentity) {
        this.session = session;
        this.serverIdentity = serverIdentity;
    }

    public ClientSession session() { return session; }

    /**
     * Wire this handler to its session's RakNet control / game callbacks. Idempotent —
     * call once after creating the session. Tests typically skip this and feed packets
     * directly via {@link #handleInboundPackets(List)} to avoid touching Netty.
     */
    public void attach() {
        if (session.rak() != null) {
            session.rak().onControlPacket(this::handleControlPacket);
            session.rak().onGameBatch(buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                session.handleInboundBatch(bytes);
            });
        }
        session.onPackets(this::handleInboundPackets);
    }

    // ---- RakNet control plane ----

    /** Handle a connected RakNet control packet (Connection Request, Connected Ping). */
    public void handleControlPacket(ByteBuf payload) {
        int id = payload.getUnsignedByte(payload.readerIndex());
        if (id == RakConstants.ID_CONNECTION_REQUEST) {
            ConnectedHandshake.ConnectionRequest req = ConnectedHandshake.readConnectionRequest(payload);
            if (session.rak() != null) {
                ByteBuf out = Unpooled.buffer();
                ConnectedHandshake.writeConnectionRequestAccepted(out, session.rak().remote(),
                    req.timestamp(), System.currentTimeMillis());
                session.rak().sendControl(out);
            }
        } else if (id == RakConstants.ID_CONNECTED_PING) {
            ConnectedHandshake.ConnectedPing ping = ConnectedHandshake.readConnectedPing(payload);
            if (session.rak() != null) {
                ByteBuf out = Unpooled.buffer();
                ConnectedHandshake.writeConnectedPong(out, ping.time(), System.currentTimeMillis());
                session.rak().sendControl(out);
            }
        }
        // New Incoming Connection (0x13) has no required response.
    }

    // ---- Bedrock packet plane ----

    /** Receive a fully-decoded batch and dispatch each packet through the state machine. */
    public void handleInboundPackets(List<Packet> packets) {
        for (Packet packet : packets) handleInboundPacket(packet);
    }

    private void handleInboundPacket(Packet packet) {
        switch (session.state()) {
            case AWAIT_REQUEST_NETWORK_SETTINGS -> handleAwaitRequestNetworkSettings(packet);
            case AWAIT_LOGIN -> handleAwaitLogin(packet);
            case AWAIT_C2S_HANDSHAKE -> handleAwaitC2sHandshake(packet);
            case AWAIT_RESOURCE_PACK_RESPONSE -> handleAwaitResourcePackResponse(packet);
            case INGAME -> handleIngame(packet);
        }
    }

    // ---- State 1: AWAIT_REQUEST_NETWORK_SETTINGS ----

    private void handleAwaitRequestNetworkSettings(Packet packet) {
        if (packet.packetId() != PacketIds.REQUEST_NETWORK_SETTINGS) {
            log.warn("unexpected packet id 0x{} in {}",
                Integer.toHexString(packet.packetId()), session.state());
            return;
        }
        RequestNetworkSettingsPacket req = (RequestNetworkSettingsPacket) packet;
        log.info("RequestNetworkSettings: clientProtocol={}", req.clientProtocol());

        // Reply with NetworkSettings (zlib, threshold=1) — must be sent BEFORE compression flip.
        NetworkSettingsPacket ns = new NetworkSettingsPacket();
        ns.setCompressionAlgorithm(0);
        ns.setCompressionThreshold(1);
        session.sendPackets(List.of(ns));

        // Flip compression ON only after the reply has been wrapped+sent.
        session.cipher().enableCompression(0, 1);
        session.setState(AWAIT_LOGIN);
    }

    // ---- State 2: AWAIT_LOGIN ----

    private void handleAwaitLogin(Packet packet) {
        if (packet.packetId() != PacketIds.LOGIN) {
            log.warn("unexpected packet id 0x{} in {}",
                Integer.toHexString(packet.packetId()), session.state());
            return;
        }

        // Login is registered as RawCapturePacket — body is the verbatim packet body.
        RawCapturePacket login = (RawCapturePacket) packet;
        ByteBuf body = login.rawBody().duplicate();
        LoginParser.Identity identity;
        try {
            identity = LoginParser.parse(body);
        } catch (RuntimeException e) {
            log.warn("login parse failed: {}", e.toString());
            return;
        }
        session.setDisplayName(identity.displayName());
        session.setXuid(identity.xuid());
        session.setClientIdentityKey(identity.clientIdentityKey());
        log.info("Login from {} (xuid={}, protocol={})",
            identity.displayName(), identity.xuid(), identity.clientProtocol());

        // Build & send the server-to-client handshake JWT (unencrypted).
        this.handshakeSalt = EcdhKeyExchange.randomSalt();
        String jwt = buildHandshakeJwt(serverIdentity.keyPair(), handshakeSalt);
        ServerToClientHandshakePacket hs = new ServerToClientHandshakePacket();
        hs.setJwt(jwt);
        session.sendPackets(List.of(hs));

        // Derive the AES-256 key. Outbound flips ONLY after the JWT is wrapped+sent above;
        // inbound flips immediately (the client encrypts its very next batch).
        byte[] key = EcdhKeyExchange.deriveKey(
            serverIdentity.keyPair().getPrivate(),
            identity.clientIdentityKey(),
            handshakeSalt);
        session.cipher().enableInboundEncryption(key);
        session.cipher().enableOutboundEncryption(key);
        session.setState(AWAIT_C2S_HANDSHAKE);
    }

    private static String buildHandshakeJwt(KeyPair signer, byte[] salt) {
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
            jws.setHeader("x5u", EcdhKeyExchange.encodePublicKey(signer.getPublic()));
            jws.setPayload("{\"salt\":\"" + Base64.getEncoder().encodeToString(salt) + "\"}");
            jws.setKey(signer.getPrivate());
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException("could not build server handshake JWT", e);
        }
    }

    // ---- State 3: AWAIT_C2S_HANDSHAKE ----

    private void handleAwaitC2sHandshake(Packet packet) {
        if (packet.packetId() != PacketIds.CLIENT_TO_SERVER_HANDSHAKE) {
            log.warn("unexpected packet id 0x{} in {}",
                Integer.toHexString(packet.packetId()), session.state());
            return;
        }
        // ClientToServerHandshakePacket has an empty body — its arrival alone is the signal.
        if (!(packet instanceof ClientToServerHandshakePacket)) return;
        log.info("client confirmed encryption");

        // Send PlayStatus(LOGIN_SUCCESS) + an empty ResourcePacksInfo.
        PlayStatusPacket ps = new PlayStatusPacket();
        ps.setStatus(PlayStatusPacket.LOGIN_SUCCESS);

        RawCapturePacket info = new RawCapturePacket(PacketIds.RESOURCE_PACKS_INFO);
        info.decode(Unpooled.wrappedBuffer(buildEmptyResourcePacksInfoBody()));

        session.sendPackets(List.of(ps, info));
        session.setState(AWAIT_RESOURCE_PACK_RESPONSE);
    }

    /**
     * ResourcePacksInfo (0x06) minimal body for protocol 975:
     *   bool   resourcePackRequired = false
     *   bool   hasAddons            = false
     *   bool   hasScripts           = false
     *   short  LE behaviourPackCount = 0
     *   short  LE resourcePackCount  = 0
     *
     * <p>This is enough to let a happy-path client move on to ResourcePackClientResponse
     * with status=COMPLETED.
     */
    private static byte[] buildEmptyResourcePacksInfoBody() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBoolean(false);    // resourcePackRequired
        buf.writeBoolean(false);    // hasAddons
        buf.writeBoolean(false);    // hasScripts
        buf.writeShortLE(0);        // behaviour pack count
        buf.writeShortLE(0);        // resource pack count
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    // ---- State 4: AWAIT_RESOURCE_PACK_RESPONSE ----

    private void handleAwaitResourcePackResponse(Packet packet) {
        if (packet.packetId() != PacketIds.RESOURCE_PACK_CLIENT_RESPONSE) {
            log.warn("unexpected packet id 0x{} in {}",
                Integer.toHexString(packet.packetId()), session.state());
            return;
        }
        RawCapturePacket resp = (RawCapturePacket) packet;
        ByteBuf body = resp.rawBody().duplicate();
        if (!body.isReadable()) return;
        int status = body.readUnsignedByte();   // 0=Refused, 2=SendPacks, 3=HaveAllPacks, 4=Completed
        if (status != 4) {
            log.info("ResourcePackClientResponse status={} (waiting for COMPLETED)", status);
            return;
        }

        resourcePackCompletedCount++;
        if (resourcePackCompletedCount == 1) {
            // First COMPLETED: respond with ResourcePackStack and stay in this state.
            RawCapturePacket stack = new RawCapturePacket(PacketIds.RESOURCE_PACK_STACK);
            stack.decode(Unpooled.wrappedBuffer(buildEmptyResourcePackStackBody()));
            session.sendPackets(List.of(stack));
            return;
        }

        // Second COMPLETED: client has accepted the stack — send StartGame, the
        // registry packets, and PlayStatus(PLAYER_SPAWN) so the client can move on.
        sendStartGameAndRegistries();
        session.setState(INGAME);
    }

    /**
     * ResourcePackStack (0x07) minimal body for protocol 975:
     *   bool      texturePackRequired       = false
     *   varuint   behaviourPackCount        = 0
     *   varuint   resourcePackCount         = 0
     *   string    gameVersion               = "1.21.0"
     *   int32 LE  experimentCount           = 0
     *   bool      experimentsToggled        = false
     */
    private static byte[] buildEmptyResourcePackStackBody() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBoolean(false);                     // texturePackRequired
        VarInts.writeUnsignedInt(buf, 0);            // behaviourPackCount
        VarInts.writeUnsignedInt(buf, 0);            // resourcePackCount
        byte[] gameVersion = "1.21.0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        VarInts.writeUnsignedInt(buf, gameVersion.length);
        buf.writeBytes(gameVersion);
        buf.writeIntLE(0);                           // experimentCount
        buf.writeBoolean(false);                     // experimentsToggled
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /**
     * Send a real StartGame body (loaded from {@code butterfly_data/v975/start_game.bin}
     * with the player's session-specific fields patched in), followed by the
     * post-StartGame registry packets and a {@link PlayStatusPacket#PLAYER_SPAWN}.
     *
     * <p>Order matters: the client expects StartGame before any of the registry
     * packets, and PLAYER_SPAWN after the registries. The MVP sends them all in a
     * single batch; later we'll defer PLAYER_SPAWN until after the client has
     * requested its initial chunks.
     *
     * <p>Identity sources:
     * <ul>
     *   <li>{@code entityUniqueId}  — derived from the client's identity public key
     *       hashCode for stability across reconnects within a process.</li>
     *   <li>{@code entityRuntimeId} — pulled from a process-wide
     *       {@link AtomicLong}; the first joining player gets 1.</li>
     *   <li>spawn position — fixed {@code (0, 100, 0)} until we have a world.</li>
     * </ul>
     */
    private void sendStartGameAndRegistries() {
        long entityUniqueId = session.clientIdentityKey() != null
            ? session.clientIdentityKey().hashCode()
            : System.nanoTime();
        long entityRuntimeId = RUNTIME_ID_SEQ.incrementAndGet();

        byte[] startGameBody = StartGameTemplate.withDynamicFields(
            entityUniqueId, entityRuntimeId, 0f, 100f, 0f);

        RawCapturePacket startGame = new RawCapturePacket(PacketIds.START_GAME);
        startGame.decode(Unpooled.wrappedBuffer(startGameBody));

        RawCapturePacket itemRegistry = new RawCapturePacket(PacketIds.ITEM_REGISTRY);
        itemRegistry.decode(Unpooled.wrappedBuffer(RegistryDataPackets.itemRegistry()));

        RawCapturePacket biomes = new RawCapturePacket(PacketIds.BIOME_DEFINITION_LIST);
        biomes.decode(Unpooled.wrappedBuffer(RegistryDataPackets.biomeDefinitions()));

        RawCapturePacket creative = new RawCapturePacket(PacketIds.CREATIVE_CONTENT);
        creative.decode(Unpooled.wrappedBuffer(RegistryDataPackets.creativeContent()));

        RawCapturePacket recipes = new RawCapturePacket(PacketIds.CRAFTING_DATA);
        recipes.decode(Unpooled.wrappedBuffer(RegistryDataPackets.craftingData()));

        RawCapturePacket actors = new RawCapturePacket(PacketIds.AVAILABLE_ACTOR_IDENTIFIERS);
        actors.decode(Unpooled.wrappedBuffer(RegistryDataPackets.actorIdentifiers()));

        PlayStatusPacket spawn = new PlayStatusPacket();
        spawn.setStatus(PlayStatusPacket.PLAYER_SPAWN);

        log.info("sending StartGame + registries (uniqueId={}, runtimeId={})",
            entityUniqueId, entityRuntimeId);
        session.sendPackets(List.of(startGame, itemRegistry, biomes, creative, recipes, actors, spawn));
    }

    // ---- State 5: INGAME ----

    private void handleIngame(Packet packet) {
        // Plan B will route ingame packets to the world / player layer. For now we only
        // log unknown packets so the rest of the test surface stays predictable.
        log.debug("ingame packet id=0x{}", Integer.toHexString(packet.packetId()));
    }
}
