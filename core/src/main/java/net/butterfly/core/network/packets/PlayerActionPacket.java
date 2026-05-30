package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import net.butterfly.codec.Packet;
import net.butterfly.nbt.VarInts;

/**
 * PlayerAction (0x24) — sent by the client to signal entity actions tied to a block
 * position: start/stop/abort break, dimension change ack, jump, sneak/sprint flips,
 * respawn, etc. Field layout per gophertunnel {@code protocol/packet/player_action.go}:
 *
 * <pre>
 *   varlong entityRuntimeId
 *   varint  actionType
 *   BlockPos blockPos      (varint x, varuint y, varint z)
 *   BlockPos resultPos     (varint x, varuint y, varint z)
 *   varint  blockFace
 * </pre>
 *
 * <p>{@code actionType} enum (subset relevant to block interaction):
 * <ul>
 *   <li>0  = START_BREAK</li>
 *   <li>1  = ABORT_BREAK</li>
 *   <li>2  = STOP_BREAK</li>
 *   <li>3  = GET_UPDATED_BLOCK</li>
 *   <li>4  = DROP_ITEM</li>
 *   <li>5  = START_SLEEPING</li>
 *   <li>6  = STOP_SLEEPING</li>
 *   <li>7  = RESPAWN</li>
 *   <li>8  = JUMP</li>
 *   <li>9  = START_SPRINT</li>
 *   <li>10 = STOP_SPRINT</li>
 *   <li>11 = START_SNEAK</li>
 *   <li>12 = STOP_SNEAK</li>
 *   <li>13 = CREATIVE_PLAYER_DESTROY_BLOCK — fired in creative mode the moment the
 *           client decides to break a block. The MVP server reacts to this only.</li>
 *   <li>14 = DIMENSION_CHANGE_DONE</li>
 * </ul>
 *
 * <p>The packet is fully covered by the listed fields (no raw tail).
 *
 * <p>This type lives in {@code core} (not {@code butterfly-protocol}) because the
 * codec registry is owned by the upstream protocol module; inbound packets at
 * id 0x24 still arrive as {@code RawCapturePacket} until that registration lands
 * — {@link LoginFlowHandler} handles 0x24 by feeding the captured body through
 * {@link #decode(ByteBuf)} on a fresh instance.
 */
public final class PlayerActionPacket implements Packet {

    /** Bedrock protocol packet id. */
    public static final int ID = 0x24;

    /** {@code actionType} value the MVP server reacts to: creative-mode block break. */
    public static final int ACTION_CREATIVE_PLAYER_DESTROY_BLOCK = 13;

    private long entityRuntimeId;
    private int actionType;
    private int blockX;
    private int blockY;
    private int blockZ;
    private int resultX;
    private int resultY;
    private int resultZ;
    private int blockFace;

    public long entityRuntimeId() { return entityRuntimeId; }
    public void setEntityRuntimeId(long v) { this.entityRuntimeId = v; }

    public int actionType() { return actionType; }
    public void setActionType(int v) { this.actionType = v; }

    public int blockX() { return blockX; }
    public void setBlockX(int v) { this.blockX = v; }

    public int blockY() { return blockY; }
    public void setBlockY(int v) { this.blockY = v; }

    public int blockZ() { return blockZ; }
    public void setBlockZ(int v) { this.blockZ = v; }

    public int resultX() { return resultX; }
    public void setResultX(int v) { this.resultX = v; }

    public int resultY() { return resultY; }
    public void setResultY(int v) { this.resultY = v; }

    public int resultZ() { return resultZ; }
    public void setResultZ(int v) { this.resultZ = v; }

    public int blockFace() { return blockFace; }
    public void setBlockFace(int v) { this.blockFace = v; }

    @Override public int packetId() { return ID; }

    @Override public void decode(ByteBuf buf) {
        this.entityRuntimeId = VarInts.readUnsignedLong(buf);
        this.actionType = VarInts.readInt(buf);
        this.blockX = VarInts.readInt(buf);
        this.blockY = (int) VarInts.readUnsignedInt(buf);
        this.blockZ = VarInts.readInt(buf);
        this.resultX = VarInts.readInt(buf);
        this.resultY = (int) VarInts.readUnsignedInt(buf);
        this.resultZ = VarInts.readInt(buf);
        this.blockFace = VarInts.readInt(buf);
    }

    @Override public void encode(ByteBuf buf) {
        VarInts.writeUnsignedLong(buf, entityRuntimeId);
        VarInts.writeInt(buf, actionType);
        VarInts.writeInt(buf, blockX);
        VarInts.writeUnsignedInt(buf, blockY);
        VarInts.writeInt(buf, blockZ);
        VarInts.writeInt(buf, resultX);
        VarInts.writeUnsignedInt(buf, resultY);
        VarInts.writeInt(buf, resultZ);
        VarInts.writeInt(buf, blockFace);
    }
}
