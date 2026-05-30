package net.butterfly.api.event.events;

import net.butterfly.api.event.Event;

/** Fired when a player disconnects from the server. */
public final class PlayerQuitEvent extends Event {

    private final String playerName;
    private final String xuid;
    private String quitMessage;

    public PlayerQuitEvent(String playerName, String xuid, String quitMessage) {
        this.playerName = playerName;
        this.xuid = xuid;
        this.quitMessage = quitMessage;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getXuid() {
        return xuid;
    }

    public String getQuitMessage() {
        return quitMessage;
    }

    public void setQuitMessage(String quitMessage) {
        this.quitMessage = quitMessage;
    }
}
