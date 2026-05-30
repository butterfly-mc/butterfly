package net.butterfly.core.command.commands;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.plugin.Server;

import java.util.List;

/** Reports the running server software version and Bedrock protocol version. */
public final class VersionCommand implements Command {

    private final Server server;

    public VersionCommand(Server server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
    }

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String description() {
        return "Show server version";
    }

    @Override
    public List<String> aliases() {
        return List.of("ver");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage("Butterfly server v" + server.version()
                + " (protocol " + server.protocolVersion() + ")");
    }
}
