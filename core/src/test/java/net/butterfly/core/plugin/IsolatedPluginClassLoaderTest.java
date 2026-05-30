package net.butterfly.core.plugin;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import net.butterfly.api.plugin.Logger;
import net.butterfly.api.plugin.Plugin;
import net.butterfly.api.plugin.Server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IsolatedPluginClassLoaderTest {

    @Test
    void apiClassesResolveViaParent(@TempDir Path tmp) throws Exception {
        // Empty jar with a manifest is enough — we're only checking parent delegation.
        Path jar = JarFixture.buildPluginJar(tmp, "empty",
                "name: Empty\nversion: 1.0.0\nmain: com.example.None\n");

        ClassLoader parent = Plugin.class.getClassLoader();
        try (IsolatedPluginClassLoader loader = new IsolatedPluginClassLoader(
                new URL[]{jar.toUri().toURL()}, parent)) {

            Class<?> p = loader.loadClass(Plugin.class.getName());
            assertSame(Plugin.class, p, "Plugin must come from the api classloader");

            Class<?> l = loader.loadClass(Logger.class.getName());
            assertSame(Logger.class, l, "Logger must come from the api classloader");

            Class<?> s = loader.loadClass(Server.class.getName());
            assertSame(Server.class, s, "Server must come from the api classloader");

            Class<?> str = loader.loadClass("java.lang.String");
            assertSame(String.class, str, "java.* must come from the bootstrap classloader");
        }
    }

    @Test
    void pluginClassLoadsFromJarAndShareSharedTypes(@TempDir Path tmp) throws Exception {
        Path jar = JarFixture.buildPluginJar(tmp, "tp",
                "name: TP\nversion: 1.0.0\nmain: net.butterfly.core.plugin.TestPlugin\n",
                JarFixture.entry(TestPlugin.class));

        ClassLoader parent = Plugin.class.getClassLoader();
        try (IsolatedPluginClassLoader loader = new IsolatedPluginClassLoader(
                new URL[]{jar.toUri().toURL()}, parent)) {

            Class<?> loadedTp = loader.loadClass(TestPlugin.class.getName());
            assertNotEquals(TestPlugin.class, loadedTp,
                    "loaded plugin class should be a different Class instance from the test classpath copy");
            assertTrue(Plugin.class.isAssignableFrom(loadedTp),
                    "loaded plugin class must still extend the shared api Plugin type");
        }
    }

    @Test
    void missingClassThrows(@TempDir Path tmp) throws IOException {
        Path jar = JarFixture.buildPluginJar(tmp, "empty",
                "name: Empty\nversion: 1.0.0\nmain: com.example.None\n");

        ClassLoader parent = Plugin.class.getClassLoader();
        try (IsolatedPluginClassLoader loader = new IsolatedPluginClassLoader(
                new URL[]{jar.toUri().toURL()}, parent)) {
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.example.DoesNotExist"));
        }
    }
}
