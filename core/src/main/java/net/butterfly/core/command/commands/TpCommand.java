package net.butterfly.core.command.commands;

import net.butterfly.api.command.Command;
import net.butterfly.api.command.CommandSender;
import net.butterfly.api.entity.Player;
import net.butterfly.api.plugin.Server;

/**
 * Teleports players. Two forms:
 * <ul>
 *   <li>{@code /tp <player>} — teleport the sender to {@code <player>} (sender must be a player).</li>
 *   <li>{@code /tp <from> <to>} — teleport {@code <from>} to {@code <to>}.</li>
 * </ul>
 */
public final class TpCommand implements Command {

    private final Server server;

    public TpCommand(Server server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
    }

    @Override
    public String name() {
        return "tp";
    }

    @Override
    public String description() {
        return "Teleport players";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!sender.isPlayer()) {
                sender.sendMessage("Usage: /tp <from> <to>");
                return;
            }
            Player from = server.playerByName(sender.name());
            if (from == null) {
                sender.sendMessage("Player not found: " + sender.name());
                return;
            }
            Player to = server.playerByName(args[0]);
            if (to == null) {
                sender.sendMessage("Player not found: " + args[0]);
                return;
            }
            from.teleport(to.world(), to.x(), to.y(), to.z());
            sender.sendMessage("Teleported " + from.name() + " to " + to.name() + ".");
            return;
        }
        if (args.length == 2) {
            Player from = server.playerByName(args[0]);
            if (from == null) {
                sender.sendMessage("Player not found: " + args[0]);
                return;
            }
            Player to = server.playerByName(args[1]);
            if (to == null) {
                sender.sendMessage("Player not found: " + args[1]);
                return;
            }
            from.teleport(to.world(), to.x(), to.y(), to.z());
            sender.sendMessage("Teleported " + from.name() + " to " + to.name() + ".");
            return;
        }
        sender.sendMessage("Usage: /tp <player> | /tp <from> <to>");
    }
}
