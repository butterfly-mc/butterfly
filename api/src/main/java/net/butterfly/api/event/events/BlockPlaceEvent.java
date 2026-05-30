package net.butterfly.api.event.events;

import net.butterfly.api.event.Cancellable;
import net.butterfly.api.event.Event;

/** Fired when a player places a block. Cancelling prevents the placement. */
public final class BlockPlaceEvent extends Event implements Cancellable {

    private final String playerName;
    private final int x;
    private final int y;
    private final int z;
    private final String blockName;
    private boolean cancelled;

    public BlockPlaceEvent(String playerName, int x, int y, int z, String blockName) {
        this.playerName = playerName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockName = blockName;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getBlockName() {
        return blockName;
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
