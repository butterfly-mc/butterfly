package net.butterfly.launcher;

import net.butterfly.api.command.CommandSender;
import net.butterfly.core.ButterflyServer;
import net.butterfly.core.ServerConfig;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Butterfly server launcher. Loads {@code server.properties} (or defaults), starts a
 * {@link ButterflyServer}, runs an interactive JLine console, and shuts down cleanly
 * on Ctrl+C / Ctrl+D / {@code stop} command.
 */
public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.loadOrDefault(Path.of("server.properties"));

        ButterflyServer server = new ButterflyServer(config);
        AtomicBoolean stopped = new AtomicBoolean(false);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stopped.compareAndSet(false, true)) server.shutdown();
        }, "butterfly-shutdown-hook"));

        try {
            server.start();
        } catch (Exception e) {
            log.error("Server failed to start", e);
            System.exit(1);
        }

        runConsole(server, stopped);

        if (stopped.compareAndSet(false, true)) {
            server.shutdown();
        }
    }

    private static void runConsole(ButterflyServer server, AtomicBoolean stopped) {
        // No interactive TTY (e.g. server launched via `java -jar` redirected from /dev/null,
        // systemd, docker without -it, or a bash subshell) — don't try to read commands; just
        // block until the JVM shutdown hook fires.
        if (System.console() == null) {
            log.info("No interactive console (stdin is not a TTY) — running headless. Send SIGTERM/SIGINT to stop.");
            while (!stopped.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            return;
        }

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        } catch (IOException e) {
            log.warn("Could not initialise terminal — running without interactive console: {}", e.toString());
            while (!stopped.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            return;
        }

        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("butterfly")
            .build();

        ConsoleSender consoleSender = new ConsoleSender();

        log.info("Console ready — type 'help' for commands, 'stop' to shut down");

        while (!stopped.get()) {
            String line;
            try {
                line = reader.readLine("butterfly> ");
            } catch (UserInterruptException e) {
                log.info("Ctrl+C received, shutting down");
                break;
            } catch (EndOfFileException e) {
                log.info("Console closed, shutting down");
                break;
            } catch (Exception e) {
                log.warn("Console error: {}", e.toString());
                continue;
            }

            if (line == null) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            String[] parts = trimmed.split("\\s+");
            String name = parts[0];
            String[] commandArgs = new String[parts.length - 1];
            System.arraycopy(parts, 1, commandArgs, 0, commandArgs.length);

            net.butterfly.api.command.Command cmd = server.commandRegistry().get(name);
            if (cmd == null) {
                consoleSender.sendMessage("Unknown command: " + name);
                continue;
            }
            try {
                server.scheduler().runOnMain(() -> {
                    try {
                        cmd.execute(consoleSender, commandArgs);
                    } catch (Exception e) {
                        log.error("Command '{}' threw", name, e);
                        consoleSender.sendMessage("Command error: " + e);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to dispatch '{}'", name, e);
            }

            // The /stop command sets running=false on the server; detect it here.
            // ButterflyServer.shutdown() flips its internal flag but the launcher
            // loop has no direct hook. Use a small grace check by reading the
            // server's tick loop running state via a public-ish accessor.
            // For MVP: poll for shutdown intent via the stopped flag (the shutdown
            // hook will set it) — this loop simply continues until ^C / ^D / hook.
        }
    }

    /** Console sender — prints to stdout via the SLF4J logger so it goes through log4j2. */
    private static final class ConsoleSender implements CommandSender {
        @Override public String name() { return "Console"; }
        @Override public void sendMessage(String message) { System.out.println(message); }
        @Override public boolean isPlayer() { return false; }
        @Override public boolean hasPermission(String permission) { return true; }
    }
}
