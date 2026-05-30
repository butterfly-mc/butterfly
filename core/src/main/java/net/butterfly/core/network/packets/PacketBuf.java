package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import net.butterfly.nbt.VarInts;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Small set of Bedrock wire helpers shared by hand-rolled packet codecs in this
 * package — keeps each {@code Packet.encode}/{@code decode} method focused on
 * field order and type rather than re-implementing string/uuid/blockpos
 * boilerplate.
 *
 * <p>Conventions match gophertunnel's {@code minecraft/protocol} I/O:
 * <ul>
 *   <li>Strings are varuint length-prefixed, UTF-8 bytes.</li>
 *   <li>BlockPos is {@code varint x, varuint y, varint z}.</li>
 *   <li>UUID is two int64 LE values written as {@code low} then {@code high}
 *       (this matches gophertunnel's {@code Reader.UUID}, which stores the
 *       low-order bits first).</li>
 * </ul>
 */
public final class PacketBuf {
    private PacketBuf() {}

    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        VarInts.writeUnsignedInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(ByteBuf buf) {
        int len = (int) VarInts.readUnsignedInt(buf);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeBlockPos(ByteBuf buf, int x, int y, int z) {
        VarInts.writeInt(buf, x);
        VarInts.writeUnsignedInt(buf, y);
        VarInts.writeInt(buf, z);
    }

    /**
     * UUID is encoded as two int64 LE values: low 64 bits first, then high.
     * Mirrors gophertunnel {@code Reader.UUID}/{@code Writer.UUID}.
     */
    public static void writeUuid(ByteBuf buf, UUID uuid) {
        if (uuid == null) {
            buf.writeLongLE(0L);
            buf.writeLongLE(0L);
            return;
        }
        buf.writeLongLE(uuid.getLeastSignificantBits());
        buf.writeLongLE(uuid.getMostSignificantBits());
    }

    public static UUID readUuid(ByteBuf buf) {
        long low = buf.readLongLE();
        long high = buf.readLongLE();
        return new UUID(high, low);
    }
}
