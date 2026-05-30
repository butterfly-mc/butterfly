package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerActionPacketTest {

    @Test
    void packetIdIsZeroX24() {
        assertEquals(0x24, new PlayerActionPacket().packetId());
    }

    @Test
    void roundTripPreservesAllFields() {
        PlayerActionPacket src = new PlayerActionPacket();
        src.setEntityRuntimeId(7L);
        src.setActionType(PlayerActionPacket.ACTION_CREATIVE_PLAYER_DESTROY_BLOCK);
        src.setBlockX(5);
        src.setBlockY(64);
        src.setBlockZ(-3);
        src.setResultX(5);
        src.setResultY(65);
        src.setResultZ(-3);
        src.setBlockFace(1);

        ByteBuf wire = Unpooled.buffer();
        try {
            src.encode(wire);

            PlayerActionPacket decoded = new PlayerActionPacket();
            decoded.decode(wire);

            assertEquals(7L, decoded.entityRuntimeId());
            assertEquals(13, decoded.actionType());
            assertEquals(5, decoded.blockX());
            assertEquals(64, decoded.blockY());
            assertEquals(-3, decoded.blockZ());
            assertEquals(5, decoded.resultX());
            assertEquals(65, decoded.resultY());
            assertEquals(-3, decoded.resultZ());
            assertEquals(1, decoded.blockFace());
            assertEquals(0, wire.readableBytes(), "decoder consumed entire body");
        } finally {
            wire.release();
        }
    }

    @Test
    void roundTripWithLargeRuntimeIdAndNegativeFace() {
        // entityRuntimeId is varlong (unsigned). actionType / coords / blockFace are zigzag-varints.
        PlayerActionPacket src = new PlayerActionPacket();
        src.setEntityRuntimeId(1L << 40);
        src.setActionType(0);
        src.setBlockX(-100_000);
        src.setBlockY(0);
        src.setBlockZ(100_000);
        src.setResultX(-100_001);
        src.setResultY(255);
        src.setResultZ(100_001);
        src.setBlockFace(-1);

        ByteBuf wire = Unpooled.buffer();
        try {
            src.encode(wire);

            PlayerActionPacket decoded = new PlayerActionPacket();
            decoded.decode(wire);

            assertEquals(1L << 40, decoded.entityRuntimeId());
            assertEquals(0, decoded.actionType());
            assertEquals(-100_000, decoded.blockX());
            assertEquals(0, decoded.blockY());
            assertEquals(100_000, decoded.blockZ());
            assertEquals(-100_001, decoded.resultX());
            assertEquals(255, decoded.resultY());
            assertEquals(100_001, decoded.resultZ());
            assertEquals(-1, decoded.blockFace());
            assertEquals(0, wire.readableBytes());
        } finally {
            wire.release();
        }
    }
}
