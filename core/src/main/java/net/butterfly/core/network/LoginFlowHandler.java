package net.butterfly.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.codec.Packet;
import net.butterfly.codec.PacketIds;
import net.butterfly.codec.UnknownPacket;
import net.butterfly.codec.packets.ClientToServerHandshakePacket;
import net.butterfly.codec.packets.NetworkSettingsPacket;
import net.butterfly.codec.packets.PlayStatusPacket;
import net.butterfly.codec.packets.RawCapturePacket;
import net.butterfly.codec.packets.RequestNetworkSettingsPacket;
import net.butterfly.codec.packets.ServerToClientHandshakePacket;
import net.butterfly.codec.packets.TextPacket;
import net.butterfly.core.entity.PlayerImpl;
import net.butterfly.core.network.chunk.ChunkSender;
import net.butterfly.core.network.packets.PlayerActionPacket;
import net.butterfly.core.network.packets.PlayerAuthInputPacket;
import net.butterfly.core.network.packets.PlayerListBuilder;
import net.butterfly.core.network.packets.StartGamePacket;
import net.butterfly.core.network.packets.start_game.Vec3;
import net.butterfly.core.world.Chunk;
import net.butterfly.core.world.SubChunk;
import net.butterfly.core.world.WorldImpl;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static net.butterfly.core.network.ClientSession.LoginState.ACTIVE;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_C2S_HANDSHAKE;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_CHUNK_RADIUS_REQUEST;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_LOGIN;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_PLAYER_INIT;
import static net.butterfly.core.network.ClientSession.LoginState.AWAIT_RESOURCE_PACK_RESPONSE;

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
 *       ResourcePackClientResponse with status=COMPLETED again, on which we send
 *       StartGame plus the post-StartGame registry packets and transition to
 *       {@code AWAIT_CHUNK_RADIUS_REQUEST}.</li>
 *   <li>{@code AWAIT_CHUNK_RADIUS_REQUEST}: the client sends {@code RequestChunkRadius}
 *       (id 0x45) — we cap it at {@link #SERVER_MAX_CHUNK_RADIUS}, reply with
 *       {@code ChunkRadiusUpdated} (0x46), follow with
 *       {@code NetworkChunkPublisherUpdate} (0x79), then ship one
 *       {@code LevelChunk} per chunk in an N×N square around spawn, and finally
 *       send {@code PlayStatus(PLAYER_SPAWN)}. Transitions to
 *       {@code AWAIT_PLAYER_INIT}.</li>
 *   <li>{@code AWAIT_PLAYER_INIT}: on {@code SetLocalPlayerAsInitialised} (id 0x71)
 *       transitions to {@code ACTIVE}. The player is fully spawned in.</li>
 * </ol>
 *
 * <p>The handler subscribes to the session's RakNet control channel (via
 * {@code rak.onControlPacket}) for Connection Request / Connected Ping, and to the
 * decoded packet stream (via {@code session.onPackets}) for game-flow packets. RakNet
 * wiring is the caller's responsibility — see {@link #attach()}.
 *
 * <p>Note: {@link #SERVER_MAX_CHUNK_RADIUS} is the absolute cap on the negotiated
 * radius; for the MVP we still ship a fixed {@link #INITIAL_CHUNK_RADIUS}-sized
 * square of chunks regardless of what the client asked for. {@link WorldImpl#loadChunk}
 * is synchronous, so the chunk burst will block the simulate thread for ~25 reads
 * — acceptable for the single-player MVP.
 */
public final class LoginFlowHandler {
    private static final Logger log = LoggerFactory.getLogger(LoginFlowHandler.class);

    /** Server-wide entity runtime id allocator. Player 1 gets 1, etc. */
    private static final AtomicLong RUNTIME_ID_SEQ = new AtomicLong(0L);

    /** Hard cap on the chunk-view radius the server will hand back to the client. */
    private static final int SERVER_MAX_CHUNK_RADIUS = 8;

    /** Fixed chunk radius the MVP ships around spawn — 5×5 = 25 chunks. */
    private static final int INITIAL_CHUNK_RADIUS = 2;

    /** Spawn block coordinates. The StartGame body uses {@code (x + 0.5, y, z + 0.5)} as the player Vec3. */
    private static final int SPAWN_BLOCK_X = 0;
    private static final int SPAWN_BLOCK_Y = 100;
    private static final int SPAWN_BLOCK_Z = 0;

    /** Bedrock packet ids handled by RawCapturePacket since they are not yet
     *  registered in butterfly-protocol's {@code BedrockCodecs.protocol975()}. */
    private static final int ID_REQUEST_CHUNK_RADIUS = 0x45;
    private static final int ID_CHUNK_RADIUS_UPDATED = 0x46;
    private static final int ID_NETWORK_CHUNK_PUBLISHER_UPDATE = 0x79;
    private static final int ID_SET_LOCAL_PLAYER_AS_INITIALISED = 0x71;
    private static final int ID_CLIENT_CACHE_STATUS = 0x81;
    private static final int ID_SERVER_BOUND_LOADING_SCREEN = 0x138;
    private static final int ID_NETWORK_STACK_LATENCY = 0x9c;

    /** Packets the client sends that we safely ignore during login. */
    private static boolean isIgnorable(int packetId) {
        return packetId == ID_CLIENT_CACHE_STATUS
            || packetId == ID_SERVER_BOUND_LOADING_SCREEN
            || packetId == ID_NETWORK_STACK_LATENCY;
    }

    /** Server identity shared across all sessions — generated once at startup. */
    public record ServerIdentity(KeyPair keyPair) {
        public static ServerIdentity generate() { return new ServerIdentity(EcdhKeyExchange.generateKeyPair()); }
    }

    private final ClientSession session;
    private final ServerIdentity serverIdentity;
    private final WorldImpl world;

    /** Fresh ECDH salt generated at the handshake step; kept until enableEncryption fires. */
    private byte[] handshakeSalt;

    /**
     * Optional callback invoked once the handshake has captured the player's display name
     * and XUID — fired right before {@code AWAIT_CHUNK_RADIUS_REQUEST} is entered (i.e.
     * after StartGame + registries have been shipped). The session is no longer in a
     * partial-handshake state; the listener may safely build a {@code PlayerImpl} and
     * fire {@code PlayerJoinEvent}. May be {@code null} (default no-op).
     */
    private Consumer<ClientSession> onPlayerSpawn = s -> {};

    /**
     * Optional callback invoked when the underlying session has been disconnected — the
     * listener uses this to remove the player from the online roster and fire
     * {@code PlayerQuitEvent}. May be {@code null} (default no-op).
     */
    private Runnable onSessionClosed = () -> {};

    /**
     * How many ResourcePackClientResponse(status=COMPLETED) we've seen. The client sends
     * one in response to ResourcePacksInfo and a second in response to ResourcePackStack;
     * we send the stack on the first and StartGame on the second.
     */
    /** Set once we've fired the onPlayerSpawn callback so we don't double-admit. */
    private final java.util.concurrent.atomic.AtomicBoolean spawnedPlayerNotified =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public LoginFlowHandler(ClientSession session, ServerIdentity serverIdentity, WorldImpl world) {
        this.session = session;
        this.serverIdentity = serverIdentity;
        this.world = world;
    }

    /** Test-only constructor — login flow without a world. Chunk-burst states are unreachable. */
    public LoginFlowHandler(ClientSession session, ServerIdentity serverIdentity) {
        this(session, serverIdentity, null);
    }

    public ClientSession session() { return session; }

    /**
     * Install the spawn-time callback. Invoked once StartGame + registry packets have
     * been shipped and the session has captured a display name + XUID. Listeners
     * typically build a {@code PlayerImpl}, fire {@code PlayerPreLoginEvent} (and kick
     * if cancelled), then add the player to the online roster and fire
     * {@code PlayerJoinEvent}. Pass {@code null} to clear.
     */
    public void setOnPlayerSpawn(Consumer<ClientSession> callback) {
        this.onPlayerSpawn = callback != null ? callback : s -> {};
    }

    /**
     * Install the disconnect callback. Listeners typically remove the player from
     * the online roster and fire {@code PlayerQuitEvent}. Pass {@code null} to clear.
     */
    public void setOnSessionClosed(Runnable callback) {
        this.onSessionClosed = callback != null ? callback : () -> {};
    }

    /**
     * Notify the handler that the underlying transport has been torn down — invokes
     * the {@link #setOnSessionClosed(Runnable)} hook exactly once. Safe to call from
     * any thread; exceptions in the hook are caught and logged.
     */
    public void notifySessionClosed() {
        try {
            onSessionClosed.run();
        } catch (RuntimeException e) {
            log.warn("onSessionClosed hook threw: {}", e.toString());
        } finally {
            onSessionClosed = () -> {};   // one-shot
        }
    }

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
        for (Packet packet : packets) {
            if (isIgnorable(packet.packetId())) continue;
            handleInboundPacket(packet);
        }
    }

    private void handleInboundPacket(Packet packet) {
        switch (session.state()) {
            case AWAIT_REQUEST_NETWORK_SETTINGS -> handleAwaitRequestNetworkSettings(packet);
            case AWAIT_LOGIN -> handleAwaitLogin(packet);
            case AWAIT_C2S_HANDSHAKE -> handleAwaitC2sHandshake(packet);
            case AWAIT_RESOURCE_PACK_RESPONSE -> handleAwaitResourcePackResponse(packet);
            case AWAIT_CHUNK_RADIUS_REQUEST -> handleAwaitChunkRadiusRequest(packet);
            case AWAIT_PLAYER_INIT -> handleAwaitPlayerInit(packet);
            case ACTIVE -> handleActive(packet);
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
     * ResourcePacksInfo (0x06) minimal body for protocol 975 (gophertunnel layout):
     *   bool   texturePackRequired
     *   bool   hasAddons
     *   bool   hasScripts
     *   bool   forceDisableVibrantVisuals
     *   UUID   worldTemplateUUID         (16 bytes)
     *   string worldTemplateVersion      (varint length-prefixed)
     *   uint16 LE texturePackCount       = 0
     *
     * <p>This is enough to let a happy-path client move on to ResourcePackClientResponse
     * with status=COMPLETED.
     */
    private static byte[] buildEmptyResourcePacksInfoBody() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBoolean(false);             // texturePackRequired
        buf.writeBoolean(false);             // hasAddons
        buf.writeBoolean(false);             // hasScripts
        buf.writeBoolean(false);             // forceDisableVibrantVisuals
        buf.writeLongLE(0L); buf.writeLongLE(0L); // worldTemplateUUID = zero
        buf.writeByte(0);                    // worldTemplateVersion = empty (varint length = 0)
        buf.writeShortLE(0);                 // texturePackCount
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
        // gophertunnel ResourcePackClientResponse status:
        //   1 = Refused, 2 = SendPacks, 3 = AllPacksDownloaded, 4 = Completed
        int status = body.readUnsignedByte();
        log.info("ResourcePackClientResponse status={}", status);
        switch (status) {
            case 3 -> {
                // Client says it has all packs. Reply with ResourcePackStack and wait for status=4.
                RawCapturePacket stack = new RawCapturePacket(PacketIds.RESOURCE_PACK_STACK);
                stack.decode(Unpooled.wrappedBuffer(buildEmptyResourcePackStackBody()));
                session.sendPackets(List.of(stack));
            }
            case 4 -> {
                // Client confirmed the stack — send StartGame + registry packets,
                // then immediately ship the initial chunk burst around spawn.
                // (Modern Bedrock clients no longer send RequestChunkRadius before
                // entering the world; they expect chunks proactively after StartGame.)
                sendStartGameAndRegistries();
                sendInitialChunkBurst();
                session.setState(AWAIT_PLAYER_INIT);
                // identity is known; let the listener build the PlayerImpl and admit them.
                if (onPlayerSpawn != null && spawnedPlayerNotified.compareAndSet(false, true)) {
                    onPlayerSpawn.accept(session);
                }
            }
            default -> log.warn("ResourcePackClientResponse unhandled status={}", status);
        }
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
        VarInts.writeUnsignedInt(buf, 0);            // texturePacks count = 0 (gophertunnel: single slice)
        byte[] gameVersion = net.butterfly.codec.Protocol.MINECRAFT_VERSION
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        VarInts.writeUnsignedInt(buf, gameVersion.length);
        buf.writeBytes(gameVersion);
        buf.writeIntLE(0);                           // experimentCount (uint32 LE prefix)
        buf.writeBoolean(false);                     // experimentsPreviouslyToggled
        buf.writeBoolean(false);                     // includeEditorPacks
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /**
     * Send a hand-rolled StartGame body via {@link StartGamePacket}, followed by the
     * post-StartGame registry packets.
     *
     * <p>This replaces the old template-patching path ({@code StartGameTemplate})
     * which was unsafe — patching the leading entity-id varints in place shifted
     * every trailing field whenever the new varint width differed from the
     * captured one. The encoder now produces bytes from typed fields, so the
     * leading IDs and trailing payload stay consistent.
     *
     * <p>Order matters: the client expects StartGame before any of the registry
     * packets. PLAYER_SPAWN is <em>not</em> sent here — it's deferred until after
     * the chunk burst (see {@link #handleAwaitChunkRadiusRequest}) so the client
     * doesn't render the player into the void.
     *
     * <p>Identity sources:
     * <ul>
     *   <li>{@code entityUniqueId}  — derived from the client's identity public key
     *       hashCode for stability across reconnects within a process.</li>
     *   <li>{@code entityRuntimeId} — pulled from a process-wide
     *       {@link AtomicLong}; the first joining player gets 1.</li>
     *   <li>spawn position — fixed {@code (0.5, 100, 0.5)} until we have a world.</li>
     * </ul>
     */
    private void sendStartGameAndRegistries() {
        long entityUniqueId = session.clientIdentityKey() != null
            ? session.clientIdentityKey().hashCode()
            : System.nanoTime();
        long entityRuntimeId = RUNTIME_ID_SEQ.incrementAndGet();

        // Build a real StartGame packet from the BDS-derived defaults and patch in
        // the per-session fields. This replaces the legacy template-patching path
        // ({@link StartGameTemplate#withDynamicFields}) which silently corrupted
        // the trailing fields whenever the new entity-id varint width differed from
        // the captured one.
        StartGamePacket startGamePkt = new StartGamePacket()
            .applyBdsDefaults()
            .setEntityUniqueId(entityUniqueId)
            .setEntityRuntimeId(entityRuntimeId)
            .setPlayerPosition(new Vec3(SPAWN_BLOCK_X + 0.5f, SPAWN_BLOCK_Y, SPAWN_BLOCK_Z + 0.5f));
        ByteBuf startGameBody = Unpooled.buffer();
        try {
            startGamePkt.encode(startGameBody);
            byte[] body = new byte[startGameBody.readableBytes()];
            startGameBody.readBytes(body);
            RawCapturePacket startGame = new RawCapturePacket(PacketIds.START_GAME);
            startGame.decode(Unpooled.wrappedBuffer(body));

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

            RawCapturePacket cmds = new RawCapturePacket(PacketIds.AVAILABLE_COMMANDS);
            cmds.decode(Unpooled.wrappedBuffer(RegistryDataPackets.availableCommands()));

            log.info("sending StartGame + registries (uniqueId={}, runtimeId={})",
                entityUniqueId, entityRuntimeId);
            session.sendPackets(List.of(startGame, itemRegistry, biomes, creative, recipes, actors, cmds));
        } finally {
            startGameBody.release();
        }
    }

    // ---- State 5: AWAIT_CHUNK_RADIUS_REQUEST ----

    /**
     * The client sends {@code RequestChunkRadius} (id 0x45) as soon as it's done
     * digesting StartGame + the registry packets. The body is a single zigzag
     * varint — the client's preferred view radius (chunks).
     *
     * <p>We respond with:
     * <ol>
     *   <li>{@code ChunkRadiusUpdated} (0x46) — the radius the server actually
     *       allows ({@code min(requested, SERVER_MAX_CHUNK_RADIUS)}).</li>
     *   <li>{@code NetworkChunkPublisherUpdate} (0x79) — the center + radius of
     *       the cube of chunks we're about to send. Radius is in BLOCKS.</li>
     *   <li>{@link #INITIAL_CHUNK_RADIUS}-sized square of {@code LevelChunk} (0x3a)
     *       packets centered on (0,0).</li>
     *   <li>{@code PlayStatus(PLAYER_SPAWN)} — only after the burst, so the client
     *       has terrain to render the player into.</li>
     * </ol>
     */
    private void handleAwaitChunkRadiusRequest(Packet packet) {
        if (packet.packetId() != ID_REQUEST_CHUNK_RADIUS) {
            log.warn("unexpected packet id 0x{} in {}",
                Integer.toHexString(packet.packetId()), session.state());
            return;
        }
        if (!(packet instanceof RawCapturePacket raw)) return;

        int requestedRadius;
        try {
            ByteBuf body = raw.rawBody().duplicate();
            requestedRadius = body.isReadable() ? VarInts.readInt(body) : SERVER_MAX_CHUNK_RADIUS;
        } catch (RuntimeException e) {
            log.warn("RequestChunkRadius parse failed: {}", e.toString());
            requestedRadius = SERVER_MAX_CHUNK_RADIUS;
        }
        int negotiatedRadius = Math.min(requestedRadius, SERVER_MAX_CHUNK_RADIUS);
        log.info("RequestChunkRadius: requested={}, granted={} (already burst — sending radius update only)",
            requestedRadius, negotiatedRadius);

        // The client may still send RequestChunkRadius after we've already burst chunks.
        // Just acknowledge with the agreed radius — chunks are already on the wire.
        RawCapturePacket radiusUpdated = new RawCapturePacket(ID_CHUNK_RADIUS_UPDATED);
        radiusUpdated.decode(Unpooled.wrappedBuffer(ChunkSender.chunkRadiusUpdatedBody(negotiatedRadius)));
        session.sendPackets(List.of(radiusUpdated));
    }

    /**
     * Send the initial chunk burst proactively right after StartGame + registries.
     * Modern Bedrock clients (1.21+) no longer wait to send RequestChunkRadius — they
     * expect terrain to be on the wire as soon as they finish ingesting the registries.
     */
    private void sendInitialChunkBurst() {
        int negotiatedRadius = SERVER_MAX_CHUNK_RADIUS;

        // 1) ChunkRadiusUpdated — let the client know the cap up-front.
        RawCapturePacket radiusUpdated = new RawCapturePacket(ID_CHUNK_RADIUS_UPDATED);
        radiusUpdated.decode(Unpooled.wrappedBuffer(ChunkSender.chunkRadiusUpdatedBody(negotiatedRadius)));
        session.sendPackets(List.of(radiusUpdated));

        // 2) NetworkChunkPublisherUpdate — center + radius in blocks.
        int publisherRadiusBlocks = INITIAL_CHUNK_RADIUS * 16;
        RawCapturePacket publisher = new RawCapturePacket(ID_NETWORK_CHUNK_PUBLISHER_UPDATE);
        publisher.decode(Unpooled.wrappedBuffer(ChunkSender.networkChunkPublisherUpdateBody(
            SPAWN_BLOCK_X, SPAWN_BLOCK_Y, SPAWN_BLOCK_Z, publisherRadiusBlocks)));
        session.sendPackets(List.of(publisher));

        // 3) LevelChunk burst around (0,0).
        sendChunkBurst();

        // 4) PlayerList Add — client needs to know about itself before rendering.
        long uid = RUNTIME_ID_SEQ.get(); // last assigned runtime id = this player's
        byte[] playerListBody = PlayerListBuilder.buildAdd(
            session.playerUuid(), uid, session.displayName(),
            session.xuid() != null ? session.xuid() : "");
        RawCapturePacket playerList = new RawCapturePacket(0x3f); // IDPlayerList
        playerList.decode(Unpooled.wrappedBuffer(playerListBody));
        session.sendPackets(List.of(playerList));

        // 5) PlayStatus(PLAYER_SPAWN) — terrain + player list shipped, client may render.
        PlayStatusPacket spawn = new PlayStatusPacket();
        spawn.setStatus(PlayStatusPacket.PLAYER_SPAWN);
        session.sendPackets(List.of(spawn));
    }

    /**
     * Encode and ship one {@code LevelChunk} packet per chunk in an
     * {@code (2*INITIAL_CHUNK_RADIUS + 1)} square around (0,0). If the world
     * doesn't have a chunk at a given coord (loadChunk produced no data), we
     * synthesize an all-air chunk with no backing world so the client still
     * gets terrain bytes for that slot.
     *
     * <p>Each chunk is sent as its own batch — easier to reason about than
     * coalescing 25 chunks into a single batch, and the codec will still
     * compress each batch.
     */
    private void sendChunkBurst() {
        if (world == null) {
            log.warn("no world wired into LoginFlowHandler — skipping chunk burst");
            return;
        }
        int dim = world.dim();
        int radius = INITIAL_CHUNK_RADIUS;
        List<Packet> burst = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                // Force a synthetic stone-floor chunk for MVP — modern clients
                // disconnect when handed an all-air chunk during initial spawn,
                // and the LevelDB world is empty for a freshly created server.
                Chunk chunk = airChunk(cx, cz, dim);
                byte[] body = ChunkSender.levelChunkBody(chunk, dim);
                RawCapturePacket pkt = new RawCapturePacket(PacketIds.LEVEL_CHUNK);
                pkt.decode(Unpooled.wrappedBuffer(body));
                burst.add(pkt);
            }
        }
        log.info("sending {} LevelChunk packets around spawn (radius={})",
            burst.size(), radius);
        session.sendPackets(burst);
    }

    /**
     * Build a worldless all-air {@link Chunk} for {@code (cx, cz)} — used when
     * the world has no entry at that coord and we still need to emit a
     * LevelChunk so the client doesn't see void.
     */
    /**
     * Synthesize a minimal "ground floor" chunk used when the LevelDB world has
     * no entry at the requested coord. Sub-chunk subY=0 (world Y 0..15) is
     * filled with stone so the client has actual terrain to render — modern
     * Bedrock clients tend to disconnect when handed an all-air chunk during
     * the initial spawn burst.
     */
    private static Chunk airChunk(int cx, int cz, int dim) {
        SubChunk[] subs = new SubChunk[Chunk.SUBCHUNK_COUNT];
        for (int i = 0; i < subs.length; i++) {
            int subY = Chunk.MIN_SUB_Y + i;
            if (subY == 0) {
                subs[i] = SubChunk.filledWith(subY,
                    net.butterfly.api.world.BlockState.of("minecraft:stone"));
            } else {
                subs[i] = SubChunk.empty(subY);
            }
        }
        return new Chunk(/* world = */ null, cx, cz, dim, subs);
    }

    // ---- State 6: AWAIT_PLAYER_INIT ----

    /**
     * The client confirms it has consumed the chunk burst and finished its
     * loading screen by sending {@code SetLocalPlayerAsInitialised} (id 0x71).
     * The body is a single varuint runtime entity id; we don't need it — the
     * arrival alone is the signal to flip to {@link ClientSession.LoginState#ACTIVE}.
     */
    private void handleAwaitPlayerInit(Packet packet) {
        if (packet.packetId() != ID_SET_LOCAL_PLAYER_AS_INITIALISED) {
            log.debug("waiting for SetLocalPlayerAsInitialised; got 0x{}",
                Integer.toHexString(packet.packetId()));
            return;
        }
        log.info("player ready: {}", session.displayName());
        session.setState(ACTIVE);
    }

    // ---- State 7: ACTIVE ----

    /**
     * Optional chat-routing hook installed by {@link ButterflyServer} so the login flow
     * can hand off inbound {@code Text} (0x09) packets without depending on the core
     * server class directly. The hook receives the player's display name and the
     * inbound chat message; the listener fires {@code PlayerChatEvent} and broadcasts
     * to other online players if the event isn't cancelled.
     */
    private java.util.function.BiConsumer<String, String> onChat = (n, m) -> {};

    /** Install the chat-routing hook. Pass {@code null} to clear. */
    public void setOnChat(java.util.function.BiConsumer<String, String> handler) {
        this.onChat = handler != null ? handler : (n, m) -> {};
    }

    /**
     * Hook invoked for every inbound {@code PlayerAuthInput} (0x90) the active session
     * sees. Receives the spawned {@link PlayerImpl} (or {@code null} if spawn hasn't
     * fired yet) plus the leading position/rotation floats. The default no-op drops
     * the update; {@link ButterflyServer} installs a real handler that mirrors the
     * values into the player's volatile state.
     */
    @FunctionalInterface
    public interface AuthInputHandler {
        void handle(PlayerImpl player, float posX, float posY, float posZ, float yaw, float pitch);
    }

    /**
     * Hook invoked for every inbound {@code PlayerAction} (0x24) the active session
     * sees. Receives the spawned {@link PlayerImpl} (or {@code null} if spawn hasn't
     * fired yet) plus the action type and target block coordinates. The default
     * no-op drops the action; {@link ButterflyServer} installs a real handler that
     * fires {@code BlockBreakEvent} for action 13 and applies the world mutation.
     */
    @FunctionalInterface
    public interface BlockActionHandler {
        void handle(PlayerImpl player, int actionType, int x, int y, int z);
    }

    private AuthInputHandler onAuthInput = (p, x, y, z, yaw, pitch) -> {};
    private BlockActionHandler onBlockAction = (p, a, x, y, z) -> {};

    /**
     * Spawned player handle. Set by {@link ButterflyServer} once
     * {@link #setOnPlayerSpawn(Consumer)} has fired and a {@link PlayerImpl} has
     * been admitted into the online roster. Remains {@code null} for sessions
     * that never reach {@code ACTIVE} (kicked at PreLogin, duplicate name, etc).
     */
    private PlayerImpl spawnedPlayer;

    /** Install the {@code PlayerAuthInput} dispatch hook. Pass {@code null} to clear. */
    public void setOnAuthInput(AuthInputHandler handler) {
        this.onAuthInput = handler != null ? handler : (p, x, y, z, yaw, pitch) -> {};
    }

    /** Install the {@code PlayerAction} dispatch hook. Pass {@code null} to clear. */
    public void setOnBlockAction(BlockActionHandler handler) {
        this.onBlockAction = handler != null ? handler : (p, a, x, y, z) -> {};
    }

    /**
     * Bind the spawned {@link PlayerImpl} for this session. Invoked by the listener
     * after {@link #setOnPlayerSpawn(Consumer)} has fired and the player has been
     * admitted to the online roster. Pass {@code null} to clear when the session
     * is torn down.
     */
    public void setSpawnedPlayer(PlayerImpl player) {
        this.spawnedPlayer = player;
    }

    private void handleActive(Packet packet) {
        if (packet.packetId() == PacketIds.TEXT && packet instanceof TextPacket text) {
            // Re-broadcast chat from the client. We use the server-known displayName
            // (captured during login) rather than the packet's sourceName so a client
            // can't spoof other usernames.
            String message = text.message();
            if (message == null || message.isEmpty()) return;
            try {
                onChat.accept(session.displayName(), message);
            } catch (RuntimeException e) {
                log.warn("chat hook threw for {}: {}", session.displayName(), e.toString());
            }
            return;
        }
        if (packet.packetId() == PlayerAuthInputPacket.ID) {
            dispatchAuthInput(packet);
            return;
        }
        if (packet.packetId() == PlayerActionPacket.ID) {
            dispatchPlayerAction(packet);
            return;
        }
        // Plan B will route remaining active packets to the world / player layer. For
        // now we only log unknown packets so the rest of the test surface stays
        // predictable.
        log.debug("active packet id=0x{}", Integer.toHexString(packet.packetId()));
    }

    /**
     * Decode the leading 8 floats of a PlayerAuthInput (0x90) — pitch, yaw, posX/Y/Z,
     * moveX/Z, headYaw — and forward to the {@link AuthInputHandler}. The packet
     * arrives as either a {@link RawCapturePacket} (when registered as raw) or an
     * {@link UnknownPacket} (default fallback for ids the upstream codec doesn't
     * know — 0x90 falls into this bucket today). We extract the verbatim body and
     * route it through {@link PlayerAuthInputPacket} for the field-level decode so
     * the handler receives typed values.
     *
     * <p>If the body is too short (e.g. an unexpected mid-packet truncation) the
     * dispatch is dropped silently — desync is preferable to corrupting the player's
     * recorded position.
     */
    private void dispatchAuthInput(Packet packet) {
        ByteBuf body = bodyOf(packet);
        if (body == null) return;
        if (body.readableBytes() < PlayerAuthInputPacket.LEADING_FLOAT_COUNT * Float.BYTES) {
            return;
        }
        PlayerAuthInputPacket decoded = new PlayerAuthInputPacket();
        try {
            decoded.decode(body);
        } catch (RuntimeException e) {
            log.debug("PlayerAuthInput decode failed: {}", e.toString());
            return;
        } finally {
            ByteBuf tail = decoded.rawBody();
            if (tail != null && tail.refCnt() > 0) tail.release();
        }
        try {
            onAuthInput.handle(spawnedPlayer,
                decoded.posX(), decoded.posY(), decoded.posZ(),
                decoded.yaw(), decoded.pitch());
        } catch (RuntimeException e) {
            log.warn("onAuthInput hook threw for {}: {}", session.displayName(), e.toString());
        }
    }

    /**
     * Decode a PlayerAction (0x24) body and forward to the {@link BlockActionHandler}.
     * The packet arrives as a {@link RawCapturePacket} (when registered as raw) or
     * an {@link UnknownPacket} (default for ids absent from the upstream codec —
     * 0x24 falls into this bucket today). Decode failures are logged and dropped —
     * a malformed action shouldn't sever the session.
     */
    private void dispatchPlayerAction(Packet packet) {
        ByteBuf body = bodyOf(packet);
        if (body == null) return;
        PlayerActionPacket decoded = new PlayerActionPacket();
        try {
            decoded.decode(body);
        } catch (RuntimeException e) {
            log.debug("PlayerAction decode failed: {}", e.toString());
            return;
        }
        try {
            onBlockAction.handle(spawnedPlayer,
                decoded.actionType(),
                decoded.blockX(), decoded.blockY(), decoded.blockZ());
        } catch (RuntimeException e) {
            log.warn("onBlockAction hook threw for {}: {}", session.displayName(), e.toString());
        }
    }

    /**
     * Extract the verbatim body bytes from a captured packet. Returns a duplicate
     * (independent reader index) so the caller can decode without affecting other
     * subscribers, or {@code null} if the packet type doesn't expose a raw body.
     */
    private static ByteBuf bodyOf(Packet packet) {
        if (packet instanceof RawCapturePacket raw) return raw.rawBody().duplicate();
        if (packet instanceof UnknownPacket unk) return unk.body().duplicate();
        return null;
    }
}
