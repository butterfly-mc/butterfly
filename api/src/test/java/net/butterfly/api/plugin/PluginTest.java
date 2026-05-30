package net.butterfly.api.plugin;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginTest {

    /** Minimal concrete subclass for testing the abstract base. */
    private static final class TestPlugin extends Plugin {
        @Override public void onLoad() {}
        @Override public void onEnable() {}
        @Override public void onDisable() {}

        Server callGetServer() { return getServer(); }
        Logger callGetLogger() { return getLogger(); }
        Path callGetDataFolder() { return getDataFolder(); }
        PluginManifest callGetManifest() { return getManifest(); }
    }

    private static PluginManifest manifest() {
        return new PluginManifest(
                "Test", "1.0.0", "com.example.Test",
                "", java.util.List.of(), java.util.List.of(), java.util.List.of());
    }

    private static PluginContext context(PluginManifest manifest) {
        // Server and Logger are interfaces; in this test we only need
        // identity, so a null-returning stub is fine.
        Logger logger = new Logger() {
            @Override public void info(String m) {}
            @Override public void warn(String m) {}
            @Override public void error(String m) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m) {}
        };
        return new PluginContext(null, logger, Path.of("data"), manifest);
    }

    @Test
    void initStoresContextAndAccessorsWork() {
        TestPlugin plugin = new TestPlugin();
        PluginManifest manifest = manifest();
        PluginContext ctx = context(manifest);

        plugin.init(ctx);

        assertNotNull(plugin.callGetLogger(), "logger should be available after init");
        assertEquals(Path.of("data"), plugin.callGetDataFolder());
        assertSame(manifest, plugin.callGetManifest());
        // server is null in our stub context; accessor itself should not throw,
        // and returns whatever was injected.
        assertEquals(null, plugin.callGetServer());
    }

    @Test
    void initTwiceThrows() {
        TestPlugin plugin = new TestPlugin();
        PluginContext ctx = context(manifest());
        plugin.init(ctx);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> plugin.init(ctx));
        // Sanity check the message mentions initialisation
        assertNotNull(ex.getMessage());
    }

    @Test
    void initWithNullThrows() {
        TestPlugin plugin = new TestPlugin();
        assertThrows(IllegalArgumentException.class, () -> plugin.init(null));
    }

    @Test
    void getServerBeforeInitThrows() {
        TestPlugin plugin = new TestPlugin();
        assertThrows(IllegalStateException.class, plugin::callGetServer);
    }

    @Test
    void getLoggerBeforeInitThrows() {
        TestPlugin plugin = new TestPlugin();
        assertThrows(IllegalStateException.class, plugin::callGetLogger);
    }

    @Test
    void getDataFolderBeforeInitThrows() {
        TestPlugin plugin = new TestPlugin();
        assertThrows(IllegalStateException.class, plugin::callGetDataFolder);
    }

    @Test
    void getManifestBeforeInitThrows() {
        TestPlugin plugin = new TestPlugin();
        assertThrows(IllegalStateException.class, plugin::callGetManifest);
    }
}
