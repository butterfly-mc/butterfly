package net.butterfly.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import net.butterfly.raknet.AckRange;
import net.butterfly.raknet.Frame;
import net.butterfly.raknet.FrameSet;
import net.butterfly.raknet.OrderingQueue;
import net.butterfly.raknet.RakConstants;
import net.butterfly.raknet.Reliability;
import net.butterfly.raknet.SplitAssembler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Server-side per-peer RakNet session.
 *
 * <p>Mirrors the proxy's {@code RakConnection} but for the listener side: we are the
 * server peer and write to a shared listener channel via {@link DatagramPacket} addressed
 * to the remote. Tracks outbound reliable / ordering / split-id counters, dispatches
 * inbound FrameSets through a {@link SplitAssembler} + {@link OrderingQueue}, sends ACKs
 * lazily, and fragments outbound batches that exceed the negotiated MTU.
 *
 * <p>Game packets (RakNet message id {@code 0xfe}) are delivered to {@link #onGameBatch}
 * with the leading {@code 0xfe} stripped; control packets (Connection Request, Connected
 * Ping, etc.) are delivered raw to {@link #onControlPacket}.
 *
 * <p>This class is intentionally transport-only — it knows nothing about Bedrock packet
 * encoding, compression, or encryption.
 */
public final class RakSession {
    private final Channel channel;
    private final InetSocketAddress remote;
    private int mtu;

    private final AtomicInteger outgoingSequence = new AtomicInteger(0);
    private final AtomicInteger outgoingReliable = new AtomicInteger(0);
    private final AtomicInteger outgoingOrdering = new AtomicInteger(0);
    private final AtomicInteger outgoingSplitId = new AtomicInteger(0);

    private final SplitAssembler splitAssembler = new SplitAssembler();
    private final OrderingQueue orderingQueue = new OrderingQueue();

    private final List<Integer> pendingAcks = new ArrayList<>();

    private Consumer<ByteBuf> onGameBatch = b -> {};
    private Consumer<ByteBuf> onControlPacket = b -> {};

    public RakSession(Channel channel, InetSocketAddress remote, int mtu) {
        this.channel = channel;
        this.remote = remote;
        this.mtu = mtu;
    }

    public InetSocketAddress remote() { return remote; }
    public int mtu() { return mtu; }
    public void setMtu(int mtu) { this.mtu = mtu; }

    public void onGameBatch(Consumer<ByteBuf> handler) { this.onGameBatch = handler; }
    public void onControlPacket(Consumer<ByteBuf> handler) { this.onControlPacket = handler; }

    // ---- Inbound ----

    /** Process a connected datagram (frame set, ACK, or NACK) addressed to this peer. */
    public void handleDatagram(ByteBuf datagram) {
        int flags = datagram.getUnsignedByte(datagram.readerIndex());
        if ((flags & RakConstants.FLAG_VALID) == 0) return;

        if ((flags & RakConstants.FLAG_ACK) != 0) return;     // ACK — no resend queue yet
        if ((flags & RakConstants.FLAG_NACK) != 0) return;    // NACK — same

        FrameSet set = FrameSet.decode(datagram);
        synchronized (pendingAcks) { pendingAcks.add(set.sequenceNumber()); }
        for (Frame frame : set.frames()) handleFrame(frame);
        flushAcks();
    }

    private void handleFrame(Frame frame) {
        ByteBuf payload;
        if (frame.isFragmented()) {
            payload = splitAssembler.offer(frame);
            if (payload == null) return;
        } else {
            payload = frame.payload().retain();
        }

        if (frame.reliability().isOrdered()) {
            Frame ordered = Frame.create(frame.reliability(), payload);
            ordered.setReliableIndex(frame.reliableIndex());
            ordered.setOrderingIndex(frame.orderingIndex());
            ordered.setOrderingChannel(frame.orderingChannel());
            for (Frame released : orderingQueue.offer(ordered)) {
                dispatchPayload(released.payload());
                released.payload().release();
            }
        } else {
            dispatchPayload(payload);
            payload.release();
        }
    }

    private void dispatchPayload(ByteBuf payload) {
        if (!payload.isReadable()) return;
        int id = payload.getUnsignedByte(payload.readerIndex());
        if (id == RakConstants.ID_GAME_PACKET) {
            payload.skipBytes(1);
            onGameBatch.accept(payload);
        } else {
            onControlPacket.accept(payload);
        }
    }

    /** Coalesce buffered datagram sequence numbers and write them out as ACK ranges. */
    public void flushAcks() {
        List<AckRange> ranges;
        synchronized (pendingAcks) {
            if (pendingAcks.isEmpty()) return;
            ranges = coalesce(pendingAcks);
            pendingAcks.clear();
        }
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RakConstants.FLAG_VALID | RakConstants.FLAG_ACK);
        AckRange.encode(buf, ranges);
        channel.writeAndFlush(new DatagramPacket(buf, remote));
    }

    private static List<AckRange> coalesce(List<Integer> seqs) {
        seqs.sort(Integer::compare);
        List<AckRange> out = new ArrayList<>();
        int start = seqs.get(0), prev = start;
        for (int i = 1; i < seqs.size(); i++) {
            int s = seqs.get(i);
            if (s == prev + 1) { prev = s; continue; }
            out.add(new AckRange(start, prev));
            start = prev = s;
        }
        out.add(new AckRange(start, prev));
        return out;
    }

    // ---- Outbound ----

    /** Send a single control packet (Connection Request Accepted, Connected Pong, etc.). */
    public void sendControl(ByteBuf payload) {
        sendReliable(payload, false);
    }

    /** Send a Bedrock batch (already wrapped via {@link SessionCipher#wrap(byte[])}) reliably-ordered. */
    public void sendGameBatch(byte[] batch) {
        ByteBuf buf = Unpooled.buffer(1 + batch.length);
        buf.writeByte(RakConstants.ID_GAME_PACKET);
        buf.writeBytes(batch);
        sendReliable(buf, true);
    }

    private void sendReliable(ByteBuf payload, boolean releasePayload) {
        try {
            int maxFrameSize = mtu - 60;             // headroom for UDP+RakNet overhead
            if (payload.readableBytes() <= maxFrameSize) {
                Frame frame = Frame.create(Reliability.RELIABLE_ORDERED, payload.retainedSlice());
                frame.setReliableIndex(outgoingReliable.getAndIncrement());
                frame.setOrderingIndex(outgoingOrdering.getAndIncrement());
                frame.setOrderingChannel(0);
                writeFrame(frame);
                frame.payload().release();
            } else {
                fragmentAndSend(payload, maxFrameSize);
            }
        } finally {
            if (releasePayload) payload.release();
        }
    }

    private void fragmentAndSend(ByteBuf payload, int maxFrameSize) {
        int total = payload.readableBytes();
        int splitCount = (total + maxFrameSize - 1) / maxFrameSize;
        int splitId = outgoingSplitId.getAndIncrement() & 0xFFFF;
        int orderingIndex = outgoingOrdering.getAndIncrement();
        for (int i = 0; i < splitCount; i++) {
            int len = Math.min(maxFrameSize, total - i * maxFrameSize);
            ByteBuf slice = payload.retainedSlice(payload.readerIndex() + i * maxFrameSize, len);
            Frame frame = Frame.create(Reliability.RELIABLE_ORDERED, slice);
            frame.setReliableIndex(outgoingReliable.getAndIncrement());
            frame.setOrderingIndex(orderingIndex);
            frame.setOrderingChannel(0);
            frame.setFragment(splitCount, splitId, i);
            writeFrame(frame);
            slice.release();
        }
    }

    private void writeFrame(Frame frame) {
        FrameSet set = new FrameSet(outgoingSequence.getAndIncrement(), List.of(frame));
        ByteBuf datagram = Unpooled.buffer();
        set.encode(datagram);
        channel.writeAndFlush(new DatagramPacket(datagram, remote));
    }
}
