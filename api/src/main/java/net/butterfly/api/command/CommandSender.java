package net.butterfly.api.command;

/**
 * Source of a dispatched command, e.g. a player or the server console.
 */
public interface CommandSender {

    /** Display name of the sender. */
    String name();

    /** Send a chat-style message back to the sender. */
    void sendMessage(String message);

    /** {@code true} if the sender is a player. */
    boolean isPlayer();

    /**
     * Permission check. The MVP has no permission system, so implementations
     * should return {@code true} unconditionally until permissions land.
     */
    boolean hasPermission(String permission);
}
