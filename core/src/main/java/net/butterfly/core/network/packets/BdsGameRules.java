package net.butterfly.core.network.packets;

import net.butterfly.core.network.packets.start_game.GameRule;

import java.util.List;

/**
 * The default game-rule list shipped by BDS in StartGame for protocol 975 —
 * verbatim from the dumped {@code start_game.json}, in the same order. Used by
 * {@link StartGamePacket#applyBdsDefaults()} so a freshly-constructed packet is
 * indistinguishable from the BDS body modulo per-session fields.
 *
 * <p>Order matters: even though the client treats game rules as a set, the
 * round-trip test asserts byte-equality with the captured BDS body, so any
 * reordering would fail the test.
 */
final class BdsGameRules {
    private BdsGameRules() {}

    static final List<GameRule> DEFAULT = List.of(
        GameRule.ofBool ("commandBlockOutput",         true, true),
        GameRule.ofBool ("doDayLightCycle",            true, true),
        GameRule.ofBool ("doEntityDrops",              true, true),
        GameRule.ofBool ("doFireTick",                 true, true),
        GameRule.ofBool ("recipesUnlock",              true, true),
        GameRule.ofBool ("doLimitedCrafting",          true, false),
        GameRule.ofBool ("doMobLoot",                  true, true),
        GameRule.ofBool ("doMobSpawning",              true, true),
        GameRule.ofBool ("doTileDrops",                true, true),
        GameRule.ofBool ("doWeatherCycle",             true, true),
        GameRule.ofBool ("drowningDamage",             true, true),
        GameRule.ofBool ("fallDamage",                 true, true),
        GameRule.ofBool ("fireDamage",                 true, true),
        GameRule.ofBool ("keepInventory",              true, false),
        GameRule.ofBool ("mobGriefing",                true, true),
        GameRule.ofBool ("pvp",                        true, true),
        GameRule.ofBool ("showCoordinates",            true, false),
        GameRule.ofBool ("locatorBar",                 true, true),
        GameRule.ofBool ("showDaysPlayed",             true, false),
        GameRule.ofBool ("naturalRegeneration",        true, true),
        GameRule.ofBool ("tntExplodes",                true, true),
        GameRule.ofBool ("sendCommandFeedback",        true, true),
        GameRule.ofInt  ("maxCommandChainLength",      true, 131070),
        GameRule.ofBool ("doInsomnia",                 true, true),
        GameRule.ofBool ("commandBlocksEnabled",       true, true),
        GameRule.ofInt  ("randomTickSpeed",            true, 2),
        GameRule.ofBool ("doImmediateRespawn",         true, false),
        GameRule.ofBool ("showDeathMessages",          true, true),
        GameRule.ofInt  ("functionCommandLimit",       true, 20000),
        GameRule.ofInt  ("spawnRadius",                true, 20),
        GameRule.ofBool ("showTags",                   true, true),
        GameRule.ofBool ("freezeDamage",               true, true),
        GameRule.ofBool ("respawnBlocksExplode",       true, true),
        GameRule.ofBool ("showBorderEffect",           true, true),
        GameRule.ofBool ("showRecipeMessages",         true, true),
        GameRule.ofInt  ("playersSleepingPercentage",  true, 200),
        GameRule.ofBool ("projectilesCanBreakBlocks",  true, true),
        GameRule.ofBool ("tntExplosionDropDecay",      true, false)
    );
}
