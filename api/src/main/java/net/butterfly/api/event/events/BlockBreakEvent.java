package net.butterfly.api.event.events;

import java.util.ArrayList;
import java.util.List;
import net.butterfly.api.event.Cancellable;
import net.butterfly.api.event.Event;

/**
 * Fired when a player breaks a block. Cancelling prevents the break;
 * {@link #getDrops()} is mutable so handlers can adjust the resulting drops.
 */
public final class BlockBreakEvent extends Event implements Cancellable {

    private final String playerName;
    private final int x;
    private final int y;
    private final int z;
    private final String blockName;
    private List<String> drops;
    private boolean cancelled;

    public BlockBreakEvent(String playerName, int x, int y, int z, String blockName, List<String> drops) {
        this.playerName = playerName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockName = blockName;
        this.drops = drops != null ? new ArrayList<>(drops) : new ArrayList<>();
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

    public List<String> getDrops() {
        return drops;
    }

    public void setDrops(List<String> drops) {
        this.drops = drops;
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
