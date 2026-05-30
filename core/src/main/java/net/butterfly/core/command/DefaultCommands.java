package net.butterfly.core.command;

import net.butterfly.api.command.CommandRegistry;
import net.butterfly.api.plugin.Server;
import net.butterfly.core.command.commands.HelpCommand;
import net.butterfly.core.command.commands.ListCommand;
import net.butterfly.core.command.commands.SayCommand;
import net.butterfly.core.command.commands.StopCommand;
import net.butterfly.core.command.commands.TpCommand;
import net.butterfly.core.command.commands.VersionCommand;

/**
 * Registers the built-in commands shipped with Butterfly: {@code help}, {@code stop},
 * {@code list}, {@code tp}, {@code say}, and {@code version}.
 */
public final class DefaultCommands {

    private DefaultCommands() {
        // utility class
    }

    /**
     * Instantiate and register every default command into {@code registry}.
     *
     * @throws IllegalArgumentException if any default command's name or alias collides with
     *                                  a command already registered in {@code registry}
     */
    public static void registerAll(Server server, CommandRegistry registry) {
        if (server == null) throw new NullPointerException("server");
        if (registry == null) throw new NullPointerException("registry");

        registry.register(new HelpCommand(server));
        registry.register(new StopCommand(server));
        registry.register(new ListCommand(server));
        registry.register(new TpCommand(server));
        registry.register(new SayCommand(server));
        registry.register(new VersionCommand(server));
    }
}
