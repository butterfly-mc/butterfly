package net.butterfly.core.world;

import io.netty.buffer.ByteBuf;
import net.butterfly.nbt.NbtList;
import net.butterfly.nbt.NbtMap;
import net.butterfly.nbt.NbtType;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writer counterpart to {@link net.butterfly.nbt.NbtLeReader}: fixed-size little-endian NBT
 * with uint16-LE string lengths and fixed-width LE int/long.
 *
 * <p>Used by {@link ChunkCodec} to encode block-palette entries inside a sub-chunk on disk.
 * Kept package-private until a broader use-case justifies promotion to the proto-nbt module.
 */
final class NbtLeWriter {

    private final ByteBuf buf;

    NbtLeWriter(ByteBuf buf) {
        this.buf = buf;
    }

    void writeCompound(NbtMap map) {
        buf.writeByte(NbtType.COMPOUND.id());
        writeString(""); // root name
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
