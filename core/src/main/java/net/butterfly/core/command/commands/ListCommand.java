package net.butterfly.core.command.commands;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.entity.Player;
import net.butterfly.api.plugin.Server;

import java.util.Collection;
import java.util.StringJoiner;

/** Reports the current online player count and their names. */
public final class ListCommand implements Command {

    private final Server server;

    public ListCommand(Server server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List online players";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Collection<? extends Player> players = server.onlinePlayers();
        int n = players.size();

        StringJoiner names = new StringJoiner(", ");
        for (Player p : players) {
            names.add(p.name());
        }
        sender.sendMessage("There are " + n + " players online: " + names);
    }
}
