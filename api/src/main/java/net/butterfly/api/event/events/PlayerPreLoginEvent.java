package net.butterfly.api.event.events;

import net.butterfly.api.event.Cancellable;
import net.butterfly.api.event.Event;

/**
 * Fired before a player is admitted to the server. Cancelling this event
 * prevents the login from completing; {@link #getKickReason()} is forwarded to
 * the client as the disconnect reason.
 */
public final class PlayerPreLoginEvent extends Event implements Cancellable {

    private final String playerName;
    private final String xuid;
    private String kickReason;
    private boolean cancelled;

    public PlayerPreLoginEvent(String playerName, String xuid) {
        this.playerName = playerName;
        this.xuid = xuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getXuid() {
        return xuid;
    }

    public String getKickReason() {
        return kickReason;
    }

    public void setKickReason(String kickReason) {
        this.kickReason = kickReason;
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
