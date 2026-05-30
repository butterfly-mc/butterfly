package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import net.butterfly.nbt.NbtList;
import net.butterfly.nbt.NbtMap;
import net.butterfly.nbt.NbtType;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Public fixed-size little-endian NBT writer for use by hand-rolled packet
 * codecs. Mirrors {@link net.butterfly.nbt.NbtLeReader} for the encode side:
 * <ul>
 *   <li>String length is a fixed <b>uint16 LE</b> (not a varint).</li>
 *   <li>{@code int} / {@code long} are fixed-width LE (not zigzag varints).</li>
 *   <li>{@code short} / {@code float} / {@code double} are LE, same as the
 *       NetworkLittleEndian variant.</li>
 * </ul>
 *
 * <p>Used by StartGame's per-block-entry {@code Properties} compound and by the
 * core ItemStack encoder. Distinct from {@link net.butterfly.nbt.NbtWriter}
 * (NetworkLittleEndian) which is used for things like StartGame's {@code propertyData}.
 *
 * <p>The companion {@code NbtLeWriter} in {@code net.butterfly.core.world} is
 * package-private and reserved for the chunk codec path; this class is the
 * publicly-visible peer for use across packages.
 */
public final class NbtLeWriter {
    private final ByteBuf buf;

    public NbtLeWriter(ByteBuf buf) { this.buf = buf; }

    public void writeRootCompound(NbtMap map) {
        buf.writeByte(NbtType.COMPOUND.id());
        writeString("");
        writePayload(map);
    }

    private void writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xffff) {
            throw new IllegalArgumentException("NBT string too long: " + bytes.length);
        }
        buf.writeShortLE(bytes.length);
        buf.writeBytes(bytes);
    }

    private void writePayload(NbtMap map) {
        for (Map.Entry<String, Object> e : map.asMap().entrySet()) {
            Object v = e.getValue();
            NbtType type = typeOf(v);
            buf.writeByte(type.id());
            writeString(e.getKey());
            writeValue(type, v);
        }
        buf.writeByte(NbtType.END.id());
    }

    @SuppressWarnings("unchecked")
    private void writeValue(NbtType type, Object v) {
        switch (type) {
            case BYTE -> buf.writeByte((Byte) v);
            case SHORT -> buf.writeShortLE((Short) v);
            case INT -> buf.writeIntLE((Integer) v);
            case LONG -> buf.writeLongLE((Long) v);
            case FLOAT -> buf.writeFloatLE((Float) v);
            case DOUBLE -> buf.writeDoubleLE((Double) v);
            case STRING -> writeString((String) v);
            case COMPOUND -> writePayload((NbtMap) v);
            case LIST -> writeList((NbtList<Object>) v);
            default -> throw new IllegalArgumentException("Unsupported tag " + type);
        }
    }

    private void writeList(NbtList<Object> list) {
        buf.writeByte(list.elementType().id());
        buf.writeIntLE(list.size());
        for (Object el : list) writeValue(list.elementType(), el);
    }

    private static NbtType typeOf(Object v) {
        if (v instanceof Byte) return NbtType.BYTE;
        if (v instanceof Short) return NbtType.SHORT;
        if (v instanceof Integer) return NbtType.INT;
        if (v instanceof Long) return NbtType.LONG;
        if (v instanceof Float) return NbtType.FLOAT;
        if (v instanceof Double) return NbtType.DOUBLE;
        if (v instanceof String) return NbtType.STRING;
        if (v instanceof NbtMap) return NbtType.COMPOUND;
        if (v instanceof NbtList) return NbtType.LIST;
        throw new IllegalArgumentException("Cannot map " + v.getClass());
    }
}
