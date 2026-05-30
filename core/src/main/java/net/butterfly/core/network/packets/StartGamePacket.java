package net.butterfly.core.network.packets;

import io.netty.buffer.ByteBuf;
import net.butterfly.codec.Packet;
import net.butterfly.codec.PacketIds;
import net.butterfly.codec.Protocol;
import net.butterfly.core.network.packets.start_game.BlockEntry;
import net.butterfly.core.network.packets.start_game.BlockPos;
import net.butterfly.core.network.packets.start_game.EducationSharedResourceURI;
import net.butterfly.core.network.packets.start_game.Experiment;
import net.butterfly.core.network.packets.start_game.GameRule;
import net.butterfly.core.network.packets.start_game.PlayerMovementSettings;
import net.butterfly.core.network.packets.start_game.ServerJoinInformation;
import net.butterfly.core.network.packets.start_game.Vec3;
import net.butterfly.nbt.NbtLeReader;
import net.butterfly.nbt.NbtMap;
import net.butterfly.nbt.NbtReader;
import net.butterfly.nbt.NbtWriter;
import net.butterfly.nbt.VarInts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * StartGame (0x0b) — the first authoritative game-state packet sent to the
 * client after the resource pack handshake completes. It carries the player's
 * own identity (unique + runtime ids, spawn position), the world descriptor
 * (seed, dimension, generator, default game rules, …), the block palette, and
 * a grab-bag of platform-specific flags.
 *
 * <p>The wire layout is reproduced verbatim from gophertunnel's
 * {@code (*StartGame).Marshal} for protocol 975 — see the project memory for
 * the authoritative field list. Field order, integer encoding (zigzag vs.
 * unsigned varint vs. fixed LE), and string variant matter exactly.
 *
 * <p>Two NBT variants appear in the body:
 * <ul>
 *   <li>{@link BlockEntry#properties()} uses <em>fixed-LE</em> NBT
 *       ({@code uint16 LE} string lengths, fixed-width LE int/long).</li>
 *   <li>{@link #propertyData()} uses <em>NetworkLittleEndian</em> NBT
 *       (varint string lengths, zigzag int/long).</li>
 * </ul>
 *
 * <p>The default-constructed instance is the BDS-shipped baseline for protocol
 * 975 ({@link #applyBdsDefaults()}); callers fill in the per-session fields
 * (entity ids, spawn position, level/world ids) before encoding.
 */
public final class StartGamePacket implements Packet {

    public static final int ID = PacketIds.START_GAME;

    // ---- Section 1 — entity, world, dimension ----

    private long entityUniqueId;
    private long entityRuntimeId;
    private int playerGameMode;
    private Vec3 playerPosition = Vec3.ZERO;
    private float pitch;
    private float yaw;
    private long worldSeed;
    private short spawnBiomeType;
    private String userDefinedBiomeName = "";
    private int dimension;
    private int generator;
    private int worldGameMode;
    private boolean hardcore;
    private int difficulty;
    private BlockPos worldSpawn = BlockPos.ZERO;
    private boolean achievementsDisabled;
    private int editorWorldType;
    private boolean createdInEditor;
    private boolean exportedFromEditor;
    private int dayCycleLockTime;
    private int educationEditionOffer;
    private boolean educationFeaturesEnabled;
    private String educationProductId = "";
    private float rainLevel;
    private float lightningLevel;
    private boolean confirmedPlatformLockedContent;
    private boolean multiPlayerGame;
    private boolean lanBroadcastEnabled;
    private int xblBroadcastMode;
    private int platformBroadcastMode;
    private boolean commandsEnabled;
    private boolean texturePackRequired;

    // ---- Section 2 — game rules + experiments ----

    private List<GameRule> gameRules = new ArrayList<>();
    private List<Experiment> experiments = new ArrayList<>();
    private boolean experimentsPreviouslyToggled;
    private boolean bonusChestEnabled;
    private boolean startWithMapEnabled;
    private int playerPermissions;
    private int serverChunkTickRadius;
    private boolean hasLockedBehaviourPack;
    private boolean hasLockedTexturePack;
    private boolean fromLockedWorldTemplate;
    private boolean msaGamerTagsOnly;
    private boolean fromWorldTemplate;
    private boolean worldTemplateSettingsLocked;
    private boolean onlySpawnV1Villagers;
    private boolean personaDisabled;
    private boolean customSkinsDisabled;
    private boolean emoteChatMuted;
    private String baseGameVersion = "";
    private int limitedWorldWidth;
    private int limitedWorldDepth;
    private boolean newNether;
    private EducationSharedResourceURI educationSharedResourceUri = EducationSharedResourceURI.EMPTY;

    /** Optional {@code force experimental gameplay} bit. {@code null} = absent. */
    private Boolean forceExperimentalGameplay;

    private int chatRestrictionLevel;
    private boolean disablePlayerInteractions;

    // ---- Section 4 — IDs, server info ----

    private String levelId = "";
    private String worldName = "";
    private String templateContentIdentity = "";
    private boolean trial;
    private PlayerMovementSettings playerMovementSettings = PlayerMovementSettings.BDS_DEFAULT;
    private long time;
    private int enchantmentSeed;

    // ---- Section 5 — block palette + correlation + property NBT ----

    private List<BlockEntry> blocks = new ArrayList<>();
    private String multiPlayerCorrelationId = "";
    private boolean serverAuthoritativeInventory;
    private String gameVersion = Protocol.MINECRAFT_VERSION;
    private NbtMap propertyData = NbtMap.EMPTY;
    private long serverBlockStateChecksum;
    private UUID worldTemplateId = new UUID(0L, 0L);
    private boolean clientSideGeneration;
    private boolean useBlockNetworkIdHashes;
    private boolean serverAuthoritativeSound;

    /** Optional {@code ServerJoinInformation}. {@code null} = absent. */
    private ServerJoinInformation serverJoinInformation;

    private String serverId = "";
    private String scenarioId = "";
    private String worldId = "";
    private String ownerId = "";

    public StartGamePacket() {}

    /** Apply the BDS protocol-975 baseline values (matches the dumped capture). */
    public StartGamePacket applyBdsDefaults() {
        this.entityUniqueId = -4294967295L;
        this.entityRuntimeId = 1L;
        this.playerGameMode = 5;
        this.playerPosition = new Vec3(0.5f, 32769.62f, 0.5f);
        this.pitch = 0f;
        this.yaw = 0f;
        this.worldSeed = 6284787323915679726L;
        this.spawnBiomeType = 0;
        this.userDefinedBiomeName = "minecraft:plains";
        this.dimension = 0;
        this.generator = 1;
        this.worldGameMode = 0;
        this.hardcore = false;
        this.difficulty = 1;
        this.worldSpawn = new BlockPos(0, 65534, 0);
        this.achievementsDisabled = false;
        this.editorWorldType = 0;
        this.createdInEditor = false;
        this.exportedFromEditor = false;
        this.dayCycleLockTime = 2884;
        this.educationEditionOffer = 0;
        this.educationFeaturesEnabled = false;
        this.educationProductId = "";
        this.rainLevel = 0f;
        this.lightningLevel = 0f;
        this.confirmedPlatformLockedContent = false;
        this.multiPlayerGame = true;
        this.lanBroadcastEnabled = true;
        this.xblBroadcastMode = 0;
        this.platformBroadcastMode = 0;
        this.commandsEnabled = false;
        this.texturePackRequired = false;
        this.gameRules = new ArrayList<>(BdsGameRules.DEFAULT);
        this.experiments = new ArrayList<>();
        this.experimentsPreviouslyToggled = false;
        this.bonusChestEnabled = false;
        this.startWithMapEnabled = false;
        this.playerPermissions = 1;
        this.serverChunkTickRadius = 4;
        this.hasLockedBehaviourPack = false;
        this.hasLockedTexturePack = false;
        this.fromLockedWorldTemplate = false;
        this.msaGamerTagsOnly = true;
        this.fromWorldTemplate = false;
        this.worldTemplateSettingsLocked = false;
        this.onlySpawnV1Villagers = false;
        this.personaDisabled = false;
        this.customSkinsDisabled = false;
        this.emoteChatMuted = false;
        this.baseGameVersion = "*";
        this.limitedWorldWidth = 16;
        this.limitedWorldDepth = 16;
        this.newNether = false;
        this.educationSharedResourceUri = EducationSharedResourceURI.EMPTY;
        this.forceExperimentalGameplay = null;
        this.chatRestrictionLevel = 0;
        this.disablePlayerInteractions = false;
        this.levelId = "Bedrock level";
        this.worldName = "Bedrock level";
        this.templateContentIdentity = "00000000-0000-0000-0000-000000000000";
        this.trial = false;
        this.playerMovementSettings = PlayerMovementSettings.BDS_DEFAULT;
        this.time = 2884L;
        this.enchantmentSeed = 1234707993;
        this.blocks = new ArrayList<>();
        this.multiPlayerCorrelationId = "<raknet>8674-aa13-fa49-8e94";
        this.serverAuthoritativeInventory = true;
        this.gameVersion = "1.26.23";
        this.propertyData = NbtMap.EMPTY;
        this.serverBlockStateChecksum = -6083918988771959701L;
        this.worldTemplateId = new UUID(0L, 0L);
        this.clientSideGeneration = false;
        this.useBlockNetworkIdHashes = true;
        this.serverAuthoritativeSound = true;
        // BDS sends serverJoinInformation = present-with-empty (all 3 nested optionals absent).
        this.serverJoinInformation = ServerJoinInformation.EMPTY;
        this.serverId = "";
        this.scenarioId = "";
        this.worldId = "";
        this.ownerId = "";
        return this;
    }

    @Override public int packetId() { return ID; }

    // ---- Encode ----

    @Override public void encode(ByteBuf buf) {
        VarInts.writeLong(buf, entityUniqueId);
        VarInts.writeUnsignedLong(buf, entityRuntimeId);
        VarInts.writeInt(buf, playerGameMode);
        buf.writeFloatLE(playerPosition.x());
        buf.writeFloatLE(playerPosition.y());
        buf.writeFloatLE(playerPosition.z());
        buf.writeFloatLE(pitch);
        buf.writeFloatLE(yaw);
        buf.writeLongLE(worldSeed);
        buf.writeShortLE(spawnBiomeType);
        PacketBuf.writeString(buf, userDefinedBiomeName);
        VarInts.writeInt(buf, dimension);
        VarInts.writeInt(buf, generator);
        VarInts.writeInt(buf, worldGameMode);
        buf.writeBoolean(hardcore);
        VarInts.writeInt(buf, difficulty);
        PacketBuf.writeBlockPos(buf, worldSpawn.x(), worldSpawn.y(), worldSpawn.z());
        buf.writeBoolean(achievementsDisabled);
        VarInts.writeInt(buf, editorWorldType);
        buf.writeBoolean(createdInEditor);
        buf.writeBoolean(exportedFromEditor);
        VarInts.writeInt(buf, dayCycleLockTime);
        VarInts.writeInt(buf, educationEditionOffer);
        buf.writeBoolean(educationFeaturesEnabled);
        PacketBuf.writeString(buf, educationProductId);
        buf.writeFloatLE(rainLevel);
        buf.writeFloatLE(lightningLevel);
        buf.writeBoolean(confirmedPlatformLockedContent);
        buf.writeBoolean(multiPlayerGame);
        buf.writeBoolean(lanBroadcastEnabled);
        VarInts.writeInt(buf, xblBroadcastMode);
        VarInts.writeInt(buf, platformBroadcastMode);
        buf.writeBoolean(commandsEnabled);
        buf.writeBoolean(texturePackRequired);

        // Game rules: varuint count + N × {string, bool, varuint type, value}
        VarInts.writeUnsignedInt(buf, gameRules.size());
        for (GameRule r : gameRules) writeGameRule(buf, r);

        // Experiments: uint32 LE count + N × {string, bool}
        buf.writeIntLE(experiments.size());
        for (Experiment e : experiments) {
            PacketBuf.writeString(buf, e.name());
            buf.writeBoolean(e.enabled());
        }

        buf.writeBoolean(experimentsPreviouslyToggled);
        buf.writeBoolean(bonusChestEnabled);
        buf.writeBoolean(startWithMapEnabled);
        VarInts.writeInt(buf, playerPermissions);
        buf.writeIntLE(serverChunkTickRadius);
        buf.writeBoolean(hasLockedBehaviourPack);
        buf.writeBoolean(hasLockedTexturePack);
        buf.writeBoolean(fromLockedWorldTemplate);
        buf.writeBoolean(msaGamerTagsOnly);
        buf.writeBoolean(fromWorldTemplate);
        buf.writeBoolean(worldTemplateSettingsLocked);
        buf.writeBoolean(onlySpawnV1Villagers);
        buf.writeBoolean(personaDisabled);
        buf.writeBoolean(customSkinsDisabled);
        buf.writeBoolean(emoteChatMuted);
        PacketBuf.writeString(buf, baseGameVersion);
        buf.writeIntLE(limitedWorldWidth);
        buf.writeIntLE(limitedWorldDepth);
        buf.writeBoolean(newNether);
        // EducationSharedResourceURI: 2 strings inline
        PacketBuf.writeString(buf, educationSharedResourceUri.buttonName());
        PacketBuf.writeString(buf, educationSharedResourceUri.linkUri());
        // OptionalFunc(ForceExperimentalGameplay, io.Bool) — bool present + (if present) bool value
        if (forceExperimentalGameplay != null) {
            buf.writeBoolean(true);
            buf.writeBoolean(forceExperimentalGameplay);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeByte(chatRestrictionLevel & 0xff);
        buf.writeBoolean(disablePlayerInteractions);
        PacketBuf.writeString(buf, levelId);
        PacketBuf.writeString(buf, worldName);
        PacketBuf.writeString(buf, templateContentIdentity);
        buf.writeBoolean(trial);
        // PlayerMoveSettings
        VarInts.writeInt(buf, playerMovementSettings.rewindHistorySize());
        buf.writeBoolean(playerMovementSettings.serverAuthoritativeBlockBreaking());
        buf.writeLongLE(time);
        VarInts.writeInt(buf, enchantmentSeed);
        // Blocks: varuint count + N × {string, fixed-LE NBT compound}
        VarInts.writeUnsignedInt(buf, blocks.size());
        for (BlockEntry b : blocks) {
            PacketBuf.writeString(buf, b.name());
            new NbtLeWriter(buf).writeRootCompound(
                b.properties() != null ? b.properties() : NbtMap.EMPTY);
        }
        PacketBuf.writeString(buf, multiPlayerCorrelationId);
        buf.writeBoolean(serverAuthoritativeInventory);
        PacketBuf.writeString(buf, gameVersion);
        // PropertyData NBT — NetworkLittleEndian
        new NbtWriter(buf).writeCompound(propertyData != null ? propertyData : NbtMap.EMPTY);
        buf.writeLongLE(serverBlockStateChecksum);
        PacketBuf.writeUuid(buf, worldTemplateId);
        buf.writeBoolean(clientSideGeneration);
        buf.writeBoolean(useBlockNetworkIdHashes);
        buf.writeBoolean(serverAuthoritativeSound);
        // OptionalMarshaler(ServerJoinInformation)
        if (serverJoinInformation != null) {
            buf.writeBoolean(true);
            // ServerJoinInformation is itself 3 × Optional<sub-struct> — each absent for MVP/BDS.
            buf.writeBoolean(serverJoinInformation.gatheringPresent());
            buf.writeBoolean(serverJoinInformation.storePresent());
            buf.writeBoolean(serverJoinInformation.presencePresent());
            // If any of the above are true, the sub-struct payload would follow here. The MVP
            // (and the BDS capture) only ever sees them all-false, so no sub-payload is emitted.
        } else {
            buf.writeBoolean(false);
        }
        PacketBuf.writeString(buf, serverId);
        PacketBuf.writeString(buf, scenarioId);
        PacketBuf.writeString(buf, worldId);
        PacketBuf.writeString(buf, ownerId);
    }

    private static void writeGameRule(ByteBuf buf, GameRule r) {
        PacketBuf.writeString(buf, r.name());
        buf.writeBoolean(r.canBeModifiedByPlayer());
        VarInts.writeUnsignedInt(buf, r.type());
        switch (r.type()) {
            case GameRule.TYPE_BOOL -> buf.writeBoolean((Boolean) r.value());
            case GameRule.TYPE_VARUINT32 -> VarInts.writeUnsignedInt(buf, ((Number) r.value()).longValue());
            case GameRule.TYPE_FLOAT -> buf.writeFloatLE(((Number) r.value()).floatValue());
            default -> throw new IllegalStateException("unknown game rule type: " + r.type());
        }
    }

    // ---- Decode ----

    @Override public void decode(ByteBuf buf) {
        this.entityUniqueId = VarInts.readLong(buf);
        this.entityRuntimeId = VarInts.readUnsignedLong(buf);
        this.playerGameMode = VarInts.readInt(buf);
        this.playerPosition = new Vec3(buf.readFloatLE(), buf.readFloatLE(), buf.readFloatLE());
        this.pitch = buf.readFloatLE();
        this.yaw = buf.readFloatLE();
        this.worldSeed = buf.readLongLE();
        this.spawnBiomeType = buf.readShortLE();
        this.userDefinedBiomeName = PacketBuf.readString(buf);
        this.dimension = VarInts.readInt(buf);
        this.generator = VarInts.readInt(buf);
        this.worldGameMode = VarInts.readInt(buf);
        this.hardcore = buf.readBoolean();
        this.difficulty = VarInts.readInt(buf);
        int wsX = VarInts.readInt(buf);
        int wsY = (int) VarInts.readUnsignedInt(buf);
        int wsZ = VarInts.readInt(buf);
        this.worldSpawn = new BlockPos(wsX, wsY, wsZ);
        this.achievementsDisabled = buf.readBoolean();
        this.editorWorldType = VarInts.readInt(buf);
        this.createdInEditor = buf.readBoolean();
        this.exportedFromEditor = buf.readBoolean();
        this.dayCycleLockTime = VarInts.readInt(buf);
        this.educationEditionOffer = VarInts.readInt(buf);
        this.educationFeaturesEnabled = buf.readBoolean();
        this.educationProductId = PacketBuf.readString(buf);
        this.rainLevel = buf.readFloatLE();
        this.lightningLevel = buf.readFloatLE();
        this.confirmedPlatformLockedContent = buf.readBoolean();
        this.multiPlayerGame = buf.readBoolean();
        this.lanBroadcastEnabled = buf.readBoolean();
        this.xblBroadcastMode = VarInts.readInt(buf);
        this.platformBroadcastMode = VarInts.readInt(buf);
        this.commandsEnabled = buf.readBoolean();
        this.texturePackRequired = buf.readBoolean();

        int ruleCount = (int) VarInts.readUnsignedInt(buf);
        this.gameRules = new ArrayList<>(ruleCount);
        for (int i = 0; i < ruleCount; i++) gameRules.add(readGameRule(buf));

        int expCount = buf.readIntLE();
        this.experiments = new ArrayList<>(expCount);
        for (int i = 0; i < expCount; i++) {
            String n = PacketBuf.readString(buf);
            boolean en = buf.readBoolean();
            experiments.add(new Experiment(n, en));
        }

        this.experimentsPreviouslyToggled = buf.readBoolean();
        this.bonusChestEnabled = buf.readBoolean();
        this.startWithMapEnabled = buf.readBoolean();
        this.playerPermissions = VarInts.readInt(buf);
        this.serverChunkTickRadius = buf.readIntLE();
        this.hasLockedBehaviourPack = buf.readBoolean();
        this.hasLockedTexturePack = buf.readBoolean();
        this.fromLockedWorldTemplate = buf.readBoolean();
        this.msaGamerTagsOnly = buf.readBoolean();
        this.fromWorldTemplate = buf.readBoolean();
        this.worldTemplateSettingsLocked = buf.readBoolean();
        this.onlySpawnV1Villagers = buf.readBoolean();
        this.personaDisabled = buf.readBoolean();
        this.customSkinsDisabled = buf.readBoolean();
        this.emoteChatMuted = buf.readBoolean();
        this.baseGameVersion = PacketBuf.readString(buf);
        this.limitedWorldWidth = buf.readIntLE();
        this.limitedWorldDepth = buf.readIntLE();
        this.newNether = buf.readBoolean();
        String btn = PacketBuf.readString(buf);
        String uri = PacketBuf.readString(buf);
        this.educationSharedResourceUri = new EducationSharedResourceURI(btn, uri);
        this.forceExperimentalGameplay = buf.readBoolean() ? buf.readBoolean() : null;
        this.chatRestrictionLevel = buf.readUnsignedByte();
        this.disablePlayerInteractions = buf.readBoolean();
        this.levelId = PacketBuf.readString(buf);
        this.worldName = PacketBuf.readString(buf);
        this.templateContentIdentity = PacketBuf.readString(buf);
        this.trial = buf.readBoolean();
        int rewind = VarInts.readInt(buf);
        boolean serverAuthBreak = buf.readBoolean();
        this.playerMovementSettings = new PlayerMovementSettings(rewind, serverAuthBreak);
        this.time = buf.readLongLE();
        this.enchantmentSeed = VarInts.readInt(buf);

        int blockCount = (int) VarInts.readUnsignedInt(buf);
        this.blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            String name = PacketBuf.readString(buf);
            NbtMap props = new NbtLeReader(buf).readCompound();
            blocks.add(new BlockEntry(name, props));
        }

        this.multiPlayerCorrelationId = PacketBuf.readString(buf);
        this.serverAuthoritativeInventory = buf.readBoolean();
        this.gameVersion = PacketBuf.readString(buf);
        this.propertyData = new NbtReader(buf).readCompound();
        this.serverBlockStateChecksum = buf.readLongLE();
        this.worldTemplateId = PacketBuf.readUuid(buf);
        this.clientSideGeneration = buf.readBoolean();
        this.useBlockNetworkIdHashes = buf.readBoolean();
        this.serverAuthoritativeSound = buf.readBoolean();

        boolean sjiPresent = buf.readBoolean();
        if (sjiPresent) {
            boolean g = buf.readBoolean();
            boolean s = buf.readBoolean();
            boolean p = buf.readBoolean();
            this.serverJoinInformation = new ServerJoinInformation(g, s, p);
            // If any sub-optional is true, the inner struct payload would follow. We don't
            // model those structs (the MVP only ever decodes BDS bytes which have all three
            // absent); fail loudly so a future change can be diagnosed at the right field.
            if (g || s || p) {
                throw new IllegalStateException(
                    "ServerJoinInformation has populated sub-optionals — not yet supported");
            }
        } else {
            this.serverJoinInformation = null;
        }

        this.serverId = PacketBuf.readString(buf);
        this.scenarioId = PacketBuf.readString(buf);
        this.worldId = PacketBuf.readString(buf);
        this.ownerId = PacketBuf.readString(buf);
    }

    private static GameRule readGameRule(ByteBuf buf) {
        String name = PacketBuf.readString(buf);
        boolean editable = buf.readBoolean();
        int type = (int) VarInts.readUnsignedInt(buf);
        return switch (type) {
            case GameRule.TYPE_BOOL -> new GameRule(name, editable, type, buf.readBoolean());
            case GameRule.TYPE_VARUINT32 -> new GameRule(name, editable, type,
                (int) VarInts.readUnsignedInt(buf));
            case GameRule.TYPE_FLOAT -> new GameRule(name, editable, type, buf.readFloatLE());
            default -> throw new IllegalStateException("unknown game rule type: " + type);
        };
    }

    // ---- Accessors ----

    public long entityUniqueId() { return entityUniqueId; }
    public StartGamePacket setEntityUniqueId(long v) { this.entityUniqueId = v; return this; }

    public long entityRuntimeId() { return entityRuntimeId; }
    public StartGamePacket setEntityRuntimeId(long v) { this.entityRuntimeId = v; return this; }

    public int playerGameMode() { return playerGameMode; }
    public StartGamePacket setPlayerGameMode(int v) { this.playerGameMode = v; return this; }

    public Vec3 playerPosition() { return playerPosition; }
    public StartGamePacket setPlayerPosition(Vec3 v) { this.playerPosition = v != null ? v : Vec3.ZERO; return this; }

    public float pitch() { return pitch; }
    public StartGamePacket setPitch(float v) { this.pitch = v; return this; }

    public float yaw() { return yaw; }
    public StartGamePacket setYaw(float v) { this.yaw = v; return this; }

    public long worldSeed() { return worldSeed; }
    public StartGamePacket setWorldSeed(long v) { this.worldSeed = v; return this; }

    public short spawnBiomeType() { return spawnBiomeType; }
    public StartGamePacket setSpawnBiomeType(short v) { this.spawnBiomeType = v; return this; }

    public String userDefinedBiomeName() { return userDefinedBiomeName; }
    public StartGamePacket setUserDefinedBiomeName(String v) { this.userDefinedBiomeName = v != null ? v : ""; return this; }

    public int dimension() { return dimension; }
    public StartGamePacket setDimension(int v) { this.dimension = v; return this; }

    public int generator() { return generator; }
    public StartGamePacket setGenerator(int v) { this.generator = v; return this; }

    public int worldGameMode() { return worldGameMode; }
    public StartGamePacket setWorldGameMode(int v) { this.worldGameMode = v; return this; }

    public boolean hardcore() { return hardcore; }
    public StartGamePacket setHardcore(boolean v) { this.hardcore = v; return this; }

    public int difficulty() { return difficulty; }
    public StartGamePacket setDifficulty(int v) { this.difficulty = v; return this; }

    public BlockPos worldSpawn() { return worldSpawn; }
    public StartGamePacket setWorldSpawn(BlockPos v) { this.worldSpawn = v != null ? v : BlockPos.ZERO; return this; }

    public boolean achievementsDisabled() { return achievementsDisabled; }
    public StartGamePacket setAchievementsDisabled(boolean v) { this.achievementsDisabled = v; return this; }

    public int editorWorldType() { return editorWorldType; }
    public StartGamePacket setEditorWorldType(int v) { this.editorWorldType = v; return this; }

    public boolean createdInEditor() { return createdInEditor; }
    public StartGamePacket setCreatedInEditor(boolean v) { this.createdInEditor = v; return this; }

    public boolean exportedFromEditor() { return exportedFromEditor; }
    public StartGamePacket setExportedFromEditor(boolean v) { this.exportedFromEditor = v; return this; }

    public int dayCycleLockTime() { return dayCycleLockTime; }
    public StartGamePacket setDayCycleLockTime(int v) { this.dayCycleLockTime = v; return this; }

    public int educationEditionOffer() { return educationEditionOffer; }
    public StartGamePacket setEducationEditionOffer(int v) { this.educationEditionOffer = v; return this; }

    public boolean educationFeaturesEnabled() { return educationFeaturesEnabled; }
    public StartGamePacket setEducationFeaturesEnabled(boolean v) { this.educationFeaturesEnabled = v; return this; }

    public String educationProductId() { return educationProductId; }
    public StartGamePacket setEducationProductId(String v) { this.educationProductId = v != null ? v : ""; return this; }

    public float rainLevel() { return rainLevel; }
    public StartGamePacket setRainLevel(float v) { this.rainLevel = v; return this; }

    public float lightningLevel() { return lightningLevel; }
    public StartGamePacket setLightningLevel(float v) { this.lightningLevel = v; return this; }

    public boolean confirmedPlatformLockedContent() { return confirmedPlatformLockedContent; }
    public StartGamePacket setConfirmedPlatformLockedContent(boolean v) { this.confirmedPlatformLockedContent = v; return this; }

    public boolean multiPlayerGame() { return multiPlayerGame; }
    public StartGamePacket setMultiPlayerGame(boolean v) { this.multiPlayerGame = v; return this; }

    public boolean lanBroadcastEnabled() { return lanBroadcastEnabled; }
    public StartGamePacket setLanBroadcastEnabled(boolean v) { this.lanBroadcastEnabled = v; return this; }

    public int xblBroadcastMode() { return xblBroadcastMode; }
    public StartGamePacket setXblBroadcastMode(int v) { this.xblBroadcastMode = v; return this; }

    public int platformBroadcastMode() { return platformBroadcastMode; }
    public StartGamePacket setPlatformBroadcastMode(int v) { this.platformBroadcastMode = v; return this; }

    public boolean commandsEnabled() { return commandsEnabled; }
    public StartGamePacket setCommandsEnabled(boolean v) { this.commandsEnabled = v; return this; }

    public boolean texturePackRequired() { return texturePackRequired; }
    public StartGamePacket setTexturePackRequired(boolean v) { this.texturePackRequired = v; return this; }

    public List<GameRule> gameRules() { return gameRules; }
    public StartGamePacket setGameRules(List<GameRule> v) { this.gameRules = v != null ? v : new ArrayList<>(); return this; }

    public List<Experiment> experiments() { return experiments; }
    public StartGamePacket setExperiments(List<Experiment> v) { this.experiments = v != null ? v : new ArrayList<>(); return this; }

    public boolean experimentsPreviouslyToggled() { return experimentsPreviouslyToggled; }
    public StartGamePacket setExperimentsPreviouslyToggled(boolean v) { this.experimentsPreviouslyToggled = v; return this; }

    public boolean bonusChestEnabled() { return bonusChestEnabled; }
    public StartGamePacket setBonusChestEnabled(boolean v) { this.bonusChestEnabled = v; return this; }

    public boolean startWithMapEnabled() { return startWithMapEnabled; }
    public StartGamePacket setStartWithMapEnabled(boolean v) { this.startWithMapEnabled = v; return this; }

    public int playerPermissions() { return playerPermissions; }
    public StartGamePacket setPlayerPermissions(int v) { this.playerPermissions = v; return this; }

    public int serverChunkTickRadius() { return serverChunkTickRadius; }
    public StartGamePacket setServerChunkTickRadius(int v) { this.serverChunkTickRadius = v; return this; }

    public boolean hasLockedBehaviourPack() { return hasLockedBehaviourPack; }
    public StartGamePacket setHasLockedBehaviourPack(boolean v) { this.hasLockedBehaviourPack = v; return this; }

    public boolean hasLockedTexturePack() { return hasLockedTexturePack; }
    public StartGamePacket setHasLockedTexturePack(boolean v) { this.hasLockedTexturePack = v; return this; }

    public boolean fromLockedWorldTemplate() { return fromLockedWorldTemplate; }
    public StartGamePacket setFromLockedWorldTemplate(boolean v) { this.fromLockedWorldTemplate = v; return this; }

    public boolean msaGamerTagsOnly() { return msaGamerTagsOnly; }
    public StartGamePacket setMsaGamerTagsOnly(boolean v) { this.msaGamerTagsOnly = v; return this; }

    public boolean fromWorldTemplate() { return fromWorldTemplate; }
    public StartGamePacket setFromWorldTemplate(boolean v) { this.fromWorldTemplate = v; return this; }

    public boolean worldTemplateSettingsLocked() { return worldTemplateSettingsLocked; }
    public StartGamePacket setWorldTemplateSettingsLocked(boolean v) { this.worldTemplateSettingsLocked = v; return this; }

    public boolean onlySpawnV1Villagers() { return onlySpawnV1Villagers; }
    public StartGamePacket setOnlySpawnV1Villagers(boolean v) { this.onlySpawnV1Villagers = v; return this; }

    public boolean personaDisabled() { return personaDisabled; }
    public StartGamePacket setPersonaDisabled(boolean v) { this.personaDisabled = v; return this; }

    public boolean customSkinsDisabled() { return customSkinsDisabled; }
    public StartGamePacket setCustomSkinsDisabled(boolean v) { this.customSkinsDisabled = v; return this; }

    public boolean emoteChatMuted() { return emoteChatMuted; }
    public StartGamePacket setEmoteChatMuted(boolean v) { this.emoteChatMuted = v; return this; }

    public String baseGameVersion() { return baseGameVersion; }
    public StartGamePacket setBaseGameVersion(String v) { this.baseGameVersion = v != null ? v : ""; return this; }

    public int limitedWorldWidth() { return limitedWorldWidth; }
    public StartGamePacket setLimitedWorldWidth(int v) { this.limitedWorldWidth = v; return this; }

    public int limitedWorldDepth() { return limitedWorldDepth; }
    public StartGamePacket setLimitedWorldDepth(int v) { this.limitedWorldDepth = v; return this; }

    public boolean newNether() { return newNether; }
    public StartGamePacket setNewNether(boolean v) { this.newNether = v; return this; }

    public EducationSharedResourceURI educationSharedResourceUri() { return educationSharedResourceUri; }
    public StartGamePacket setEducationSharedResourceUri(EducationSharedResourceURI v) {
        this.educationSharedResourceUri = v != null ? v : EducationSharedResourceURI.EMPTY;
        return this;
    }

    public Boolean forceExperimentalGameplay() { return forceExperimentalGameplay; }
    public StartGamePacket setForceExperimentalGameplay(Boolean v) { this.forceExperimentalGameplay = v; return this; }

    public int chatRestrictionLevel() { return chatRestrictionLevel; }
    public StartGamePacket setChatRestrictionLevel(int v) { this.chatRestrictionLevel = v; return this; }

    public boolean disablePlayerInteractions() { return disablePlayerInteractions; }
    public StartGamePacket setDisablePlayerInteractions(boolean v) { this.disablePlayerInteractions = v; return this; }

    public String levelId() { return levelId; }
    public StartGamePacket setLevelId(String v) { this.levelId = v != null ? v : ""; return this; }

    public String worldName() { return worldName; }
    public StartGamePacket setWorldName(String v) { this.worldName = v != null ? v : ""; return this; }

    public String templateContentIdentity() { return templateContentIdentity; }
    public StartGamePacket setTemplateContentIdentity(String v) { this.templateContentIdentity = v != null ? v : ""; return this; }

    public boolean trial() { return trial; }
    public StartGamePacket setTrial(boolean v) { this.trial = v; return this; }

    public PlayerMovementSettings playerMovementSettings() { return playerMovementSettings; }
    public StartGamePacket setPlayerMovementSettings(PlayerMovementSettings v) {
        this.playerMovementSettings = v != null ? v : PlayerMovementSettings.BDS_DEFAULT;
        return this;
    }

    public long time() { return time; }
    public StartGamePacket setTime(long v) { this.time = v; return this; }

    public int enchantmentSeed() { return enchantmentSeed; }
    public StartGamePacket setEnchantmentSeed(int v) { this.enchantmentSeed = v; return this; }

    public List<BlockEntry> blocks() { return blocks; }
    public StartGamePacket setBlocks(List<BlockEntry> v) { this.blocks = v != null ? v : new ArrayList<>(); return this; }

    public String multiPlayerCorrelationId() { return multiPlayerCorrelationId; }
    public StartGamePacket setMultiPlayerCorrelationId(String v) { this.multiPlayerCorrelationId = v != null ? v : ""; return this; }

    public boolean serverAuthoritativeInventory() { return serverAuthoritativeInventory; }
    public StartGamePacket setServerAuthoritativeInventory(boolean v) { this.serverAuthoritativeInventory = v; return this; }

    public String gameVersion() { return gameVersion; }
    public StartGamePacket setGameVersion(String v) { this.gameVersion = v != null ? v : ""; return this; }

    public NbtMap propertyData() { return propertyData; }
    public StartGamePacket setPropertyData(NbtMap v) { this.propertyData = v != null ? v : NbtMap.EMPTY; return this; }

    public long serverBlockStateChecksum() { return serverBlockStateChecksum; }
    public StartGamePacket setServerBlockStateChecksum(long v) { this.serverBlockStateChecksum = v; return this; }

    public UUID worldTemplateId() { return worldTemplateId; }
    public StartGamePacket setWorldTemplateId(UUID v) { this.worldTemplateId = v != null ? v : new UUID(0L, 0L); return this; }

    public boolean clientSideGeneration() { return clientSideGeneration; }
    public StartGamePacket setClientSideGeneration(boolean v) { this.clientSideGeneration = v; return this; }

    public boolean useBlockNetworkIdHashes() { return useBlockNetworkIdHashes; }
    public StartGamePacket setUseBlockNetworkIdHashes(boolean v) { this.useBlockNetworkIdHashes = v; return this; }

    public boolean serverAuthoritativeSound() { return serverAuthoritativeSound; }
    public StartGamePacket setServerAuthoritativeSound(boolean v) { this.serverAuthoritativeSound = v; return this; }

    public ServerJoinInformation serverJoinInformation() { return serverJoinInformation; }
    public StartGamePacket setServerJoinInformation(ServerJoinInformation v) { this.serverJoinInformation = v; return this; }

    public String serverId() { return serverId; }
    public StartGamePacket setServerId(String v) { this.serverId = v != null ? v : ""; return this; }

    public String scenarioId() { return scenarioId; }
    public StartGamePacket setScenarioId(String v) { this.scenarioId = v != null ? v : ""; return this; }

    public String worldId() { return worldId; }
    public StartGamePacket setWorldId(String v) { this.worldId = v != null ? v : ""; return this; }

    public String ownerId() { return ownerId; }
    public StartGamePacket setOwnerId(String v) { this.ownerId = v != null ? v : ""; return this; }
}
