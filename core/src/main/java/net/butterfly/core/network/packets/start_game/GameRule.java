package net.butterfly.core.network.packets.start_game;

/**
 * StartGame's {@code GameRule} entry (legacy variant). Wire layout per
 * gophertunnel {@code GameRuleLegacy.Marshal}:
 * <pre>
 *   string  Name
 *   bool    CanBeModifiedByPlayer
 *   varuint Type    (1 = bool, 2 = varuint32, 3 = float32 LE)
 *   <varies by Type>
 * </pre>
 *
 * <p>The {@code value} field is stored as {@link Object} — the concrete runtime
 * type is determined by {@link #type()}: {@link Boolean} for type 1,
 * {@link Integer} (or any subclass of {@link Number} returning a non-negative
 * int via {@link Number#longValue()}) for type 2, {@link Float} for type 3.
 */
public record GameRule(String name, boolean canBeModifiedByPlayer, int type, Object value) {

    public static final int TYPE_BOOL = 1;
    public static final int TYPE_VARUINT32 = 2;
    public static final int TYPE_FLOAT = 3;

    public static GameRule ofBool(String name, boolean editable, boolean value) {
        return new GameRule(name, editable, TYPE_BOOL, value);
    }

    public static GameRule ofInt(String name, boolean editable, int value) {
        return new GameRule(name, editable, TYPE_VARUINT32, value);
    }

    public static GameRule ofFloat(String name, boolean editable, float value) {
        return new GameRule(name, editable, TYPE_FLOAT, value);
    }
}
