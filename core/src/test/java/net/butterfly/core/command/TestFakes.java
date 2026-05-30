package net.butterfly.core.command;

import net.butterfly.api.async.Scheduler;
import net.butterfly.api.async.WorldView;
import net.butterfly.api.command.CommandRegistry;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.entity.EntityType;
import net.butterfly.api.entity.Player;
import net.butterfly.api.event.EventBus;
import net.butterfly.api.plugin.PluginManager;
import net.butterfly.api.plugin.Server;
import net.butterfly.api.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight in-memory test doubles for the {@code core/command} test suite.
 *
 * <p>Only the slice of {@link Server} / {@link Player} / {@link CommandSender} that the default
 * commands actually exercise is implemented; everything else throws
 * {@link UnsupportedOperationException} so unintended dependencies surface loudly in tests.
 */
final class TestFakes {

    private TestFakes() {
        // helper container
    }

    /** {@link CommandSender} that records every {@code sendMessage} into a list. */
    static final class FakeSender implements CommandSender {
        private final String name;
        private final boolean player;
        final List<String> messages = new ArrayList<>();

        FakeSender(String name, boolean player) {
            this.name = name;
            this.player = player;
        }

        static FakeSender console() {
            return new FakeSender("CONSOLE", false);
        }

        static FakeSender player(String name) {
            return new FakeSender(name, true);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public boolean isPlayer() {
            return player;
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }
    }

    /** Minimal {@link Player} stub that records teleport calls. */
    static final class FakePlayer implements Player {
        private final String name;
        final List<String> messages = new ArrayList<>();
        int teleportCount;
        World lastTeleportWorld;
        double lastX;
        double lastY;
        double lastZ;

        FakePlayer(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String xuid() {
            return "";
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public void kick(String reason) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void sendChat(String from, String message) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public UUID uuid() {
            return new UUID(0, name.hashCode());
        }

        @Override
        public EntityType type() {
            return EntityType.PLAYER;
        }

        @Override
        public World world() {
            return null;
        }

        @Override
        public double x() {
            return 0;
        }

        @Override
        public double y() {
            return 0;
        }

        @Override
        public double z() {
            return 0;
        }

        @Override
        public float yaw() {
            return 0f;
        }

        @Override
        public float pitch() {
            return 0f;
        }

        @Override
        public void teleport(World world, double x, double y, double z) {
            teleportCount++;
            lastTeleportWorld = world;
            lastX = x;
            lastY = y;
            lastZ = z;
        }
    }

    /** {@link Server} that exposes a fixed version, online roster, and shutdown counter. */
    static final class FakeServer implements Server {
        private final String version;
        private final int protocolVersion;
        private final CommandRegistry registry;
        private final Map<String, FakePlayer> players = new HashMap<>();
        int shutdownCalls;

        FakeServer(String version, int protocolVersion) {
            this.version = version;
            this.protocolVersion = protocolVersion;
            this.registry = new CommandRegistryImpl();
        }

        FakePlayer addPlayer(String name) {
            FakePlayer p = new FakePlayer(name);
            players.put(name, p);
            return p;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public int protocolVersion() {
            return protocolVersion;
        }

        @Override
        public World defaultWorld() {
            return null;
        }

        @Override
        public WorldView worldSnapshot() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public PluginManager pluginManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CommandRegistry commandRegistry() {
            return registry;
        }

        @Override
        public Scheduler scheduler() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            return Collections.unmodifiableCollection(players.values());
        }

        @Override
        public Player playerByName(String name) {
            return players.get(name);
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }
    }
}
