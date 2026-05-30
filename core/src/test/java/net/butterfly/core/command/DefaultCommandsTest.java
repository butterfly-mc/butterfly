package net.butterfly.core.command;

import net.butterfly.core.command.TestFakes.FakePlayer;
import net.butterfly.core.command.TestFakes.FakeSender;
import net.butterfly.core.command.TestFakes.FakeServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCommandsTest {

    private static FakeServer newServer() {
        FakeServer s = new FakeServer("0.1.0", 975);
        DefaultCommands.registerAll(s, s.commandRegistry());
        return s;
    }

    @Test
    void registerAllRegistersSixCommands() {
        FakeServer server = newServer();
        assertEquals(6, server.commandRegistry().all().size());
        for (String name : List.of("help", "stop", "list", "tp", "say", "version")) {
            assertNotNull(server.commandRegistry().get(name), "missing command: " + name);
        }
        // Aliases.
        assertNotNull(server.commandRegistry().get("?"));
        assertNotNull(server.commandRegistry().get("ver"));
    }

    @Test
    void helpCommandListsEveryRegisteredCommand() {
        FakeServer server = newServer();
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("help").execute(sender, new String[0]);

        assertEquals("Available commands:", sender.messages.get(0));
        // One header + one row per command.
        assertEquals(1 + 6, sender.messages.size());
        // Body rows are formatted as "- /<name>: <description>".
        for (int i = 1; i < sender.messages.size(); i++) {
            assertTrue(sender.messages.get(i).startsWith("- /"),
                    "row " + i + " missing prefix: " + sender.messages.get(i));
        }
    }

    @Test
    void versionCommandReportsServerVersionAndProtocol() {
        FakeServer server = newServer();
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("version").execute(sender, new String[0]);

        assertEquals(1, sender.messages.size());
        String msg = sender.messages.get(0);
        assertTrue(msg.contains("v0.1.0"), msg);
        assertTrue(msg.contains("975"), msg);
    }

    @Test
    void versionCommandIsReachableByAlias() {
        FakeServer server = newServer();
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("ver").execute(sender, new String[0]);

        assertTrue(sender.messages.get(0).contains("v0.1.0"));
    }

    @Test
    void listCommandWithZeroPlayers() {
        FakeServer server = newServer();
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("list").execute(sender, new String[0]);

        assertEquals(List.of("There are 0 players online: "), sender.messages);
    }

    @Test
    void listCommandWithOnePlayer() {
        FakeServer server = newServer();
        server.addPlayer("Alice");
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("list").execute(sender, new String[0]);

        assertEquals(List.of("There are 1 players online: Alice"), sender.messages);
    }

    @Test
    void listCommandWithTwoPlayersJoinsNamesWithComma() {
        FakeServer server = newServer();
        server.addPlayer("Alice");
        server.addPlayer("Bob");
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("list").execute(sender, new String[0]);

        assertEquals(1, sender.messages.size());
        String msg = sender.messages.get(0);
        assertTrue(msg.startsWith("There are 2 players online: "), msg);
        assertTrue(msg.contains("Alice") && msg.contains("Bob"), msg);
        assertTrue(msg.contains(", "), msg);
    }

    @Test
    void sayCommandWithNoArgsReportsUsage() {
        FakeServer server = newServer();
        FakeSender sender = FakeSender.console();

        server.commandRegistry().get("say").execute(sender, new String[0]);

        assertEquals(List.of("Usage: /say <message>"), sender.messages);
    }

    @Test
    void sayCommandBroadcastsToPlayersAndConsole() {
        FakeServer server = newServer();
        FakePlayer alice = server.addPlayer("Alice");
        FakePlayer bob = server.addPlayer("Bob");
        FakeSender console = FakeSender.console();

        server.commandRegistry().get("say").execute(console, new String[]{"hello", "world"});

        String expected = "[Server] hello world";
        assertEquals(List.of(expected), alice.messages);
        assertEquals(List.of(expected), bob.messages);
        // Console isn't in onlinePlayers, so the command echoes the broadcast back to it.
        assertEquals(List.of(expected), console.messages);
    }

    @Test
    void sayCommandDoesNotDoubleEchoToPlayerSender() {
        FakeServer server = newServer();
        FakePlayer alice = server.addPlayer("Alice");
        FakeSender aliceSender = FakeSender.player("Alice");

        server.commandRegistry().get("say").execute(aliceSender, new String[]{"hi"});

        // Alice receives one copy as a player; the sender wrapper isn't echoed again.
        assertEquals(List.of("[Server] hi"), alice.messages);
        assertEquals(List.of(), aliceSender.messages);
    }

    @Test
    void tpCommandWithNoArgsReportsUsage() {
        FakeServer server = newServer();
        FakeSender console = FakeSender.console();

        server.commandRegistry().get("tp").execute(console, new String[0]);

        assertEquals(1, console.messages.size());
        assertTrue(console.messages.get(0).startsWith("Usage: /tp"), console.messages.get(0));
    }

    @Test
    void tpCommandSingleArgFromConsoleReportsUsage() {
        FakeServer server = newServer();
        server.addPlayer("Alice");
        FakeSender console = FakeSender.console();

        server.commandRegistry().get("tp").execute(console, new String[]{"Alice"});

        assertEquals(List.of("Usage: /tp <from> <to>"), console.messages);
    }

    @Test
    void tpCommandTwoArgsTeleportsAndConfirms() {
        FakeServer server = newServer();
        FakePlayer alice = server.addPlayer("Alice");
        FakePlayer bob = server.addPlayer("Bob");
        FakeSender console = FakeSender.console();

        server.commandRegistry().get("tp").execute(console, new String[]{"Alice", "Bob"});

        assertEquals(1, alice.teleportCount);
        assertEquals(0, bob.teleportCount);
        assertEquals(List.of("Teleported Alice to Bob."), console.messages);
    }

    @Test
    void tpCommandUnknownTargetReportsError() {
        FakeServer server = newServer();
        server.addPlayer("Alice");
        FakeSender console = FakeSender.console();

        server.commandRegistry().get("tp").execute(console, new String[]{"Alice", "Ghost"});

        assertEquals(List.of("Player not found: Ghost"), console.messages);
    }

    @Test
    void stopCommandShutsDownTheServer() {
        FakeServer server = newServer();
        FakeSender console = FakeSender.console();

        server.commandRegistry().get("stop").execute(console, new String[0]);

        assertEquals(List.of("Shutting down..."), console.messages);
        assertEquals(1, server.shutdownCalls);
    }
}
