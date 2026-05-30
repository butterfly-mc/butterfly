package net.butterfly.api.event.events;

import net.butterfly.api.event.Event;

/** Fired once a player has successfully joined the server. */
public final class PlayerJoinEvent extends Event {

    private final String playerName;
    private final String xuid;
    private String joinMessage;

    public PlayerJoinEvent(String playerName, String xuid, String joinMessage) {
        this.playerName = playerName;
        this.xuid = xuid;
        this.joinMessage = joinMessage;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getXuid() {
        return xuid;
    }

    public String getJoinMessage() {
        return joinMessage;
    }

    public void setJoinMessage(String joinMessage) {
        this.joinMessage = joinMessage;
    }
}
