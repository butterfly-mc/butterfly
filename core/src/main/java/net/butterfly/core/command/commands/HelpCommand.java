package net.butterfly.core.command.commands;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandRegistry;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.plugin.Server;

import java.util.List;

/** Lists every registered command in the server's {@link CommandRegistry}. */
public final class HelpCommand implements Command {

    private final Server server;

    public HelpCommand(Server server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "List available commands";
    }

    @Override
    public List<String> aliases() {
        return List.of("?");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage("Available commands:");
        for (Command c : server.commandRegistry().all()) {
            sender.sendMessage("- /" + c.name() + ": " + c.description());
        }
    }
}
