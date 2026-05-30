package net.butterfly.api.command;

import java.util.List;

/**
 * A registered command. Commands are looked up by {@link #name()} or any of
 * their {@link #aliases()} via the {@link CommandRegistry}.
 */
public interface Command {

    /** Primary name (without leading slash). */
    String name();

    /** One-line description of the command, used in help output. */
    default String description() {
        return "";
    }

    /** Additional names this command can be invoked under. */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * Run the command on behalf of {@code sender}.
     *
     * @param sender entity that issued the command
     * @param args   tokens after the command name (never null, may be empty)
     */
    void execute(CommandSender sender, String[] args);
}
