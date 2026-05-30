package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.nbt.VarInts;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds a minimal PlayerList Add packet body (id 0x3f) for a single player.
 * The client needs this before it will render the player entity.
 */
public final class PlayerListBuilder {
    private PlayerListBuilder() {}

    private static final byte[] SKIN_RESOURCE_PATCH =
        "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_SKIN_DATA = new byte[64 * 64 * 4]; // 64x64 RGBA transparent

    /**
     * Build a PlayerList Add body for one player.
     */
    public static byte[] buildAdd(UUID uuid, long entityUniqueId, String username, String xuid) {
        ByteBuf buf = Unpooled.buffer(512);
        // ActionType = 0 (Add)
        buf.writeByte(0);
        // Entry count (varuint)
        VarInts.writeUnsignedInt(buf, 1);

        // --- PlayerListEntry ---
        // UUID (16 bytes: low LE then high LE)
        buf.writeLongLE(uuid.getLeastSignificantBits());
        buf.writeLongLE(uuid.getMostSignificantBits());
        // EntityUniqueID (varint64 zigzag)
        VarInts.writeLong(buf, entityUniqueId);
        // Username
        writeString(buf, username);
        // XUID
        writeString(buf, xuid != null ? xuid : "");
        // PlatformChatID
        writeString(buf, "");
        // BuildPlatform (int32 LE) — 0 = unknown
        buf.writeIntLE(0);

        // --- Skin (minimal) ---
        // SkinID
        writeString(buf, "Standard_Custom_" + uuid);
        // PlayFabID
        writeString(buf, "");
        // SkinResourcePatch (ByteSlice = varuint len + bytes)
        VarInts.writeUnsignedInt(buf, SKIN_RESOURCE_PATCH.length);
        buf.writeBytes(SKIN_RESOURCE_PATCH);
        // SkinImageWidth, SkinImageHeight (uint32 LE)
        buf.writeIntLE(64);
        buf.writeIntLE(64);
        // SkinData (ByteSlice)
        VarInts.writeUnsignedInt(buf, EMPTY_SKIN_DATA.length);
        buf.writeBytes(EMPTY_SKIN_DATA);
        // Animations (SliceUint32Length) — count=0
        buf.writeIntLE(0);
        // CapeImageWidth, CapeImageHeight
        buf.writeIntLE(0);
        buf.writeIntLE(0);
        // CapeData (ByteSlice) — empty
        VarInts.writeUnsignedInt(buf, 0);
        // SkinGeometry (ByteSlice) — empty
        VarInts.writeUnsignedInt(buf, 0);
        // GeometryDataEngineVersion (ByteSlice) — empty
        VarInts.writeUnsignedInt(buf, 0);
        // AnimationData (ByteSlice) — empty
        VarInts.writeUnsignedInt(buf, 0);
        // CapeID
        writeString(buf, "");
        // FullID
        writeString(buf, uuid.toString());
        // ArmSize
        writeString(buf, "wide");
        // SkinColour
        writeString(buf, "#0");
        // PersonaPieces (SliceUint32Length) — count=0
        buf.writeIntLE(0);
        // PieceTintColours (SliceUint32Length) — count=0
        buf.writeIntLE(0);
        // PremiumSkin, PersonaSkin, PersonaCapeOnClassicSkin, PrimaryUser, OverrideAppearance
        buf.writeBoolean(false);
        buf.writeBoolean(false);
        buf.writeBoolean(false);
        buf.writeBoolean(true);  // PrimaryUser = true for the local player
        buf.writeBoolean(false);

        // --- End of entry ---
        // Teacher, Host, SubClient
        buf.writeBoolean(false);
        buf.writeBoolean(false);
        buf.writeBoolean(false);
        // PlayerColour (ARGB = 4 bytes)
        buf.writeIntLE(0xFFFFFFFF);

        // --- Trailing Trusted bool per entry (after all entries) ---
        buf.writeBoolean(true);

        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        VarInts.writeUnsignedInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }
}
