package net.butterfly.core.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.butterfly.raknet.RakConstants;
import net.butterfly.raknet.offline.UnconnectedHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * UDP listener that accepts Bedrock client connections, drives the offline (unconnected)
 * RakNet handshake, then hands each peer off to a {@link ClientSession} for the connected
 * phase.
 *
 * <p>Construction takes a {@link Consumer} that is invoked once a session is created
 * (after Open Connection Reply 2 has been written). The caller wires up
 * {@code onGameBatch} / {@code onControlPacket} on the session's {@link RakSession} and
 * subscribes a {@link LoginFlowHandler} to drive the post-RakNet Bedrock handshake.
 *
 * <p>This class only handles the offline portion: ping/pong + open-connection 1/2.
 * Connected datagrams are routed to the per-peer session's {@code rak.handleDatagram}.
 */
public final class ConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    /** MOTD fields baked into the unconnected pong response. */
    public record Motd(
        String name,
        int protocol,
        String minecraftVersion,
        int onlinePlayers,
        int maxPlayers,
        String levelName) {}

    private final Motd motd;
    private final Consumer<ClientSession> onSessionCreated;
    private final long serverGuid = System.nanoTime();

    private final Map<InetSocketAddress, ClientSession> sessions = new ConcurrentHashMap<>();

    private EventLoopGroup group;
    private Channel channel;

    public ConnectionManager(Motd motd, Consumer<ClientSession> onSessionCreated) {
        this.motd = motd;
        this.onSessionCreated = onSessionCreated;
    }

    public Motd motd() { return motd; }
    public long serverGuid() { return serverGuid; }
    public Map<InetSocketAddress, ClientSession> sessions() { return sessions; }

    /** Bind the UDP listener and start accepting datagrams. */
    public void start(String bindHost, int bindPort) throws InterruptedException {
        if (group != null) throw new IllegalStateException("already started");
        this.group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap()
            .group(group)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override protected void initChannel(NioDatagramChannel ch) {
                    ch.pipeline().addLast(new ListenerHandler(bindPort));
                }
            });
        this.channel = b.bind(bindHost, bindPort).sync().channel();
        log.info("listener up on {}:{}", bindHost, bindPort);
    }

    /** Shutdown the listener and event loop. Idempotent. */
    public void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }

    /** Visible for testing — handler installed on the listener channel. */
    final class ListenerHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final int bindPort;

        ListenerHandler(int bindPort) { this.bindPort = bindPort; }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket pkt) {
            ByteBuf in = pkt.content();
            if (!in.isReadable()) return;
            int id = in.getUnsignedByte(in.readerIndex());
            InetSocketAddress sender = pkt.sender();

            switch (id) {
                case RakConstants.ID_UNCONNECTED_PING -> { handlePing(ctx, pkt); return; }
                case RakConstants.ID_OPEN_CONNECTION_REQUEST_1 -> {
                    handleOpenConnectionRequest1(ctx, pkt, sender);
                    return;
                }
                case RakConstants.ID_OPEN_CONNECTION_REQUEST_2 -> {
                    handleOpenConnectionRequest2(ctx, pkt, sender);
                    return;
                }
                default -> { /* fall through to per-session */ }
            }

            ClientSession session = sessions.get(sender);
            if (session != null) session.rak().handleDatagram(in.retain());
        }

        private void handlePing(ChannelHandlerContext ctx, DatagramPacket pkt) {
            UnconnectedHandshake.Ping ping = UnconnectedHandshake.readPing(pkt.content().duplicate());
            String motdLine = "MCPE;" + motd.name() + ";" + motd.protocol() + ";"
                + motd.minecraftVersion() + ";" + motd.onlinePlayers() + ";" + motd.maxPlayers() + ";"
                + serverGuid + ";" + motd.levelName() + ";Survival;1;" + bindPort + ";" + bindPort + ";";
            ByteBuf out = Unpooled.buffer();
            UnconnectedHandshake.writePong(out, ping.time(), serverGuid, motdLine);
            ctx.writeAndFlush(new DatagramPacket(out, pkt.sender()));
        }

        private void handleOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket pkt, InetSocketAddress sender) {
            // The total UDP datagram size IS the MTU the client is probing for.
            int mtu = pkt.content().readableBytes() + RakConstants.UDP_HEADER_SIZE;
            mtu = Math.min(mtu, RakConstants.DEFAULT_MTU);
            ByteBuf out = Unpooled.buffer();
            UnconnectedHandshake.writeOpenConnectionReply1(out, serverGuid, false, mtu);
            ctx.writeAndFlush(new DatagramPacket(out, sender));
        }

        private void handleOpenConnectionRequest2(ChannelHandlerContext ctx, DatagramPacket pkt, InetSocketAddress sender) {
            UnconnectedHandshake.OpenConnRequest2 req =
                UnconnectedHandshake.readOpenConnectionRequest2(pkt.content().duplicate());

            ByteBuf out = Unpooled.buffer();
            UnconnectedHandshake.writeOpenConnectionReply2(out, serverGuid, sender, req.mtu(), false);
            ctx.writeAndFlush(new DatagramPacket(out, sender));

            RakSession rak = new RakSession(ctx.channel(), sender, req.mtu());
            ClientSession session = new ClientSession(rak);
            sessions.put(sender, session);
            try {
                onSessionCreated.accept(session);
            } catch (RuntimeException e) {
                log.error("session-created hook threw for {}", sender, e);
                sessions.remove(sender);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("listener error", cause);
        }
    }
}
