package net.butterfly.api.entity;

/**
 * A connected human player.
 *
 * <p>{@code Player} extends {@link Entity} with identity (name, XUID) and chat/messaging
 * operations. Player handles remain valid while the player is online; once the player
 * disconnects, calls to messaging operations are silently dropped (or may throw, depending
 * on implementation).
 */
public interface Player extends Entity {

    /**
     * @return the player's display name as reported by the client; never {@code null}
     */
    String name();

    /**
     * @return the player's Xbox Live user ID (XUID) as a decimal string, or an empty
     *         string if the player is not authenticated against Xbox Live
     */
    String xuid();

    /**
     * Sends a system message to this player. The message is shown in the client's chat
     * area without an attached author.
     *
     * <p>This method is safe to call from any thread; implementations are expected to
     * marshal the send onto the network thread as needed.
     *
     * @param message the message text; must not be {@code null}
     */
    void sendMessage(String message);

    /**
     * Disconnects the player with the given reason text shown on their client.
     *
     * <p><b>Threading:</b> must be called on the simulate thread; the implementation
     * may throw {@link IllegalStateException} if called from another thread.
     *
     * @param reason the kick reason shown to the player; must not be {@code null}
     * @throws IllegalStateException if called off the simulate thread
     */
    void kick(String reason);

    /**
     * Sends a chat message to this player attributed to the given author name.
     *
     * <p>This method is safe to call from any thread; implementations are expected to
     * marshal the send onto the network thread as needed.
     *
     * @param from    the author shown for the chat message (e.g. another player's name);
     *                must not be {@code null}
     * @param message the chat body; must not be {@code null}
     */
    void sendChat(String from, String message);
}
