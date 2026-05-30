package net.butterfly.core.network.packets.start_game;

/** Three-component float vector, LE-encoded as 3 × float32 on the wire. */
public record Vec3(float x, float y, float z) {
    public static final Vec3 ZERO = new Vec3(0f, 0f, 0f);
}
