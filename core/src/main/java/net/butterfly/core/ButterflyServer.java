package net.butterfly.core;

import net.butterfly.api.async.Scheduler;
import net.butterfly.api.async.WorldView;
import net.butterfly.api.command.CommandRegistry;
import net.butterfly.api.entity.Player;
import net.butterfly.api.event.EventBus;
import net.butterfly.api.event.events.PlayerChatEvent;
import net.butterfly.api.event.events.PlayerJoinEvent;
import net.butterfly.api.event.events.PlayerPreLoginEvent;
import net.butterfly.api.event.events.PlayerQuitEvent;
import net.butterfly.api.event.events.ServerStartEvent;
import net.butterfly.api.event.events.ServerStopEvent;
import net.butterfly.api.plugin.PluginManager;
import net.butterfly.api.plugin.Server;
import net.butterfly.api.world.World;
import net.butterfly.codec.Protocol;
import net.butterfly.core.command.CommandRegistryImpl;
import net.butterfly.core.command.DefaultCommands;
import net.butterfly.core.entity.PlayerImpl;
import net.butterfly.core.event.EventBusImpl;
import net.butterfly.core.network.ClientSession;
import net.butterfly.core.network.ConnectionManager;
import net.butterfly.core.network.LoginFlowHandler;
import net.butterfly.core.network.LoginFlowHandler.ServerIdentity;
import net.butterfly.core.plugin.PluginHost;
import net.butterfly.core.tick.PhasedPipeline;
import net.butterfly.core.tick.SchedulerImpl;
import net.butterfly.core.tick.TickLoop;
import net.butterfly.core.tick.WorldSnapshotPublisher;
import net.butterfly.core.world.LevelDb;
import net.butterfly.core.world.WorldImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Butterfly server — facade that wires every core component together and exposes
 * the {@link Server} interface to plugins. Lifecycle:
 *
 * <pre>
 *   new ButterflyServer(config)
 *     → start()    // open world, start tick loop, bind network, load+enable plugins
 *     → (running)
 *     → shutdown() // disable plugins, stop network, stop tick loop, close world
 * </pre>
 */
public final class ButterflyServer implements Server {
    private static final Logger log = LoggerFactory.getLogger(ButterflyServer.class);
    private static final String VERSION = "0.1.0-MVP";

    private final ServerConfig config;
    private final EventBusImpl eventBus = new EventBusImpl();
    private final CommandRegistryImpl commandRegistry = new CommandRegistryImpl();
    private final TickLoop tickLoop = new TickLoop(20);
    private final ForkJoinPool asyncPool = ForkJoinPool.commonPool();
    private final SchedulerImpl scheduler;
    private final ExecutorService decodePool = Executors.newWorkStealingPool();
    private final ExecutorService encodePool = Executors.newWorkStealingPool();
    private final WorldSnapshotPublisher snapshotPublisher = new WorldSnapshotPublisher();
    private final ServerIdentity serverIdentity = ServerIdentity.generate();
    private final ConcurrentHashMap<String, Player> onlineByName = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LevelDb levelDb;
    private WorldImpl defaultWorld;
    private ConnectionManager connectionManager;
    private PluginHost pluginHost;
    private PhasedPipeline pipeline;

    public ButterflyServer(ServerConfig config) {
        this.config = config;
        this.scheduler = new SchedulerImpl(tickLoop, asyncPool);
    }

    public ServerConfig config() { return config; }

    /** Open world, register defaults, start tick + network, load plugins. */
    public void start() {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("already started");
        log.info("Butterfly v{} starting (protocol {} / MC {})", VERSION, Protocol.VERSION, Protocol.MINECRAFT_VERSION);

        try {
            ensureDirs();
            openWorld();

            DefaultCommands.registerAll(this, commandRegistry);

            tickLoop.start();
            tickLoop.setPostTickHook(() -> snapshotPublisher.publish(tickLoop.currentTick(), defaultWorld));
            pipeline = new PhasedPipeline(decodePool, encodePool, this::peerHandles);
            pipeline.installInto(tickLoop);

            startNetwork();

            pluginHost = new PluginHost(this, config.pluginsDir(), config.pluginDataDir());
            pluginHost.loadAll();
            pluginHost.enableAll();

            eventBus.fire(new ServerStartEvent());
            log.info("Server started on {}:{}", config.bindHost(), config.bindPort());
        } catch (RuntimeException | IOException e) {
            log.error("Startup failed; rolling back", e);
            shutdownInternal();
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        }
    }

    private void ensureDirs() throws IOException {
        Files.createDirectories(config.worldsDir());
        Files.createDirectories(config.pluginsDir());
        Files.createDirectories(config.pluginDataDir());
    }

    private void openWorld() throws IOException {
        Files.createDirectories(config.worldDir());
        levelDb = LevelDb.open(config.worldDir());
        defaultWorld = new WorldImpl(config.levelName(), levelDb, 0, tickLoop.thread());
        log.info("Opened world '{}' at {}", config.levelName(), config.worldDir());
    }

    private void startNetwork() {
        ConnectionManager.Motd motd = new ConnectionManager.Motd(
            config.serverName(),
            Protocol.VERSION,
            Protocol.MINECRAFT_VERSION,
            0,
            config.maxPlayers(),
            config.levelName()
        );
        connectionManager = new ConnectionManager(motd, this::onSessionCreated);
        try {
            connectionManager.start(config.bindHost(), config.bindPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while binding network", e);
        }
    }

    private void onSessionCreated(ClientSession session) {
        // Construct the login handler — its constructor wires session.onPackets(...).
        LoginFlowHandler login = new LoginFlowHandler(session, serverIdentity, defaultWorld);

        // Once the handshake captures displayName + xuid (right before AWAIT_CHUNK_RADIUS_REQUEST),
        // build the PlayerImpl, run PreLogin checks, register them in the online roster and
        // broadcast Join. The hook fires on the tick thread (decode is dispatched there).
        login.setOnPlayerSpawn(this::handlePlayerSpawn);

        // Quit cleanup — invoked when the session is torn down (kick or transport drop).
        login.setOnSessionClosed(() -> handleSessionClosed(session));

        // Chat: dispatched from LoginFlowHandler's ACTIVE state for inbound Text packets.
        login.setOnChat(this::handleChat);

        // Inbound batches arrive on the Netty event loop. Decode + dispatch mutate session
        // and world state and fire plugin events, so they MUST run on the tick thread.
        // Copy the bytes off the ByteBuf synchronously (Netty thread owns the buffer) and
        // defer the actual handleInboundBatch to the next tick via the scheduler.
        //
        // TODO(perf): this funnels every peer's decode through a single tick thread. Once
        //   ClientSession exposes inbox/outbox queues + a pure decodeBatch step, the
        //   PhasedPipeline (already constructed in start()) can move decrypt/decompress/
        //   decode onto its parallel decodePool and encryption onto its parallel encodePool,
        //   leaving only dispatch on the tick thread. See PhasedPipeline / PeerHandle —
        //   the workers are wired but not yet exercised per-peer.
        session.rak().onGameBatch(buf -> {
            byte[] wire = new byte[buf.readableBytes()];
            buf.readBytes(wire);
            scheduler.runOnMain(() -> {
                try {
                    session.handleInboundBatch(wire);
                } catch (Exception e) {
                    log.warn("login flow error for {}: {}", session.rak().remote(), e.toString());
                }
            });
        });
    }

    /**
     * Build a {@link PlayerImpl} from the captured login identity, fire
     * {@link PlayerPreLoginEvent} (kicking the client if cancelled), then add the
     * player to the online roster and fire {@link PlayerJoinEvent}. Runs on the tick
     * thread.
     */
    private void handlePlayerSpawn(ClientSession session) {
        String name = session.displayName();
        String xuid = session.xuid();
        UUID uuid = uuidFor(name, xuid);

        PlayerImpl player = new PlayerImpl(
            name, xuid, uuid, defaultWorld, session, this,
            /* spawnX = */ 0.0, /* spawnY = */ 100.0, /* spawnZ = */ 0.0);

        // PreLogin — cancellable. If a listener cancels, kick the client with the
        // listener-supplied reason (or a generic fallback) and abort.
        PlayerPreLoginEvent pre = new PlayerPreLoginEvent(name, xuid);
        try { eventBus.fire(pre); }
        catch (Exception e) { log.warn("PlayerPreLoginEvent listener threw for {}: {}", name, e.toString()); }
        if (pre.isCancelled()) {
            String reason = pre.getKickReason() != null ? pre.getKickReason() : "Disconnected";
            log.info("PreLogin cancelled for {}: {}", name, reason);
            try { player.kick(reason); }
            catch (Exception e) { log.debug("kick after PreLogin-cancel failed: {}", e.toString()); }
            return;
        }

        // Admit the player. {@code putIfAbsent} guards against a duplicate-name race —
        // we lose the new connection in that case (Bedrock allows only one client per
        // display name on the same server).
        Player existing = onlineByName.putIfAbsent(name, player);
        if (existing != null) {
            log.info("rejecting duplicate join for {} (already online)", name);
            try { player.kick("Already connected"); }
            catch (Exception e) { log.debug("kick on duplicate-join failed: {}", e.toString()); }
            return;
        }

        // Join — fire the event with a default join message; listeners may rewrite it.
        String defaultJoin = name + " joined the game";
        PlayerJoinEvent join = new PlayerJoinEvent(name, xuid, defaultJoin);
        try { eventBus.fire(join); }
        catch (Exception e) { log.warn("PlayerJoinEvent listener threw for {}: {}", name, e.toString()); }
        String joinMessage = join.getJoinMessage();
        if (joinMessage != null && !joinMessage.isEmpty()) broadcastSystem(joinMessage);
    }

    /**
     * Remove the player from the online roster and fire {@link PlayerQuitEvent}. Idempotent —
     * second invocations for the same session are silently dropped.
     */
    private void handleSessionClosed(ClientSession session) {
        String name = session.displayName();
        if (name == null || name.isEmpty()) return;   // never made it past Login
        Player removed = onlineByName.remove(name);
        if (removed == null) return;                  // already cleaned up

        String xuid = session.xuid();
        String defaultQuit = name + " left the game";
        PlayerQuitEvent quit = new PlayerQuitEvent(name, xuid, defaultQuit);
        try { eventBus.fire(quit); }
        catch (Exception e) { log.warn("PlayerQuitEvent listener threw for {}: {}", name, e.toString()); }
        String quitMessage = quit.getQuitMessage();
        if (quitMessage != null && !quitMessage.isEmpty()) broadcastSystem(quitMessage);
    }

    /**
     * Fire {@link PlayerChatEvent} for an inbound chat message and, if not cancelled,
     * broadcast it to every online player. The author and message can be rewritten by
     * listeners.
     */
    private void handleChat(String from, String message) {
        PlayerChatEvent chat = new PlayerChatEvent(from, message);
        try { eventBus.fire(chat); }
        catch (Exception e) { log.warn("PlayerChatEvent listener threw for {}: {}", from, e.toString()); }
        if (chat.isCancelled()) return;
        String body = chat.getMessage();
        if (body == null || body.isEmpty()) return;
        broadcastChat(from, body);
    }

    /**
     * Derive a stable UUID for a player from {@code xuid} when available, falling back
     * to {@code name}-based hashing. Bedrock authoritative identity is XUID for online
     * players; for offline mode we pick a name-derived UUID so reconnects line up.
     */
    private static UUID uuidFor(String name, String xuid) {
        String seed = (xuid != null && !xuid.isEmpty()) ? "xuid:" + xuid : "name:" + name;
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Broadcast a system message (no source name) to every online player. */
    public void broadcastSystem(String message) {
        if (message == null) return;
        for (Player p : onlineByName.values()) {
            try { p.sendMessage(message); }
            catch (Exception e) { log.debug("broadcastSystem send to {} failed: {}", p.name(), e.toString()); }
        }
    }

    /** Broadcast a chat message attributed to {@code from} to every online player. */
    public void broadcastChat(String from, String message) {
        if (message == null) return;
        for (Player p : onlineByName.values()) {
            try { p.sendChat(from, message); }
            catch (Exception e) { log.debug("broadcastChat send to {} failed: {}", p.name(), e.toString()); }
        }
    }

    private Collection<net.butterfly.core.tick.PeerHandle> peerHandles() {
        if (connectionManager == null) return Collections.emptyList();
        return connectionManager.sessions().values().stream()
            .<net.butterfly.core.tick.PeerHandle>map(PeerHandleAdapter::new)
            .toList();
    }

    /** Adapter — for now the network layer + LoginFlowHandler decrypt+decode inline,
     *  so the phased pipeline doesn't have decode/encode work to do per peer.
     *  Plan B / future iterations move that work into the pipeline workers. */
    private static final class PeerHandleAdapter implements net.butterfly.core.tick.PeerHandle {
        @SuppressWarnings("unused") private final ClientSession session;
        PeerHandleAdapter(ClientSession s) { this.session = s; }
        @Override public byte[][] drainInbox() { return new byte[0][]; }
        @Override public void simulate(byte[] batch) { /* no-op for MVP */ }
        @Override public byte[][] drainOutbox() { return new byte[0][]; }
        @Override public void encodeAndSend(byte[] batch) { /* no-op for MVP */ }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Shutting down");
        try {
            eventBus.fire(new ServerStopEvent());
        } catch (Exception e) {
            log.warn("ServerStopEvent listener threw", e);
        }
        shutdownInternal();
    }

    private void shutdownInternal() {
        if (pluginHost != null) {
            try { pluginHost.disableAll(); } catch (Exception e) { log.warn("plugin disable error", e); }
        }
        if (connectionManager != null) {
            try { connectionManager.stop(); } catch (Exception e) { log.warn("network stop error", e); }
        }
        try { tickLoop.stop(); } catch (Exception e) { log.warn("tick loop stop error", e); }
        decodePool.shutdownNow();
        encodePool.shutdownNow();
        if (defaultWorld != null) {
            try { defaultWorld.unloadAll(); } catch (Exception e) { log.warn("world unload error", e); }
        }
        if (levelDb != null) {
            try { levelDb.close(); } catch (Exception e) { log.warn("leveldb close error", e); }
        }
        log.info("Shutdown complete");
    }

    // ---- Server interface ----
    @Override public String version() { return VERSION; }
    @Override public int protocolVersion() { return Protocol.VERSION; }
    @Override public World defaultWorld() { return defaultWorld; }
    @Override public WorldView worldSnapshot() {
        WorldView v = snapshotPublisher.snapshot();
        return v != null ? v : EmptyWorldView.INSTANCE;
    }
    @Override public EventBus eventBus() { return eventBus; }
    @Override public PluginManager pluginManager() {
        return pluginHost != null ? pluginHost.pluginManager() : EmptyPluginManager.INSTANCE;
    }
    @Override public CommandRegistry commandRegistry() { return commandRegistry; }
    @Override public Scheduler scheduler() { return scheduler; }
    @Override public Collection<? extends Player> onlinePlayers() { return List.copyOf(onlineByName.values()); }
    @Override public Player playerByName(String name) { return name == null ? null : onlineByName.get(name); }
}
