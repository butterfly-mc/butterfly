package net.butterfly.core.command.commands;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.plugin.Server;

/** Initiates an orderly server shutdown. */
public final class StopCommand implements Command {

    private final Server server;

    public StopCommand(Server server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
    }

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the server";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage("Shutting down...");
        server.shutdown();
    }
}
