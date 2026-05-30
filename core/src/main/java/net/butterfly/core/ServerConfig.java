package net.butterfly.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Bootstrap configuration parsed from {@code server.properties} (Bukkit-style).
 * Falls back to packaged defaults if no file is present at startup.
 */
public record ServerConfig(
    String serverName,
    String bindHost,
    int bindPort,
    String levelName,
    Path worldsDir,
    Path pluginsDir,
    Path pluginDataDir,
    int maxPlayers,
    int viewDistance
) {
    public static final int DEFAULT_PORT = 19132;
    public static final String DEFAULT_LEVEL = "world";

    public static ServerConfig defaults() {
        return new ServerConfig(
            "Butterfly server",
            "0.0.0.0",
            DEFAULT_PORT,
            DEFAULT_LEVEL,
            Path.of("worlds"),
            Path.of("plugins"),
            Path.of("plugin-data"),
            20,
            8
        );
    }

    public static ServerConfig load(Reader r) throws IOException {
        Properties p = new Properties();
        p.load(r);
        ServerConfig d = defaults();
        return new ServerConfig(
            p.getProperty("server-name", d.serverName),
            p.getProperty("server-bind", d.bindHost),
            Integer.parseInt(p.getProperty("server-port", String.valueOf(d.bindPort))),
            p.getProperty("level-name", d.levelName),
            Path.of(p.getProperty("worlds-dir", d.worldsDir.toString())),
            Path.of(p.getProperty("plugins-dir", d.pluginsDir.toString())),
            Path.of(p.getProperty("plugin-data-dir", d.pluginDataDir.toString())),
            Integer.parseInt(p.getProperty("max-players", String.valueOf(d.maxPlayers))),
            Integer.parseInt(p.getProperty("view-distance", String.valueOf(d.viewDistance)))
        );
    }

    public static ServerConfig loadOrDefault(Path path) {
        if (path == null || !Files.exists(path)) return defaults();
        try (Reader r = Files.newBufferedReader(path)) {
            return load(r);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    public static ServerConfig fromClasspathOrDefault(String resource) {
        try (InputStream is = ServerConfig.class.getResourceAsStream(resource)) {
            if (is == null) return defaults();
            return load(new java.io.InputStreamReader(is));
        } catch (IOException e) {
            return defaults();
        }
    }

    public Path worldDir() { return worldsDir.resolve(levelName); }
}
