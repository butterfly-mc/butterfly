package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAuthInputPacketTest {

    @Test
    void packetIdIsZeroX90() {
        assertEquals(0x90, new PlayerAuthInputPacket().packetId());
    }

    @Test
    void roundTripPreservesAllFieldsAndRawTail() {
        PlayerAuthInputPacket src = new PlayerAuthInputPacket();
        src.setPitch(12.5f);
        src.setYaw(-37.25f);
        src.setPosX(100.5f);
        src.setPosY(64.0f);
        src.setPosZ(-200.75f);
        src.setMoveX(0.6f);
        src.setMoveZ(-0.4f);
        src.setHeadYaw(45.125f);

        // Simulated tail of the body (e.g. inputData bitset + varints + ...)
        byte[] tailBytes = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, (byte) 0xff, 0x7f };
        src.setRawBody(Unpooled.wrappedBuffer(tailBytes));

        ByteBuf wire = Unpooled.buffer();
        try {
            src.encode(wire);

            int expectedLen = PlayerAuthInputPacket.LEADING_FLOAT_COUNT * Float.BYTES + tailBytes.length;
            assertEquals(expectedLen, wire.readableBytes(), "encoded body length");

            PlayerAuthInputPacket decoded = new PlayerAuthInputPacket();
            decoded.decode(wire);

            assertEquals(src.pitch(),   decoded.pitch(),   0f);
            assertEquals(src.yaw(),     decoded.yaw(),     0f);
            assertEquals(src.posX(),    decoded.posX(),    0f);
            assertEquals(src.posY(),    decoded.posY(),    0f);
            assertEquals(src.posZ(),    decoded.posZ(),    0f);
            assertEquals(src.moveX(),   decoded.moveX(),   0f);
            assertEquals(src.moveZ(),   decoded.moveZ(),   0f);
            assertEquals(src.headYaw(), decoded.headYaw(), 0f);

            ByteBuf tail = decoded.rawBody();
            try {
                assertEquals(tailBytes.length, tail.readableBytes(), "raw tail length");
                byte[] tailReadback = new byte[tail.readableBytes()];
                tail.getBytes(tail.readerIndex(), tailReadback);
                assertArrayEquals(tailBytes, tailReadback, "raw tail bytes");
            } finally {
                tail.release();
            }

            assertEquals(0, wire.readableBytes(), "decoder consumed entire body");
        } finally {
            wire.release();
        }
    }

    @Test
    void roundTripWithEmptyTailProducesEmptyRawBody() {
        PlayerAuthInputPacket src = new PlayerAuthInputPacket();
        src.setPitch(1f);
        src.setYaw(2f);
        src.setPosX(3f);
        src.setPosY(4f);
        src.setPosZ(5f);
        src.setMoveX(6f);
        src.setMoveZ(7f);
        src.setHeadYaw(8f);

        ByteBuf wire = Unpooled.buffer();
        try {
            src.encode(wire);
            assertEquals(PlayerAuthInputPacket.LEADING_FLOAT_COUNT * Float.BYTES,
                wire.readableBytes());

            PlayerAuthInputPacket decoded = new PlayerAuthInputPacket();
            decoded.decode(wire);

            ByteBuf tail = decoded.rawBody();
            try {
                assertEquals(0, tail.readableBytes(), "no tail bytes when none were encoded");
            } finally {
                tail.release();
            }
        } finally {
            wire.release();
        }
    }

    @Test
    void leadingFloatOrderMatchesGophertunnel() {
        // Wire order per gophertunnel PlayerAuthInput.Marshal:
        //   Float32(Pitch), Float32(Yaw), Vec3(Position), Vec2(MoveVector), Float32(HeadYaw)
        // Encode unique sentinel values, then decode raw little-endian floats and verify
        // they appear in the documented slot order.
        PlayerAuthInputPacket src = new PlayerAuthInputPacket();
        src.setPitch(1.0f);
        src.setYaw(2.0f);
        src.setPosX(3.0f);
        src.setPosY(4.0f);
        src.setPosZ(5.0f);
        src.setMoveX(6.0f);
        src.setMoveZ(7.0f);
        src.setHeadYaw(8.0f);

        ByteBuf wire = Unpooled.buffer();
        try {
            src.encode(wire);
            assertEquals(1.0f, wire.readFloatLE(), 0f);   // pitch
            assertEquals(2.0f, wire.readFloatLE(), 0f);   // yaw
            assertEquals(3.0f, wire.readFloatLE(), 0f);   // pos.x
            assertEquals(4.0f, wire.readFloatLE(), 0f);   // pos.y
            assertEquals(5.0f, wire.readFloatLE(), 0f);   // pos.z
            assertEquals(6.0f, wire.readFloatLE(), 0f);   // move.x
            assertEquals(7.0f, wire.readFloatLE(), 0f);   // move.z
            assertEquals(8.0f, wire.readFloatLE(), 0f);   // headYaw
            assertEquals(0, wire.readableBytes());
        } finally {
            wire.release();
        }
    }
}
