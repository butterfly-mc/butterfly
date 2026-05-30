package net.butterfly.core.network.packets.start_game;

/**
 * Three-component integer block position. Wire layout per gophertunnel
 * {@code BlockPos}: {@code varint x, varuint y, varint z}. Encoded by
 * {@link net.butterfly.core.network.packets.PacketBuf#writeBlockPos}.
 */
public record BlockPos(int x, int y, int z) {
    public static final BlockPos ZERO = new BlockPos(0, 0, 0);
}
