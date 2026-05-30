package net.butterfly.core.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.api.entity.EntityType;
import net.butterfly.api.entity.Player;
import net.butterfly.api.world.World;
import net.butterfly.codec.Strings;
import net.butterfly.codec.packets.RawCapturePacket;
import net.butterfly.codec.packets.TextPacket;
import net.butterfly.core.ButterflyServer;
import net.butterfly.core.network.ClientSession;
import net.butterfly.core.world.WorldImpl;
import net.butterfly.nbt.VarInts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default {@link Player} implementation backed by a {@link ClientSession}.
 *
 * <p>Identity ({@link #name()}, {@link #xuid()}, {@link #uuid()}) is captured at
 * construction and immutable. Position/rotation fields are {@code volatile} so
 * the simulate thread can mutate them while readers (network senders, plugin
 * code) observe consistent values.
 *
 * <p>Network operations ({@link #sendMessage(String)}, {@link #sendChat(String, String)},
 * {@link #kick(String)}) push packets through the session via
 * {@link ClientSession#sendPackets(List)} — which handles compression and
 * encryption based on the session's current cipher state.
 *
 * <p>For the MVP, {@link #teleport(World, double, double, double)} only updates
 * local state and does NOT send a {@code MovePlayer} packet. Wiring teleport-with-
 * network is deferred to milestone B2.
 */
public final class PlayerImpl implements Player {

    private static final Logger log = LoggerFactory.getLogger(PlayerImpl.class);

    /** Bedrock packet id for Disconnect (not currently registered in BedrockCodecs). */
    private static final int DISCONNECT_PACKET_ID = 0x05;

    private final String name;
    private final String xuid;
    private final UUID uuid;
    private final ClientSession session;
    private final ButterflyServer server;

    private volatile WorldImpl world;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;

    /**
     * @param name     display name (never {@code null})
     * @param xuid     XUID string (never {@code null}; may be empty)
     * @param uuid     stable identity UUID
     * @param world    spawn world (may be {@code null} in tests; production callers must
     *                 pass a real world)
     * @param session  network session for outbound packets
     * @param server   parent server for chat / quit broadcasts (may be {@code null} in tests)
     * @param spawnX   initial X
     * @param spawnY   initial Y (feet)
     * @param spawnZ   initial Z
     */
    public PlayerImpl(String name,
                      String xuid,
                      UUID uuid,
                      WorldImpl world,
                      ClientSession session,
                      ButterflyServer server,
                      double spawnX,
                      double spawnY,
                      double spawnZ) {
        this.name = Objects.requireNonNull(name, "name");
        this.xuid = Objects.requireNonNull(xuid, "xuid");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.world = world;
        this.session = Objects.requireNonNull(session, "session");
        this.server = server;
        this.x = spawnX;
        this.y = spawnY;
        this.z = spawnZ;
        this.yaw = 0f;
        this.pitch = 0f;
    }

    // ---- Player ----

    @Override public String name() { return name; }
    @Override public String xuid() { return xuid; }

    @Override
    public void sendMessage(String message) {
        Objects.requireNonNull(message, "message");
        TextPacket pkt = new TextPacket();
        pkt.setType(TextPacket.TYPE_RAW);
        pkt.setNeedsTranslation(false);
        pkt.setMessage(message);
        // sourceName / xuid / platformChatId / filteredMessage default to "" in TextPacket.
        try {
            session.sendPackets(List.of(pkt));
        } catch (RuntimeException e) {
            log.debug("sendMessage to {} failed (session may be closed): {}", name, e.toString());
        }
    }

    @Override
    public void sendChat(String from, String message) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(message, "message");
        TextPacket pkt = new TextPacket();
        pkt.setType(TextPacket.TYPE_CHAT);
        pkt.setNeedsTranslation(false);
        pkt.setSourceName(from);
        pkt.setMessage(message);
        pkt.setXuid(this.xuid);
        try {
            session.sendPackets(List.of(pkt));
        } catch (RuntimeException e) {
            log.debug("sendChat to {} failed (session may be closed): {}", name, e.toString());
        }
    }

    @Override
    public void kick(String reason) {
        Objects.requireNonNull(reason, "reason");
        // Disconnect (0x05) body for protocol 975 — minimal layout per the MVP spec:
        //   varint reason = 0  (generic disconnect)
        //   bool hideDisconnectScreen = false
        //   string message  (varuint length + UTF-8 bytes)
        // The codec doesn't have a registered DisconnectPacket type yet, so we ship
        // it via a RawCapturePacket whose body is the encoded payload.
        ByteBuf body = Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(body, 0);   // reason = 0
            body.writeBoolean(false);            // hideDisconnectScreen
            Strings.write(body, reason);         // varint length + UTF-8 message bytes
            byte[] bodyBytes = new byte[body.readableBytes()];
            body.readBytes(bodyBytes);

            RawCapturePacket disconnect = new RawCapturePacket(DISCONNECT_PACKET_ID);
            disconnect.decode(Unpooled.wrappedBuffer(bodyBytes));
            session.sendPackets(List.of(disconnect));
        } catch (RuntimeException e) {
            log.warn("kick({}) for {} failed: {}", reason, name, e.toString());
        } finally {
            body.release();
        }
        // MVP: RakSession has no close() method — natural RakNet timeout will tear down
        // the connection a few seconds after the client receives the Disconnect packet.
        // Tracked for B2 (proper session lifecycle).
    }

    // ---- Entity ----

    @Override public UUID uuid() { return uuid; }
    @Override public EntityType type() { return EntityType.PLAYER; }
    @Override public World world() { return world; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }
    @Override public float yaw() { return yaw; }
    @Override public float pitch() { return pitch; }

    @Override
    public void teleport(World destination, double tx, double ty, double tz) {
        Objects.requireNonNull(destination, "destination");
        // MVP: update local state only. Sending MovePlayer to the client is part of B2.
        if (destination instanceof WorldImpl impl) {
            this.world = impl;
        }
        this.x = tx;
        this.y = ty;
        this.z = tz;
    }

    /** Internal: update yaw/pitch (for future PlayerAuthInput integration). */
    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Internal: update position only (for future PlayerAuthInput integration). */
    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Internal: parent server reference (may be {@code null} in tests). */
    public ButterflyServer server() { return server; }

    /** Internal: backing session (used by listener/cleanup paths). */
    public ClientSession session() { return session; }
}
