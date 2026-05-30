package net.butterfly.core.network.packets.start_game;

/**
 * One entry of the {@code experiments} slice in StartGame. Wire layout per
 * gophertunnel {@code ExperimentData.Marshal}:
 * <pre>
 *   string Name      (varint length + bytes)
 *   bool   Enabled
 * </pre>
 */
public record Experiment(String name, boolean enabled) {}
