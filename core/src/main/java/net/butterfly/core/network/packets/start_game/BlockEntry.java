package net.butterfly.core.network.packets.start_game;

import net.butterfly.nbt.NbtMap;

/**
 * One entry of the {@code blocks} slice (block palette) in StartGame. Wire
 * layout per gophertunnel {@code BlockEntry.Marshal}:
 * <pre>
 *   string  Name        (varint length + bytes)
 *   nbt-le  Properties  (fixed-LE NBT compound: uint16 LE string length, fixed-width LE int/long)
 * </pre>
 *
 * <p>Note the NBT variant is the FIXED-SIZE LE one used inside chunks/items —
 * <em>not</em> the NetworkLittleEndian one used for {@code propertyData} elsewhere
 * in StartGame.
 */
public record BlockEntry(String name, NbtMap properties) {}
