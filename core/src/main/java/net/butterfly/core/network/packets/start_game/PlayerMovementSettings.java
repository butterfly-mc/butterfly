package net.butterfly.core.network.packets.start_game;

/**
 * StartGame's {@code PlayerMoveSettings} sub-struct. For protocol 975 the wire
 * layout shrank to two fields:
 * <pre>
 *   varint32 RewindHistorySize
 *   bool     ServerAuthoritativeBlockBreaking
 * </pre>
 *
 * <p>The legacy {@code MovementType} / {@code ServerAuthoritativeMovementMode}
 * fields were removed when modern Bedrock made server-authoritative movement the
 * unconditional default.
 */
public record PlayerMovementSettings(int rewindHistorySize, boolean serverAuthoritativeBlockBreaking) {
    /** Defaults that match BDS's StartGame body for protocol 975. */
    public static final PlayerMovementSettings BDS_DEFAULT =
        new PlayerMovementSettings(40, true);
}
