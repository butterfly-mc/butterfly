package net.butterfly.core.command.commands;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.entity.Player;
import net.butterfly.api.plugin.Server;

/** Broadcasts a chat message from the server to every online player. */
public final class SayCommand implements Command {

    private final Server server;

    public SayCommand(Server server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
    }

    @Override
    public String name() {
        return "say";
    }

    @Override
    public String description() {
        return "Broadcast a message to all players";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /say <message>");
            return;
        }

        StringBuilder message = new StringBuilder("[Server] ");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) message.append(' ');
            message.append(args[i]);
        }
        String body = message.toString();

        boolean senderIsPlayer = sender.isPlayer();
        boolean echoedToSender = false;
        for (Player p : server.onlinePlayers()) {
            p.sendMessage(body);
            if (senderIsPlayer && p.name().equals(sender.name())) {
                echoedToSender = true;
            }
        }
        if (!echoedToSender) {
            sender.sendMessage(body);
        }
    }
}
