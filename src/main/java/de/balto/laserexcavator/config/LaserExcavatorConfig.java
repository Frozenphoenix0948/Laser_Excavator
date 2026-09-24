package de.balto.laserexcavator.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LaserExcavatorConfig {
    public enum NetworkDebugMode {
        NORMAL,
        NO_VISUALS,
        DISABLED
    }

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue DEFAULT_WIDTH;
    public static final ModConfigSpec.IntValue DEFAULT_HEIGHT;
    public static final ModConfigSpec.IntValue DEFAULT_LENGTH;
    public static final ModConfigSpec.IntValue MIN_SELECTION_SIZE;
    public static final ModConfigSpec.IntValue MAX_HORIZONTAL_SIZE;
    public static final ModConfigSpec.IntValue MAX_VERTICAL_SIZE;
    public static final ModConfigSpec.IntValue SCAN_COLUMNS_PER_TICK;
    public static final ModConfigSpec.IntValue FORCE_FIELD_HEIGHT;
    public static final ModConfigSpec.IntValue TRANSPORT_TICKS_PER_BLOCK;
    public static final ModConfigSpec.IntValue DELIVERY_RETRY_TICKS;
    public static final ModConfigSpec.IntValue ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue ENERGY_MAX_RECEIVE;
    public static final ModConfigSpec.IntValue ENERGY_PER_BLOCK;
    public static final ModConfigSpec.BooleanValue FUEL_SLOT_ENABLED;
    public static final ModConfigSpec.IntValue FUEL_ENERGY_PER_BURN_TICK;
    public static final ModConfigSpec.IntValue FUEL_BURN_TICKS_PER_SERVER_TICK;
    public static final ModConfigSpec.BooleanValue SUPPRESS_NEIGHBOR_UPDATES;
    public static final ModConfigSpec.BooleanValue ENABLE_DETERMINISTIC_LOOT_CACHE;
    public static final ModConfigSpec.BooleanValue ENABLE_DIRECT_TERRAIN_REMOVAL;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> UNBREAKABLE_BLOCKS;
    public static final ModConfigSpec.IntValue VISUAL_BATCH_TICKS;
    public static final ModConfigSpec.IntValue VISUAL_BATCH_MAX_EVENTS;
    public static final ModConfigSpec.EnumValue<NetworkDebugMode> NETWORK_DEBUG_MODE;
    public static final ModConfigSpec.BooleanValue ENABLE_STRESS_TEST_COMMANDS;

    public static final ModConfigSpec.IntValue UPGRADE_SLOT_COUNT;
    public static final ModConfigSpec.IntValue SPEED_BASE_INTERVAL;
    public static final ModConfigSpec.IntValue SPEED_TIER_1_INTERVAL;
    public static final ModConfigSpec.IntValue SPEED_TIER_2_INTERVAL;
    public static final ModConfigSpec.IntValue SPEED_TIER_3_INTERVAL;
    public static final ModConfigSpec.IntValue SPEED_TIER_4_INTERVAL;
    public static final ModConfigSpec.IntValue SPEED_TIER_5_INTERVAL;
    public static final ModConfigSpec.IntValue ENERGY_EFFICIENCY_TIER_1_REDUCTION;
    public static final ModConfigSpec.IntValue ENERGY_EFFICIENCY_TIER_2_REDUCTION;
    public static final ModConfigSpec.IntValue ENERGY_EFFICIENCY_TIER_3_REDUCTION;
    public static final ModConfigSpec.IntValue ENERGY_EFFICIENCY_TIER_4_REDUCTION;
    public static final ModConfigSpec.IntValue ENERGY_EFFICIENCY_TIER_5_REDUCTION;
    public static final ModConfigSpec.IntValue BASE_HORIZONTAL_SIZE;
    public static final ModConfigSpec.IntValue AREA_TIER_1_SIZE;
    public static final ModConfigSpec.IntValue AREA_TIER_2_SIZE;
    public static final ModConfigSpec.IntValue AREA_TIER_3_SIZE;
    public static final ModConfigSpec.BooleanValue AUTO_SMELTING_ENABLED;
    public static final ModConfigSpec.IntValue LUCK_TIER_1_LEVEL;
    public static final ModConfigSpec.IntValue LUCK_TIER_2_LEVEL;
    public static final ModConfigSpec.IntValue LUCK_TIER_3_LEVEL;
    public static final ModConfigSpec.IntValue FILTER_TIER_1_CAPACITY;
    public static final ModConfigSpec.IntValue FILTER_TIER_2_CAPACITY;
    public static final ModConfigSpec.IntValue FILTER_TIER_3_CAPACITY;
    public static final ModConfigSpec.IntValue FILTER_TIER_4_CAPACITY;
    public static final ModConfigSpec.IntValue FILTER_TIER_5_CAPACITY;
    public static final ModConfigSpec.IntValue FILTER_TIER_1_COOLDOWN_REDUCTION;
    public static final ModConfigSpec.IntValue FILTER_TIER_2_COOLDOWN_REDUCTION;
    public static final ModConfigSpec.IntValue FILTER_TIER_3_COOLDOWN_REDUCTION;
    public static final ModConfigSpec.IntValue FILTER_TIER_4_COOLDOWN_REDUCTION;
    public static final ModConfigSpec.IntValue FILTER_TIER_5_COOLDOWN_REDUCTION;
    public static final ModConfigSpec.BooleanValue SILK_TOUCH_ENABLED;


    public static final RecipeConfig EXCAVATOR_RECIPE;
    public static final RecipeConfig SPEED_TIER_1_RECIPE;
    public static final RecipeConfig SPEED_TIER_2_RECIPE;
    public static final RecipeConfig SPEED_TIER_3_RECIPE;
    public static final RecipeConfig SPEED_TIER_4_RECIPE;
    public static final RecipeConfig SPEED_TIER_5_RECIPE;
    public static final RecipeConfig AUTO_SMELTING_RECIPE;
    public static final RecipeConfig AREA_TIER_1_RECIPE;
    public static final RecipeConfig AREA_TIER_2_RECIPE;
    public static final RecipeConfig AREA_TIER_3_RECIPE;
    public static final RecipeConfig LUCK_TIER_1_RECIPE;
    public static final RecipeConfig LUCK_TIER_2_RECIPE;
    public static final RecipeConfig LUCK_TIER_3_RECIPE;
    public static final RecipeConfig FILTER_TIER_1_RECIPE;
    public static final RecipeConfig FILTER_TIER_2_RECIPE;
    public static final RecipeConfig FILTER_TIER_3_RECIPE;
    public static final RecipeConfig FILTER_TIER_4_RECIPE;
    public static final RecipeConfig FILTER_TIER_5_RECIPE;
    public static final RecipeConfig SILK_TOUCH_RECIPE;
    public static final RecipeConfig FLUID_IGNORE_RECIPE;
    public static final RecipeConfig NETHER_COOLING_RECIPE;
    public static final RecipeConfig ENERGY_EFFICIENCY_TIER_1_RECIPE;
    public static final RecipeConfig ENERGY_EFFICIENCY_TIER_2_RECIPE;
    public static final RecipeConfig ENERGY_EFFICIENCY_TIER_3_RECIPE;
    public static final RecipeConfig ENERGY_EFFICIENCY_TIER_4_RECIPE;
    public static final RecipeConfig ENERGY_EFFICIENCY_TIER_5_RECIPE;

    /**
     * Resolved global hard blacklist. The config stores registry IDs because that is
     * readable/editable for pack authors, while excavation uses Block identity checks
     * so target scanning never reparses ResourceLocations in its inner loop.
     */
    private static volatile List<String> cachedUnbreakableIds = List.of();
    private static volatile Set<Block> cachedUnbreakableBlocks = Set.of();
    private static volatile NetworkDebugMode networkDebugModeOverride;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(" Core excavator behavior.").push("machine");
        DEFAULT_WIDTH = builder.comment(" Default horizontal width of a newly placed excavator.")
                .defineInRange("defaultWidth", 16, 1, 512);
        DEFAULT_HEIGHT = builder.comment(" Default excavation depth/height.")
                .defineInRange("defaultHeight", 32, 1, 384);
        DEFAULT_LENGTH = builder.comment(" Default horizontal length of a newly placed excavator.")
                .defineInRange("defaultLength", 16, 1, 512);
        MIN_SELECTION_SIZE = builder.comment(" Smallest selectable width, height, or length.")
                .defineInRange("minimumSelectionSize", 1, 1, 128);
        MAX_HORIZONTAL_SIZE = builder.comment(" Hard horizontal X/Z selection cap. Area-upgrade sizes are clamped to this value.")
                .defineInRange("maximumHorizontalSize", 128, 1, 512);
        MAX_VERTICAL_SIZE = builder.comment(" Maximum selectable excavation depth/height.")
                .defineInRange("maximumVerticalSize", 256, 1, 384);
        SCAN_COLUMNS_PER_TICK = builder.comment(" X/Z columns scanned each server tick. Higher values finish scans faster but create a larger short CPU burst.")
                .defineInRange("scanColumnsPerTick", 64, 1, 4096);
        FORCE_FIELD_HEIGHT = builder.comment(
                        " Server-authoritative height of the transport force field above the excavator block.",
                        " This height is shared by all players and is also used for item-transport delivery timing."
                )
                .defineInRange("forceFieldHeight", 15, 2, 128);
        TRANSPORT_TICKS_PER_BLOCK = builder.comment(
                        " Item transport travel time per block. Higher values make items fly more slowly."
                )
                .defineInRange("transportTicksPerBlock", 6, 1, 100);
        DELIVERY_RETRY_TICKS = builder.comment(" Retry delay when an arriving transport cannot insert its items into output storage.")
                .defineInRange("deliveryRetryTicks", 20, 1, 1200);
        ENERGY_CAPACITY = builder.comment(" Internal Forge Energy (FE) buffer capacity.")
                .defineInRange("energyCapacity", 1_000_000, 1, Integer.MAX_VALUE);
        ENERGY_MAX_RECEIVE = builder.comment(" Maximum FE the excavator can accept from cables each server tick.")
                .defineInRange("energyMaxReceivePerTick", 10_000, 1, Integer.MAX_VALUE);
        ENERGY_PER_BLOCK = builder.comment(
                        " Total FE required to excavate one normal block before Energy Efficiency reductions.",
                        " The cost is spread across the current Speed-upgrade work interval, so Speed changes power throughput rather than total FE per block."
                )
                .defineInRange("energyPerBlock", 640, 0, Integer.MAX_VALUE);
        SUPPRESS_NEIGHBOR_UPDATES = builder.comment(" Suppress neighbor/shape updates when blocks are removed. This is faster and stops stable fluids from immediately flowing into excavated cells.")
                .define("suppressNeighborUpdates", true);
        ENABLE_DETERMINISTIC_LOOT_CACHE = builder.comment(
                        " Learn deterministic block drops server-wide and reuse them across excavators.",
                        " Cache keys include BlockState, Silk Touch and Fortune level. Random/dynamic results remain on the normal loot-table path."
                )
                .define("deterministicLootCache", true);
        ENABLE_DIRECT_TERRAIN_REMOVAL = builder.comment(
                        " Use a direct LevelChunk block-state mutation for conservatively recognized plain terrain blocks.",
                        " This path is only used while neighbor updates are suppressed. Complex/subclassed blocks always fall back to Level#setBlock."
                )
                .define("directTerrainRemoval", true);
        UNBREAKABLE_BLOCKS = builder.comment(
                        " Block registry IDs that no excavator may ever remove.",
                        " Protected blocks are skipped without being removed, so excavation may continue below them in the same column.",
                        " This is a global hard blacklist and does not require a filter upgrade. Modded IDs are supported."
                )
                .defineList(
                        "unbreakableBlocks",
                        List.of("minecraft:bedrock"),
                        () -> "minecraft:bedrock",
                        value -> value instanceof String id && ResourceLocation.tryParse(id) != null
                );
        builder.pop();

        builder.comment(
                        " Optional furnace-fuel power generation.",
                        " When disabled the fuel slot is not exposed by the server menu and is not rendered by the client."
                )
                .push("fuel");
        FUEL_SLOT_ENABLED = builder.comment(" Enable the excavator fuel slot and internal fuel-to-FE generation.")
                .define("enabled", true);
        FUEL_ENERGY_PER_BURN_TICK = builder.comment(
                        " FE generated for each vanilla furnace burn tick consumed by the excavator."
                )
                .defineInRange("energyPerBurnTick", 10, 1, Integer.MAX_VALUE);
        FUEL_BURN_TICKS_PER_SERVER_TICK = builder.comment(
                        " How many vanilla furnace burn ticks may be converted each server tick.",
                        " This changes fuel conversion speed only; it does not change the total FE obtained from an item."
                )
                .defineInRange("burnTicksPerServerTick", 32, 1, 1200);
        builder.pop();

        builder.comment(
                        " Server-side visual networking/batching.",
                        " Visual starts keep their exact mined-block position but are accumulated for several server ticks before transmission.",
                        " Larger batching intervals reduce packet/task overhead at the cost of additional visual network latency."
                )
                .push("network");
        VISUAL_BATCH_TICKS = builder.comment(
                        " Number of server ticks accumulated into a normal visual batch.",
                        " 1 flushes every tick; 4 is the default and can wait up to about three ticks before a normal flush."
                )
                .defineInRange("visualBatchTicks", 4, 1, 20);
        VISUAL_BATCH_MAX_EVENTS = builder.comment(
                        " Maximum laser + transport events in one compact visual payload.",
                        " A busy tracking chunk flushes early when it reaches this cap; larger queues are split safely."
                )
                .defineInRange("visualBatchMaxEvents", 4096, 64, 4096);
        builder.pop();

        builder.comment(
                        " Server-side networking diagnostic controls.",
                        " NORMAL: send visual and excavation block-update payloads normally.",
                        " NO_VISUALS: do not queue or send laser/transport visual payloads.",
                        " DISABLED: do not queue or send visual payloads or excavation block-update payloads.",
                        " These modes are for performance diagnosis; DISABLED intentionally leaves client terrain stale until chunks are reloaded."
                )
                .push("debug");
        NETWORK_DEBUG_MODE = builder.defineEnum("networkDebugMode", NetworkDebugMode.NORMAL);
        builder.pop();

        builder.comment(
                        " Development-only benchmark tooling.",
                        " Keep disabled for normal gameplay. Stress commands can create very large server and renderer workloads."
                )
                .push("development");
        ENABLE_STRESS_TEST_COMMANDS = builder.comment(
                        " Enable the development stress-test, synthetic transport, and grid commands."
                )
                .define("enableStressTestCommands", false);
        builder.pop();

        builder.comment(
                        " Upgrade behavior. The excavator supports up to seven visible/active upgrade slots and allows at most one installed upgrade per type.",
                        " Reducing the configured slot count leaves higher stored slots inactive so changing the setting does not delete installed upgrades."
                ).push("upgrades");
        UPGRADE_SLOT_COUNT = builder.comment(
                        " Number of visible and active upgrade slots. Set to 0 to disable upgrade installation without deleting stored upgrades."
                )
                .defineInRange("slots", 7, 0, 7);
        builder.comment(" Mining interval in ticks without a speed upgrade and for each speed-upgrade tier.").push("speed");
        SPEED_BASE_INTERVAL = builder.defineInRange("baseIntervalTicks", 20, 1, 1200);
        SPEED_TIER_1_INTERVAL = builder.defineInRange("tier1IntervalTicks", 10, 1, 1200);
        SPEED_TIER_2_INTERVAL = builder.defineInRange("tier2IntervalTicks", 5, 1, 1200);
        SPEED_TIER_3_INTERVAL = builder.defineInRange("tier3IntervalTicks", 3, 1, 1200);
        SPEED_TIER_4_INTERVAL = builder.defineInRange("tier4IntervalTicks", 2, 1, 1200);
        SPEED_TIER_5_INTERVAL = builder.defineInRange("tier5IntervalTicks", 1, 1, 1200);
        builder.pop();

        builder.comment(
                " Percentage reduction of total FE per excavated block for Energy Efficiency I-V.",
                " Effective values are kept monotonic so higher tiers can never use more energy than lower tiers."
        ).push("energyEfficiency");
        ENERGY_EFFICIENCY_TIER_1_REDUCTION = builder.defineInRange("tier1ReductionPercent", 10, 0, 100);
        ENERGY_EFFICIENCY_TIER_2_REDUCTION = builder.defineInRange("tier2ReductionPercent", 20, 0, 100);
        ENERGY_EFFICIENCY_TIER_3_REDUCTION = builder.defineInRange("tier3ReductionPercent", 30, 0, 100);
        ENERGY_EFFICIENCY_TIER_4_REDUCTION = builder.defineInRange("tier4ReductionPercent", 40, 0, 100);
        ENERGY_EFFICIENCY_TIER_5_REDUCTION = builder.defineInRange("tier5ReductionPercent", 50, 0, 100);
        builder.pop();

        builder.comment(" Maximum horizontal X/Z size without an area upgrade and with Area I/II/III.").push("area");
        BASE_HORIZONTAL_SIZE = builder.defineInRange("baseSize", 16, 1, 512);
        AREA_TIER_1_SIZE = builder.defineInRange("tier1Size", 32, 1, 512);
        AREA_TIER_2_SIZE = builder.defineInRange("tier2Size", 64, 1, 512);
        AREA_TIER_3_SIZE = builder.defineInRange("tier3Size", 128, 1, 512);
        builder.pop();

        AUTO_SMELTING_ENABLED = builder.comment(" Master switch for the auto-smelting upgrade functionality.")
                .define("autoSmeltingEnabled", true);

        builder.comment(" Fortune level applied by Luck I/II/III.").push("luck");
        LUCK_TIER_1_LEVEL = builder.defineInRange("tier1FortuneLevel", 1, 0, 10);
        LUCK_TIER_2_LEVEL = builder.defineInRange("tier2FortuneLevel", 2, 0, 10);
        LUCK_TIER_3_LEVEL = builder.defineInRange("tier3FortuneLevel", 3, 0, 10);
        builder.pop();

        builder.comment(" Number of exact block types ignored by Filter I-V. The UI supports at most 16 entries.").push("filter");
        FILTER_TIER_1_CAPACITY = builder.defineInRange("tier1Entries", 1, 0, 16);
        FILTER_TIER_2_CAPACITY = builder.defineInRange("tier2Entries", 4, 0, 16);
        FILTER_TIER_3_CAPACITY = builder.defineInRange("tier3Entries", 8, 0, 16);
        FILTER_TIER_4_CAPACITY = builder.defineInRange("tier4Entries", 12, 0, 16);
        FILTER_TIER_5_CAPACITY = builder.defineInRange("tier5Entries", 16, 0, 16);
        builder.comment(
                " Percentage by which each filter tier reduces the normal mining-cycle delay applied when a configured block is skipped.",
                " 0 means a skipped block takes a full mining cycle; 100 skips it immediately with no mining-cycle delay.",
                " Effective reduction is kept monotonic so a higher filter tier can never be slower than a lower tier."
        );
        FILTER_TIER_1_COOLDOWN_REDUCTION = builder.defineInRange("tier1CooldownReductionPercent", 0, 0, 100);
        FILTER_TIER_2_COOLDOWN_REDUCTION = builder.defineInRange("tier2CooldownReductionPercent", 25, 0, 100);
        FILTER_TIER_3_COOLDOWN_REDUCTION = builder.defineInRange("tier3CooldownReductionPercent", 50, 0, 100);
        FILTER_TIER_4_COOLDOWN_REDUCTION = builder.defineInRange("tier4CooldownReductionPercent", 75, 0, 100);
        FILTER_TIER_5_COOLDOWN_REDUCTION = builder.defineInRange("tier5CooldownReductionPercent", 100, 0, 100);
        builder.pop();

        SILK_TOUCH_ENABLED = builder.comment(" Master switch for Silk Touch upgrade behavior.")
                .define("silkTouchEnabled", true);
        builder.pop();

        builder.comment(
                " Crafting recipes. Ingredient entries are item IDs such as minecraft:diamond or item tags prefixed with #.",
                " Shaped recipes use exactly nine entries in row-major order; an empty string means an empty slot.",
                " Shapeless recipes use one entry per ingredient. Recipe changes take effect after /reload or the next datapack sync."
        ).push("recipes");

        EXCAVATOR_RECIPE = recipe(builder, "excavator", false, List.of(
                "minecraft:iron_block", "minecraft:diamond_block", "minecraft:iron_block",
                "minecraft:redstone_block", "minecraft:blast_furnace", "minecraft:redstone_block",
                "minecraft:iron_block", "minecraft:hopper", "minecraft:iron_block"
        ));
        SPEED_TIER_1_RECIPE = recipe(builder, "speedTier1", false, List.of(
                "minecraft:redstone_block", "minecraft:gold_ingot", "minecraft:redstone_block",
                "minecraft:quartz", "minecraft:paper", "minecraft:quartz",
                "minecraft:redstone_block", "minecraft:gold_ingot", "minecraft:redstone_block"
        ));
        SPEED_TIER_2_RECIPE = recipe(builder, "speedTier2", false, List.of(
                "minecraft:redstone_block", "minecraft:gold_block", "minecraft:redstone_block",
                "minecraft:diamond", "laserexcavator:speed_upgrade_tier_1", "minecraft:diamond",
                "minecraft:redstone_block", "minecraft:gold_block", "minecraft:redstone_block"
        ));
        SPEED_TIER_3_RECIPE = recipe(builder, "speedTier3", false, List.of(
                "minecraft:gold_block", "minecraft:redstone_block", "minecraft:gold_block",
                "minecraft:diamond", "laserexcavator:speed_upgrade_tier_2", "minecraft:diamond",
                "minecraft:gold_block", "minecraft:comparator", "minecraft:gold_block"
        ));
        SPEED_TIER_4_RECIPE = recipe(builder, "speedTier4", false, List.of(
                "minecraft:diamond_block", "minecraft:ender_eye", "minecraft:redstone_block",
                "minecraft:ender_eye", "laserexcavator:speed_upgrade_tier_3", "minecraft:ender_eye",
                "minecraft:redstone_block", "minecraft:ender_eye", "minecraft:diamond_block"
        ));
        SPEED_TIER_5_RECIPE = recipe(builder, "speedTier5", false, List.of(
                "minecraft:netherite_ingot", "minecraft:ender_eye", "minecraft:redstone_block",
                "minecraft:diamond_block", "laserexcavator:speed_upgrade_tier_4", "minecraft:diamond_block",
                "minecraft:redstone_block", "minecraft:ender_eye", "minecraft:netherite_ingot"
        ));
        AUTO_SMELTING_RECIPE = recipe(builder, "autoSmelting", false, List.of(
                "minecraft:redstone", "minecraft:magma_cream", "minecraft:redstone",
                "minecraft:quartz", "minecraft:blast_furnace", "minecraft:quartz",
                "minecraft:redstone", "minecraft:blaze_powder", "minecraft:redstone"
        ));
        AREA_TIER_1_RECIPE = recipe(builder, "areaTier1", false, List.of(
                "minecraft:iron_block", "minecraft:compass", "minecraft:iron_block",
                "minecraft:redstone_block", "minecraft:paper", "minecraft:redstone_block",
                "minecraft:iron_block", "minecraft:compass", "minecraft:iron_block"
        ));
        AREA_TIER_2_RECIPE = recipe(builder, "areaTier2", false, List.of(
                "minecraft:gold_block", "minecraft:ender_pearl", "minecraft:gold_block",
                "minecraft:redstone_block", "laserexcavator:area_upgrade_32", "minecraft:redstone_block",
                "minecraft:gold_block", "minecraft:ender_pearl", "minecraft:gold_block"
        ));
        AREA_TIER_3_RECIPE = recipe(builder, "areaTier3", false, List.of(
                "minecraft:diamond_block", "minecraft:ender_eye", "minecraft:diamond_block",
                "minecraft:crying_obsidian", "laserexcavator:area_upgrade_64", "minecraft:crying_obsidian",
                "minecraft:diamond_block", "minecraft:ender_eye", "minecraft:diamond_block"
        ));
        LUCK_TIER_1_RECIPE = recipe(builder, "luckTier1", false, List.of(
                "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:redstone",
                "minecraft:quartz", "minecraft:paper", "minecraft:quartz",
                "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:redstone"
        ));
        LUCK_TIER_2_RECIPE = recipe(builder, "luckTier2", false, List.of(
                "minecraft:lapis_block", "minecraft:emerald", "minecraft:lapis_block",
                "minecraft:gold_ingot", "laserexcavator:luck_upgrade_tier_1", "minecraft:gold_ingot",
                "minecraft:lapis_block", "minecraft:diamond", "minecraft:lapis_block"
        ));
        LUCK_TIER_3_RECIPE = recipe(builder, "luckTier3", false, List.of(
                "minecraft:diamond", "minecraft:ender_eye", "minecraft:diamond",
                "minecraft:lapis_block", "laserexcavator:luck_upgrade_tier_2", "minecraft:lapis_block",
                "minecraft:emerald_block", "minecraft:netherite_ingot", "minecraft:emerald_block"
        ));
        FILTER_TIER_1_RECIPE = recipe(builder, "filterTier1", false, List.of(
                "minecraft:iron_block", "minecraft:comparator", "minecraft:iron_block",
                "minecraft:redstone_block", "minecraft:paper", "minecraft:redstone_block",
                "minecraft:iron_block", "minecraft:compass", "minecraft:iron_block"
        ));
        FILTER_TIER_2_RECIPE = recipe(builder, "filterTier2", false, List.of(
                "minecraft:gold_block", "minecraft:comparator", "minecraft:gold_block",
                "minecraft:redstone_block", "laserexcavator:filter_upgrade_tier_1", "minecraft:redstone_block",
                "minecraft:gold_block", "minecraft:hopper", "minecraft:gold_block"
        ));
        FILTER_TIER_3_RECIPE = recipe(builder, "filterTier3", false, List.of(
                "minecraft:diamond_block", "minecraft:ender_pearl", "minecraft:diamond_block",
                "minecraft:comparator", "laserexcavator:filter_upgrade_tier_2", "minecraft:comparator",
                "minecraft:gold_block", "minecraft:redstone_block", "minecraft:gold_block"
        ));
        FILTER_TIER_4_RECIPE = recipe(builder, "filterTier4", false, List.of(
                "minecraft:diamond_block", "minecraft:ender_eye", "minecraft:diamond_block",
                "minecraft:obsidian", "laserexcavator:filter_upgrade_tier_3", "minecraft:obsidian",
                "minecraft:diamond_block", "minecraft:comparator", "minecraft:diamond_block"
        ));
        FILTER_TIER_5_RECIPE = recipe(builder, "filterTier5", false, List.of(
                "minecraft:netherite_block", "minecraft:ender_eye", "minecraft:netherite_block",
                "minecraft:diamond_block", "laserexcavator:filter_upgrade_tier_4", "minecraft:diamond_block",
                "minecraft:netherite_block", "minecraft:nether_star", "minecraft:netherite_block"
        ));
        SILK_TOUCH_RECIPE = recipe(builder, "silkTouch", false, List.of(
                "minecraft:amethyst_shard", "minecraft:diamond", "minecraft:amethyst_shard",
                "minecraft:obsidian", "@silk_touch_book", "minecraft:obsidian",
                "minecraft:amethyst_shard", "minecraft:enchanting_table", "minecraft:amethyst_shard"
        ));
        FLUID_IGNORE_RECIPE = recipe(builder, "fluidIgnore", false, List.of(
                "minecraft:sponge", "minecraft:bucket", "minecraft:sponge",
                "minecraft:redstone", "minecraft:paper", "minecraft:redstone",
                "minecraft:sponge", "minecraft:bucket", "minecraft:sponge"
        ));
        NETHER_COOLING_RECIPE = recipe(builder, "netherCooling", false, List.of(
                "minecraft:netherite_ingot", "minecraft:magma_cream", "minecraft:netherite_ingot",
                "minecraft:blaze_powder", "minecraft:blue_ice", "minecraft:blaze_powder",
                "minecraft:netherite_ingot", "minecraft:magma_cream", "minecraft:netherite_ingot"
        ));
        ENERGY_EFFICIENCY_TIER_1_RECIPE = recipe(builder, "energyEfficiencyTier1", false, List.of(
                "minecraft:copper_ingot", "minecraft:quartz", "minecraft:copper_ingot",
                "minecraft:redstone", "minecraft:paper", "minecraft:redstone",
                "minecraft:copper_ingot", "minecraft:quartz", "minecraft:copper_ingot"
        ));
        ENERGY_EFFICIENCY_TIER_2_RECIPE = recipe(builder, "energyEfficiencyTier2", false, List.of(
                "minecraft:quartz", "minecraft:copper_block", "minecraft:quartz",
                "minecraft:redstone_block", "laserexcavator:energy_efficiency_upgrade_tier_1", "minecraft:redstone_block",
                "minecraft:quartz", "minecraft:copper_block", "minecraft:quartz"
        ));
        ENERGY_EFFICIENCY_TIER_3_RECIPE = recipe(builder, "energyEfficiencyTier3", false, List.of(
                "minecraft:redstone_block", "minecraft:gold_block", "minecraft:redstone_block",
                "minecraft:diamond", "laserexcavator:energy_efficiency_upgrade_tier_2", "minecraft:diamond",
                "minecraft:redstone_block", "minecraft:comparator", "minecraft:redstone_block"
        ));
        ENERGY_EFFICIENCY_TIER_4_RECIPE = recipe(builder, "energyEfficiencyTier4", false, List.of(
                "minecraft:ender_eye", "minecraft:diamond_block", "minecraft:ender_eye",
                "minecraft:emerald", "laserexcavator:energy_efficiency_upgrade_tier_3", "minecraft:emerald",
                "minecraft:ender_eye", "minecraft:diamond_block", "minecraft:ender_eye"
        ));
        ENERGY_EFFICIENCY_TIER_5_RECIPE = recipe(builder, "energyEfficiencyTier5", false, List.of(
                "minecraft:obsidian", "minecraft:nether_star", "minecraft:obsidian",
                "minecraft:netherite_ingot", "laserexcavator:energy_efficiency_upgrade_tier_4", "minecraft:netherite_ingot",
                "minecraft:obsidian", "minecraft:echo_shard", "minecraft:obsidian"
        ));
        builder.pop();

        SPEC = builder.build();
    }

    public static int upgradeSlotCount() {
        return Math.max(0, Math.min(7, UPGRADE_SLOT_COUNT.get()));
    }

    public static boolean fuelSlotEnabled() {
        return FUEL_SLOT_ENABLED.get();
    }

    public static boolean stressTestCommandsEnabled() {
        return ENABLE_STRESS_TEST_COMMANDS.get();
    }

    private LaserExcavatorConfig() {}

    private static RecipeConfig recipe(ModConfigSpec.Builder builder, String key, boolean shapeless, List<String> ingredients) {
        builder.push(key);
        ModConfigSpec.BooleanValue enabled = builder.comment(" Whether this recipe exists.").define("enabled", true);
        ModConfigSpec.BooleanValue shapelessValue = builder.comment(" false = shaped 3x3, true = shapeless.").define("shapeless", shapeless);
        ModConfigSpec.ConfigValue<List<? extends String>> ingredientList = builder.comment(
                        " Item IDs or #item_tags. For shaped recipes use nine entries; empty string is an empty slot. " +
                                "Special ingredient @silk_touch_book requires an enchanted book containing Silk Touch I."
                )
                .defineList("ingredients", ingredients, () -> "minecraft:stone", value -> value instanceof String);
        ModConfigSpec.IntValue resultCount = builder.defineInRange("resultCount", 1, 1, 64);
        builder.pop();
        return new RecipeConfig(enabled, shapelessValue, ingredientList, resultCount);
    }

    public static int clampSelectionSize(int value, int max) {
        int normalizedMax = Math.max(1, max);
        int normalizedMin = Math.min(MIN_SELECTION_SIZE.get(), normalizedMax);
        return Math.max(normalizedMin, Math.min(value, normalizedMax));
    }

    public static int speedInterval(int tier) {
        return switch (tier) {
            case 1 -> SPEED_TIER_1_INTERVAL.get();
            case 2 -> SPEED_TIER_2_INTERVAL.get();
            case 3 -> SPEED_TIER_3_INTERVAL.get();
            case 4 -> SPEED_TIER_4_INTERVAL.get();
            case 5 -> SPEED_TIER_5_INTERVAL.get();
            default -> SPEED_BASE_INTERVAL.get();
        };
    }

    public static int energyEfficiencyReductionPercent(int tier) {
        int tier1 = ENERGY_EFFICIENCY_TIER_1_REDUCTION.get();
        int tier2 = Math.max(tier1, ENERGY_EFFICIENCY_TIER_2_REDUCTION.get());
        int tier3 = Math.max(tier2, ENERGY_EFFICIENCY_TIER_3_REDUCTION.get());
        int tier4 = Math.max(tier3, ENERGY_EFFICIENCY_TIER_4_REDUCTION.get());
        int tier5 = Math.max(tier4, ENERGY_EFFICIENCY_TIER_5_REDUCTION.get());
        return switch (tier) {
            case 1 -> Math.min(100, tier1);
            case 2 -> Math.min(100, tier2);
            case 3 -> Math.min(100, tier3);
            case 4 -> Math.min(100, tier4);
            case 5 -> Math.min(100, tier5);
            default -> 0;
        };
    }

    public static int energyPerBlock(int tier) {
        long base = Math.max(0, ENERGY_PER_BLOCK.get());
        int reduction = energyEfficiencyReductionPercent(tier);
        long remainingPercent = 100L - reduction;
        long cost = (base * remainingPercent + 50L) / 100L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, cost));
    }

    /**
     * Nominal FE draw per powered excavation tick for the supplied Speed and
     * Energy Efficiency tiers. The live work loop redistributes any remainder
     * across the shot, so this is the maximum/rounded-up per-tick value used by
     * tooltips and balance calculations.
     */
    public static int energyPerTick(int speedTier, int efficiencyTier) {
        long energyPerBlock = energyPerBlock(efficiencyTier);
        int interval = Math.max(1, speedInterval(speedTier));
        long perTick = (energyPerBlock + interval - 1L) / interval;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, perTick));
    }

    public static int luckLevel(int tier) {
        return switch (tier) {
            case 1 -> LUCK_TIER_1_LEVEL.get();
            case 2 -> LUCK_TIER_2_LEVEL.get();
            case 3 -> LUCK_TIER_3_LEVEL.get();
            default -> 0;
        };
    }

    public static int filterCapacity(int tier) {
        int tier1 = FILTER_TIER_1_CAPACITY.get();
        int tier2 = Math.max(tier1, FILTER_TIER_2_CAPACITY.get());
        int tier3 = Math.max(tier2, FILTER_TIER_3_CAPACITY.get());
        int tier4 = Math.max(tier3, FILTER_TIER_4_CAPACITY.get());
        int tier5 = Math.max(tier4, FILTER_TIER_5_CAPACITY.get());
        return switch (tier) {
            case 1 -> Math.min(16, tier1);
            case 2 -> Math.min(16, tier2);
            case 3 -> Math.min(16, tier3);
            case 4 -> Math.min(16, tier4);
            case 5 -> Math.min(16, tier5);
            default -> 0;
        };
    }

    public static int filterCooldownReductionPercent(int tier) {
        int tier1 = FILTER_TIER_1_COOLDOWN_REDUCTION.get();
        int tier2 = Math.max(tier1, FILTER_TIER_2_COOLDOWN_REDUCTION.get());
        int tier3 = Math.max(tier2, FILTER_TIER_3_COOLDOWN_REDUCTION.get());
        int tier4 = Math.max(tier3, FILTER_TIER_4_COOLDOWN_REDUCTION.get());
        int tier5 = Math.max(tier4, FILTER_TIER_5_COOLDOWN_REDUCTION.get());
        return switch (tier) {
            case 1 -> Math.min(100, tier1);
            case 2 -> Math.min(100, tier2);
            case 3 -> Math.min(100, tier3);
            case 4 -> Math.min(100, tier4);
            case 5 -> Math.min(100, tier5);
            default -> 100;
        };
    }

    public static int filterSkipCooldownTicks(int filterTier, int miningIntervalTicks) {
        int reduction = filterCooldownReductionPercent(filterTier);
        if (reduction >= 100) return 0;
        int interval = Math.max(1, miningIntervalTicks);
        int remainingPercent = 100 - reduction;
        return Math.max(1, Math.round(interval * (remainingPercent / 100.0F)));
    }

    public static int areaSize(int tier) {
        int hardMax = MAX_HORIZONTAL_SIZE.get();
        int base = Math.min(BASE_HORIZONTAL_SIZE.get(), hardMax);
        int tier1 = Math.min(Math.max(base, AREA_TIER_1_SIZE.get()), hardMax);
        int tier2 = Math.min(Math.max(tier1, AREA_TIER_2_SIZE.get()), hardMax);
        int tier3 = Math.min(Math.max(tier2, AREA_TIER_3_SIZE.get()), hardMax);
        return switch (tier) {
            case 1 -> tier1;
            case 2 -> tier2;
            case 3 -> tier3;
            default -> base;
        };
    }

    public static int maxConfiguredHorizontalSize() {
        return MAX_HORIZONTAL_SIZE.get();
    }

    /**
     * Returns the resolved global hard blacklist. Resolution is cached and only
     * rebuilt when the configured ID list actually changes (including /reload or
     * a config reload). Unknown-but-syntactically-valid mod IDs are simply ignored
     * until that block exists in the current registry.
     */
    public static Set<Block> unbreakableBlocks() {
        List<? extends String> configured = UNBREAKABLE_BLOCKS.get();
        List<String> cachedIds = cachedUnbreakableIds;

        if (sameStrings(configured, cachedIds)) {
            return cachedUnbreakableBlocks;
        }

        synchronized (LaserExcavatorConfig.class) {
            cachedIds = cachedUnbreakableIds;
            if (sameStrings(configured, cachedIds)) {
                return cachedUnbreakableBlocks;
            }

            HashSet<Block> resolved = new HashSet<>();
            java.util.ArrayList<String> snapshot = new java.util.ArrayList<>(configured.size());
            for (String raw : configured) {
                snapshot.add(raw);
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id == null) continue;
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(resolved::add);
            }

            cachedUnbreakableIds = List.copyOf(snapshot);
            cachedUnbreakableBlocks = Set.copyOf(resolved);
            return cachedUnbreakableBlocks;
        }
    }

    public static NetworkDebugMode networkDebugMode() {
        NetworkDebugMode override = networkDebugModeOverride;
        return override != null ? override : NETWORK_DEBUG_MODE.get();
    }

    public static void setNetworkDebugModeOverride(NetworkDebugMode mode) {
        networkDebugModeOverride = mode;
    }

    public static void clearNetworkDebugModeOverride() {
        networkDebugModeOverride = null;
    }

    public static boolean hasNetworkDebugModeOverride() {
        return networkDebugModeOverride != null;
    }

    public static NetworkDebugMode configuredNetworkDebugMode() {
        return NETWORK_DEBUG_MODE.get();
    }

    public static boolean suppressVisualNetworkPayloads() {
        return networkDebugMode() != NetworkDebugMode.NORMAL;
    }

    public static boolean suppressBlockUpdateNetworkPayloads() {
        return networkDebugMode() == NetworkDebugMode.DISABLED;
    }

    private static boolean sameStrings(List<? extends String> configured, List<String> cached) {
        if (configured.size() != cached.size()) return false;
        for (int i = 0; i < configured.size(); i++) {
            if (!configured.get(i).equals(cached.get(i))) return false;
        }
        return true;
    }

    public record RecipeConfig(
            ModConfigSpec.BooleanValue enabled,
            ModConfigSpec.BooleanValue shapeless,
            ModConfigSpec.ConfigValue<List<? extends String>> ingredients,
            ModConfigSpec.IntValue resultCount
    ) {}
}
