package net.butterfly.api.event.events;

import net.butterfly.api.event.Cancellable;
import net.butterfly.api.event.Event;

/**
 * Fired when a player sends a chat message. Cancelling suppresses the broadcast;
 * mutating {@link #setMessage(String)} rewrites the message before it is shown
 * to other players.
 */
public final class PlayerChatEvent extends Event implements Cancellable {

    private final String playerName;
    private String message;
    private boolean cancelled;

    public PlayerChatEvent(String playerName, String message) {
        this.playerName = playerName;
        this.message = message;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
