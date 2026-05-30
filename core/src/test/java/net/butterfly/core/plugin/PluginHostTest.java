package net.butterfly.core.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import net.butterfly.api.async.Scheduler;
import net.butterfly.api.async.WorldView;
import net.butterfly.api.command.CommandRegistry;
import net.butterfly.api.entity.Player;
import net.butterfly.api.event.EventBus;
import net.butterfly.api.plugin.Plugin;
import net.butterfly.api.plugin.PluginManager;
import net.butterfly.api.plugin.Server;
import net.butterfly.api.world.World;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginHostTest {

    /** No-op Server stub. Plugins under test never call into it. */
    private static final Server STUB_SERVER = new Server() {
        @Override public String version() { return "test"; }
        @Override public int protocolVersion() { return 0; }
        @Override public World defaultWorld() { return null; }
        @Override public WorldView worldSnapshot() { return null; }
        @Override public EventBus eventBus() { return null; }
        @Override public PluginManager pluginManager() { return null; }
        @Override public CommandRegistry commandRegistry() { return null; }
        @Override public Scheduler scheduler() { return null; }
        @Override public Collection<? extends Player> onlinePlayers() { return List.of(); }
        @Override public Player playerByName(String name) { return null; }
        @Override public void shutdown() {}
    };

    @BeforeEach
    void resetCounters() {
        TestPluginCounter.reset();
    }

    private static final String YAML_TEMPLATE = """
            name: %s
            version: 1.0.0
            main: net.butterfly.core.plugin.TestPlugin
            """;

    @Test
    void loadEnableDisableInvokesLifecycle(@TempDir Path tmp) throws Exception {
        Path pluginsDir = Files.createDirectories(tmp.resolve("plugins"));
        Path dataDir = Files.createDirectories(tmp.resolve("data"));

        JarFixture.buildPluginJar(pluginsDir, "tp1",
                String.format(YAML_TEMPLATE, "TP1"),
                JarFixture.entry(TestPlugin.class));

        PluginHost host = new PluginHost(STUB_SERVER, pluginsDir, dataDir);

        host.loadAll();
        assertEquals(1, TestPluginCounter.loadCount, "onLoad must run during loadAll");
        assertEquals(0, TestPluginCounter.enableCount, "enableAll must NOT have run yet");

        PluginManager pm = host.pluginManager();
        assertEquals(1, pm.getPlugins().size());
        Plugin loaded = pm.getPlugin("TP1");
        assertNotNull(loaded);
        assertFalse(pm.isEnabled("TP1"), "plugin must not be enabled before enableAll");
        assertTrue(Files.isDirectory(dataDir.resolve("TP1")),
                "data folder must be created before init");

        host.enableAll();
        assertEquals(1, TestPluginCounter.enableCount);
        assertTrue(pm.isEnabled("TP1"));

        host.disableAll();
        assertEquals(1, TestPluginCounter.disableCount);
        assertFalse(pm.isEnabled("TP1"));
    }

    @Test
    void emptyPluginsDirIsNoop(@TempDir Path tmp) throws Exception {
        Path pluginsDir = Files.createDirectories(tmp.resolve("plugins"));
        Path dataDir = Files.createDirectories(tmp.resolve("data"));

        PluginHost host = new PluginHost(STUB_SERVER, pluginsDir, dataDir);
        host.loadAll();
        host.enableAll();
        host.disableAll();

        assertEquals(0, host.pluginManager().getPlugins().size());
    }

    @Test
    void missingPluginsDirIsTreatedAsEmpty(@TempDir Path tmp) {
        Path pluginsDir = tmp.resolve("nonexistent");
        Path dataDir = tmp.resolve("data");

        PluginHost host = new PluginHost(STUB_SERVER, pluginsDir, dataDir);
        host.loadAll();
        assertEquals(0, host.pluginManager().getPlugins().size());
    }

    @Test
    void getPluginByUnknownNameReturnsNull(@TempDir Path tmp) throws Exception {
        Path pluginsDir = Files.createDirectories(tmp.resolve("plugins"));
        Path dataDir = Files.createDirectories(tmp.resolve("data"));

        PluginHost host = new PluginHost(STUB_SERVER, pluginsDir, dataDir);
        host.loadAll();
        assertNull(host.pluginManager().getPlugin("does-not-exist"));
        assertFalse(host.pluginManager().isEnabled("does-not-exist"));
    }
}
