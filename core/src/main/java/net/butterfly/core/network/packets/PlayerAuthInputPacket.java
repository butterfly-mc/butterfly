package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.codec.Packet;

/**
 * PlayerAuthInput (0x90) — sent by the client every tick (20x/s) when server-authoritative
 * movement is enabled. The full body has ~80 fields with conditional sub-blocks; this minimal
 * decoder only parses the always-present leading rotation/position/movement floats and stores
 * the rest of the body verbatim for relay or follow-up parsing.
 *
 * <p>Wire order of the leading floats (per gophertunnel {@code PlayerAuthInput.Marshal}):
 * {@code Pitch, Yaw, Position(x,y,z), MoveVector(x,z), HeadYaw} — 8 little-endian float32 values.
 *
 * <p>TODO: register {@code 0x90 -> PlayerAuthInputPacket::new} on the protocol-975
 * {@code PacketRegistry} (currently constructed in butterfly-protocol's {@code BedrockCodecs}).
 */
public final class PlayerAuthInputPacket implements Packet {

    /** Bedrock protocol packet id. */
    public static final int ID = 0x90;

    /** Number of leading float32 LE values parsed before the raw tail. */
    public static final int LEADING_FLOAT_COUNT = 8;

    private float pitch;
    private float yaw;
    private float posX;
    private float posY;
    private float posZ;
    private float moveX;
    private float moveZ;
    private float headYaw;

    /** Remainder of the packet body after the 8 leading floats. */
    private ByteBuf rawBody = Unpooled.EMPTY_BUFFER;

    public float pitch() { return pitch; }
    public void setPitch(float v) { this.pitch = v; }

    public float yaw() { return yaw; }
    public void setYaw(float v) { this.yaw = v; }

    public float posX() { return posX; }
    public void setPosX(float v) { this.posX = v; }

    public float posY() { return posY; }
    public void setPosY(float v) { this.posY = v; }

    public float posZ() { return posZ; }
    public void setPosZ(float v) { this.posZ = v; }

    public float moveX() { return moveX; }
    public void setMoveX(float v) { this.moveX = v; }

    public float moveZ() { return moveZ; }
    public void setMoveZ(float v) { this.moveZ = v; }

    public float headYaw() { return headYaw; }
    public void setHeadYaw(float v) { this.headYaw = v; }

    /** Raw, un-parsed remainder of the body (everything after the leading floats). */
    public ByteBuf rawBody() { return rawBody; }
    public void setRawBody(ByteBuf body) { this.rawBody = body; }

    @Override public int packetId() { return ID; }

    @Override public void decode(ByteBuf buf) {
        this.pitch   = buf.readFloatLE();
        this.yaw     = buf.readFloatLE();
        this.posX    = buf.readFloatLE();
        this.posY    = buf.readFloatLE();
        this.posZ    = buf.readFloatLE();
        this.moveX   = buf.readFloatLE();
        this.moveZ   = buf.readFloatLE();
        this.headYaw = buf.readFloatLE();
        this.rawBody = buf.readRetainedSlice(buf.readableBytes());
    }

    @Override public void encode(ByteBuf buf) {
        buf.writeFloatLE(pitch);
        buf.writeFloatLE(yaw);
        buf.writeFloatLE(posX);
        buf.writeFloatLE(posY);
        buf.writeFloatLE(posZ);
        buf.writeFloatLE(moveX);
        buf.writeFloatLE(moveZ);
        buf.writeFloatLE(headYaw);
        buf.writeBytes(rawBody, rawBody.readerIndex(), rawBody.readableBytes());
    }
}
