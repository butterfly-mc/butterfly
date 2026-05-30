package net.butterfly.core.network.packets.start_game;

/**
 * StartGame's {@code ServerJoinInformation} optional sub-struct. Per
 * gophertunnel master, the struct itself contains three further nested
 * {@code Optional<...>} sub-structs in this order:
 * <pre>
 *   Optional&lt;GatheringJoinInfo&gt;
 *   Optional&lt;StoreEntryPointInfo&gt;
 *   Optional&lt;PresenceInfo&gt;
 * </pre>
 *
 * <p>The MVP only ever (en|de)codes the case where all three nested optionals
 * are absent (which matches the BDS capture). The three booleans are kept on
 * the record so a future change can populate them — but if any is {@code true},
 * the encoder/decoder currently rejects the packet rather than silently
 * truncating.
 *
 * <p>Whether the outer {@code ServerJoinInformation} is itself present is
 * tracked separately by {@link net.butterfly.core.network.packets.StartGamePacket}
 * (the field reference is {@code null} when absent).
 */
public record ServerJoinInformation(boolean gatheringPresent,
                                    boolean storePresent,
                                    boolean presencePresent) {

    /** All three nested optionals absent — matches the BDS capture. */
    public static final ServerJoinInformation EMPTY = new ServerJoinInformation(false, false, false);
}
