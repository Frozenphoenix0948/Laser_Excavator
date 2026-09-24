package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeItem;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeType;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Owns the upgrade inventory, cached upgrade tiers, block filter and cached loot
 * tool used by the excavator. Changes are reported back to the block entity so
 * selection/excavation state can be invalidated only when necessary.
 */
public final class ExcavatorUpgradeManager {
    private static final String TAG_BLOCK_FILTER = "BlockFilter";
    private static final String TAG_FILTER_SLOT_PREFIX = "Slot";

    /** An eighth compatibility storage slot preserves an item stored in that slot when loading save data. */
    public static final int STORED_UPGRADE_SLOTS = 8;
    public static final int MAX_UPGRADE_SLOTS = 7;
    public static final int MAX_FILTER_SLOTS = 16;

    private final BooleanSupplier levelAvailableSupplier;
    private final BooleanSupplier busySupplier;
    private final BooleanSupplier excavationInitializedSupplier;
    private final Runnable configurationChanged;
    private final Runnable fluidIgnoreModeChanged;
    private final Runnable clampSelectionToAreaUpgrade;
    private final Runnable changeListener;
    private final Runnable syncChangeListener;

    private final int[] cachedUpgradeTiers = new int[ExcavatorUpgradeType.values().length];
    private final Block[] filteredBlocks = new Block[MAX_FILTER_SLOTS];

    /**
     * Hot-path block lookup for the filter. Blocks are registry singletons, so identity
     * semantics are exactly what we want and avoid hashing ResourceLocations or scanning
     * up to sixteen slots for every target/state lookup. The set is rebuilt only when
     * the filter contents or the installed filter tier changes.
     */
    private final Set<Block> filteredBlockLookup = Collections.newSetFromMap(new IdentityHashMap<>());
    private int cachedFilterCapacity;
    private int cachedActiveSlotCount = -1;
    private ItemStack cachedLootTool = ItemStack.EMPTY;
    private int cachedLootToolLuckLevel = -1;
    private boolean cachedLootToolSilkTouch;

    /** Upgrade inventory; not exposed to pipes. */
    private final ItemStackHandler inventory = new ItemStackHandler(STORED_UPGRADE_SLOTS) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return canInstall(slot, stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            int previousMaxArea = maxHorizontalSize();
            int previousFilterTier = tier(ExcavatorUpgradeType.FILTER);
            boolean previousFluidIgnore = hasFluidIgnore();
            refreshCache();

            if (levelAvailableSupplier.getAsBoolean()) {
                boolean fluidIgnoreChanged = previousFluidIgnore != hasFluidIgnore();
                if (fluidIgnoreChanged && excavationInitializedSupplier.getAsBoolean()) {
                    fluidIgnoreModeChanged.run();
                }

                if (!busySupplier.getAsBoolean()) {
                    boolean filterChanged = previousFilterTier != tier(ExcavatorUpgradeType.FILTER);
                    if (filterChanged && excavationInitializedSupplier.getAsBoolean()) {
                        configurationChanged.run();
                        return;
                    }
                    if (maxHorizontalSize() < previousMaxArea) {
                        clampSelectionToAreaUpgrade.run();
                    }
                }
            }
            changeListener.run();
        }
    };

    public ExcavatorUpgradeManager(
            BooleanSupplier levelAvailableSupplier,
            BooleanSupplier busySupplier,
            BooleanSupplier excavationInitializedSupplier,
            Runnable configurationChanged,
            Runnable fluidIgnoreModeChanged,
            Runnable clampSelectionToAreaUpgrade,
            Runnable changeListener,
            Runnable syncChangeListener
    ) {
        this.levelAvailableSupplier = levelAvailableSupplier;
        this.busySupplier = busySupplier;
        this.excavationInitializedSupplier = excavationInitializedSupplier;
        this.configurationChanged = configurationChanged;
        this.fluidIgnoreModeChanged = fluidIgnoreModeChanged;
        this.clampSelectionToAreaUpgrade = clampSelectionToAreaUpgrade;
        this.changeListener = changeListener;
        this.syncChangeListener = syncChangeListener;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public int activeSlotCount() {
        return Math.min(MAX_UPGRADE_SLOTS, LaserExcavatorConfig.upgradeSlotCount());
    }

    public static boolean isExcavatorUpgrade(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ExcavatorUpgradeItem;
    }

    public static @Nullable ExcavatorUpgradeType upgradeType(ItemStack stack) {
        return stack.getItem() instanceof ExcavatorUpgradeItem upgrade
                ? upgrade.getUpgradeType()
                : null;
    }

    /** Upgrades that can change immediately without invalidating the scanned excavation area. */
    public static boolean isHotSwappable(@Nullable ExcavatorUpgradeType type) {
        if (type == null) return false;
        return switch (type) {
            case SPEED, AUTO_SMELTING, LUCK, SILK_TOUCH, FLUID_IGNORE, ENERGY_EFFICIENCY, NETHER_COOLING -> true;
            case AREA, FILTER -> false;
        };
    }

    public boolean canInstall(int slot, ItemStack stack) {
        ExcavatorUpgradeType type = upgradeType(stack);
        int activeSlots = activeSlotCount();
        if (type == null || slot < 0 || slot >= activeSlots) return false;

        for (int other = 0; other < activeSlots; other++) {
            if (other == slot) continue;
            ExcavatorUpgradeType installed = upgradeType(inventory.getStackInSlot(other));
            if (installed == type || upgradesConflict(type, installed)) return false;
        }
        return true;
    }

    public static boolean upgradesConflict(@Nullable ExcavatorUpgradeType first, @Nullable ExcavatorUpgradeType second) {
        if (first == null || second == null) return false;
        return (first == ExcavatorUpgradeType.SILK_TOUCH
                && (second == ExcavatorUpgradeType.LUCK || second == ExcavatorUpgradeType.AUTO_SMELTING))
                || (second == ExcavatorUpgradeType.SILK_TOUCH
                && (first == ExcavatorUpgradeType.LUCK || first == ExcavatorUpgradeType.AUTO_SMELTING));
    }

    public int tier(ExcavatorUpgradeType type) {
        ensureActiveSlotCountCurrent();
        return cachedUpgradeTiers[type.ordinal()];
    }

    public int speedTier() {
        return tier(ExcavatorUpgradeType.SPEED);
    }

    public boolean hasAutoSmelting() {
        return tier(ExcavatorUpgradeType.AUTO_SMELTING) > 0
                && LaserExcavatorConfig.AUTO_SMELTING_ENABLED.get();
    }

    public int luckTier() {
        return tier(ExcavatorUpgradeType.LUCK);
    }

    public int luckLevel() {
        return LaserExcavatorConfig.luckLevel(tier(ExcavatorUpgradeType.LUCK));
    }

    public boolean hasSilkTouch() {
        return tier(ExcavatorUpgradeType.SILK_TOUCH) > 0
                && LaserExcavatorConfig.SILK_TOUCH_ENABLED.get();
    }

    public boolean hasFluidIgnore() {
        return tier(ExcavatorUpgradeType.FLUID_IGNORE) > 0;
    }

    public boolean hasNetherCooling() {
        return tier(ExcavatorUpgradeType.NETHER_COOLING) > 0;
    }

    public int filterCapacity() {
        ensureActiveSlotCountCurrent();
        return cachedFilterCapacity;
    }

    public int maxHorizontalSize() {
        return LaserExcavatorConfig.areaSize(tier(ExcavatorUpgradeType.AREA));
    }

    public void refreshCache() {
        Arrays.fill(cachedUpgradeTiers, 0);

        int activeSlots = activeSlotCount();
        cachedActiveSlotCount = activeSlots;
        for (int slot = 0; slot < activeSlots; slot++) {
            if (!(inventory.getStackInSlot(slot).getItem() instanceof ExcavatorUpgradeItem upgrade)) continue;
            int index = upgrade.getUpgradeType().ordinal();
            cachedUpgradeTiers[index] = Math.max(cachedUpgradeTiers[index], upgrade.getTier());
        }

        cachedFilterCapacity = LaserExcavatorConfig.filterCapacity(tier(ExcavatorUpgradeType.FILTER));
        rebuildFilterLookup();
        cachedLootTool = ItemStack.EMPTY;
    }

    private void ensureActiveSlotCountCurrent() {
        if (cachedActiveSlotCount != activeSlotCount()) {
            refreshCache();
        }
    }

    public @Nullable Block filterBlock(int slot) {
        return slot >= 0 && slot < MAX_FILTER_SLOTS ? filteredBlocks[slot] : null;
    }

    public int filterBlockRegistryId(int slot) {
        Block block = filterBlock(slot);
        return block == null ? -1 : BuiltInRegistries.BLOCK.getId(block);
    }

    public boolean setFilterBlock(int slot, @Nullable Block block) {
        if (slot < 0 || slot >= filterCapacity() || busySupplier.getAsBoolean()) return false;
        if (filteredBlocks[slot] == block) return true;

        filteredBlocks[slot] = block;
        rebuildFilterLookup();
        if (excavationInitializedSupplier.getAsBoolean()) {
            configurationChanged.run();
        } else {
            syncChangeListener.run();
        }
        return true;
    }

    private void rebuildFilterLookup() {
        filteredBlockLookup.clear();
        int capacity = Math.min(cachedFilterCapacity, MAX_FILTER_SLOTS);
        for (int i = 0; i < capacity; i++) {
            Block filtered = filteredBlocks[i];
            if (filtered != null) filteredBlockLookup.add(filtered);
        }
    }

    private boolean isFilteredBlock(Block block) {
        // Most excavators have no active filter at all, making this a single branch.
        return !filteredBlockLookup.isEmpty() && filteredBlockLookup.contains(block);
    }

    public static boolean isFluidBlock(BlockState state) {
        // Include normal LiquidBlock implementations and custom/modded blocks
        // whose default state is intrinsically a fluid. Waterlogged solid blocks
        // are intentionally not treated as fluid blocks.
        return state.getBlock() instanceof LiquidBlock
                || !state.getBlock().defaultBlockState().getFluidState().isEmpty();
    }

    /**
     * True only for user-filtered blocks that should use the tier-dependent skip
     * cooldown. Global protected blocks and Fluid Ignore remain immediate skips.
     */
    public boolean isFilterCooldownTarget(BlockState state, Set<Block> unbreakableBlocks) {
        ensureActiveSlotCountCurrent();
        Block block = state.getBlock();
        return !unbreakableBlocks.contains(block)
                && isFilteredBlock(block)
                && !(hasFluidIgnore() && isFluidBlock(state));
    }

    public boolean shouldIgnoreTarget(BlockState state, Set<Block> unbreakableBlocks) {
        ensureActiveSlotCountCurrent();
        Block block = state.getBlock();
        return unbreakableBlocks.contains(block)
                || isFilteredBlock(block)
                || (hasFluidIgnore() && isFluidBlock(state));
    }

    /**
     * Only upgrades that change target eligibility need their own private cursor map.
     * Fortune, silk touch, smelting, speed and energy upgrades only change processing
     * or drops and can safely share the same world-column cursor.
     */
    public boolean usesSharedColumnHeights() {
        return tier(ExcavatorUpgradeType.FILTER) <= 0
                && tier(ExcavatorUpgradeType.FLUID_IGNORE) <= 0;
    }

    public ItemStack excavationTool(ServerLevel level) {
        int luckLevel = luckLevel();
        boolean silkTouch = hasSilkTouch();
        if (!cachedLootTool.isEmpty()
                && cachedLootToolLuckLevel == luckLevel
                && cachedLootToolSilkTouch == silkTouch) {
            return cachedLootTool;
        }

        ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
        if (silkTouch) {
            tool.enchant(level.registryAccess().holderOrThrow(Enchantments.SILK_TOUCH), 1);
        } else if (luckLevel > 0) {
            tool.enchant(level.registryAccess().holderOrThrow(Enchantments.FORTUNE), luckLevel);
        }
        cachedLootTool = tool;
        cachedLootToolLuckLevel = luckLevel;
        cachedLootToolSilkTouch = silkTouch;
        return cachedLootTool;
    }

    public void saveFilter(CompoundTag tag) {
        CompoundTag filterTag = new CompoundTag();
        for (int i = 0; i < MAX_FILTER_SLOTS; i++) {
            Block block = filteredBlocks[i];
            if (block != null) {
                filterTag.putString(TAG_FILTER_SLOT_PREFIX + i, BuiltInRegistries.BLOCK.getKey(block).toString());
            }
        }
        tag.put(TAG_BLOCK_FILTER, filterTag);
    }

    public void loadFilter(CompoundTag tag) {
        Arrays.fill(filteredBlocks, null);
        if (tag.contains(TAG_BLOCK_FILTER, Tag.TAG_COMPOUND)) {
            CompoundTag filterTag = tag.getCompound(TAG_BLOCK_FILTER);
            for (int i = 0; i < MAX_FILTER_SLOTS; i++) {
                String raw = filterTag.getString(TAG_FILTER_SLOT_PREFIX + i);
                if (raw.isBlank()) continue;
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id != null) {
                    int filterSlot = i;
                    BuiltInRegistries.BLOCK.getOptional(id)
                            .ifPresent(block -> filteredBlocks[filterSlot] = block);
                }
            }
        }
        rebuildFilterLookup();
    }

    public void deserializeInventory(CompoundTag inventoryTag, HolderLookup.Provider registries) {
        inventory.deserializeNBT(registries, inventoryTag);
        refreshCache();
    }
}
