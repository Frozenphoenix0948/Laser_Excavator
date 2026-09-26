package de.balto.laserexcavator.block.blockentities;

import de.balto.laserexcavator.block.excavator.ExcavationScanner;
import de.balto.laserexcavator.block.excavator.ExcavatorArea;
import de.balto.laserexcavator.block.excavator.ExcavatorAutomationItemHandler;
import de.balto.laserexcavator.block.excavator.ExcavatorAutoSmelter;
import de.balto.laserexcavator.block.excavator.ExcavatorLootCache;
import de.balto.laserexcavator.block.excavator.ExcavatorFastBlockRemoval;
import de.balto.laserexcavator.block.excavator.ExcavatorDeliveryManager;
import de.balto.laserexcavator.block.excavator.ExcavatorEnergyStorage;
import de.balto.laserexcavator.block.excavator.ExcavatorFuelManager;
import de.balto.laserexcavator.block.excavator.ExcavatorColumnState;
import de.balto.laserexcavator.block.excavator.ExcavatorUpgradeManager;
import de.balto.laserexcavator.block.excavator.ExcavatorBlock;
import de.balto.laserexcavator.block.excavator.ExcavatorBlockSyncBatcher;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import de.balto.laserexcavator.block.excavator.ExcavatorSharedColumnHeights;
import de.balto.laserexcavator.block.excavator.ExcavatorWorkPhase;
import de.balto.laserexcavator.block.excavator.ExtractOnlyItemHandler;
import de.balto.laserexcavator.screen.ExcavatorMenu;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeType;
import de.balto.laserexcavator.network.excavator.ExcavatorNetworking;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static de.balto.laserexcavator.block.excavator.ExcavatorUpgradeManager.isFluidBlock;

public class ExcavatorBlockEntity extends BlockEntity implements MenuProvider {
    private static final int PERSISTENCE_VERSION = 3;

    private static final String TAG_DATA_VERSION = "DataVersion";
    private static final String TAG_SELECTION_WIDTH = "SelectionWidth";
    private static final String TAG_SELECTION_HEIGHT = "SelectionHeight";
    private static final String TAG_SELECTION_LENGTH = "SelectionLength";
    private static final String TAG_SCAN_STATE = "ScanState";
    private static final String TAG_SCAN_INDEX = "ScanIndex";
    private static final String TAG_HIGHEST_SURFACE_Y = "HighestSurfaceY";
    private static final String TAG_BLOCKS_EXCAVATED = "BlocksExcavated";
    private static final String TAG_ACTIVE_COLUMN_COUNT = "ActiveColumnCount";
    private static final String TAG_PENDING_ITEM_COUNT = "PendingItemCount";
    private static final String TAG_WORK_PHASE = "WorkPhase";
    private static final String TAG_PHASE_START_GAME_TIME = "PhaseStartGameTime";
    private static final String TAG_LASER_POWERED_TICKS = "LaserPoweredTicks";
    private static final String TAG_LASER_ENERGY_SPENT = "LaserEnergySpent";
    private static final String TAG_FILTER_SKIP_COOLDOWN_END_TICK = "FilterSkipCooldownEndTick";
    private static final String TAG_ACTIVE_TARGET = "ActiveTarget";
    private static final String TAG_SURFACE_HEIGHTS = "SurfaceHeights";
    private static final String TAG_CURRENT_HEIGHTS = "CurrentHeights";
    private static final String TAG_ACTIVE_COLUMNS = "ActiveColumns";
    private static final String TAG_EXCAVATION_INITIALIZED = "ExcavationInitialized";
    private static final String TAG_OUTPUT_INVENTORY = "OutputInventory";
    private static final String TAG_UPGRADE_INVENTORY = "UpgradeInventory";
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_CLIENT_SYNC_ONLY = "ClientSyncOnly";
    private static final String TAG_INVENTORY_SIZE = "Size";
    public static final int UPGRADE_SLOTS = ExcavatorUpgradeManager.MAX_UPGRADE_SLOTS;

    public static final int MAX_FILTER_SLOTS = ExcavatorUpgradeManager.MAX_FILTER_SLOTS;

    public static final int OUTPUT_COLUMNS = 9;
    public static final int OUTPUT_ROWS = 3;
    public static final int OUTPUT_SLOTS = OUTPUT_COLUMNS * OUTPUT_ROWS;

    private ExcavatorDeliveryManager deliveries;
    private int syncedPendingDeliveryCount;

    private long lastStorageWaitCheckedRevision = -1L;

    /**
     * Exact target/drop set that most recently hit storage backpressure. Keeping
     * it lets the machine test the real requirement and automatically restart the
     * same laser as soon as sufficient space is available. These fields are only
     * transient; after a world reload a STORAGE_FULL machine simply reselects a
     * target and rebuilds the same state if the storage is still full.
     */
    private @Nullable BlockPos storageBlockedTarget;
    private int storageBlockedColumnIndex = -1;
    private @Nullable BlockState storageBlockedState;
    private List<ItemStack> storageBlockedDrops = List.of();

    private int selectionWidth = LaserExcavatorConfig.clampSelectionSize(LaserExcavatorConfig.DEFAULT_WIDTH.get(), LaserExcavatorConfig.areaSize(0));
    private int selectionHeight = LaserExcavatorConfig.clampSelectionSize(LaserExcavatorConfig.DEFAULT_HEIGHT.get(), LaserExcavatorConfig.MAX_VERTICAL_SIZE.get());
    private int selectionLength = LaserExcavatorConfig.clampSelectionSize(LaserExcavatorConfig.DEFAULT_LENGTH.get(), LaserExcavatorConfig.areaSize(0));

    private ExcavatorScanState scanState = ExcavatorScanState.IDLE;
    private final ExcavatorColumnState columns = new ExcavatorColumnState();
    private int blocksExcavated = 0;

    private int activeColumnIndex = -1;
    private BlockPos activeTarget;
    private ExcavatorWorkPhase workPhase = ExcavatorWorkPhase.NONE;
    private long phaseStartGameTime = 0L;
    /** Powered ticks accumulated by the current laser shot. Energy starvation does not advance this. */
    private int laserPoweredTicks = 0;
    /** FE already paid toward the current block. Persisted so unload/reload cannot discount a shot. */
    private int laserEnergySpent = 0;
    private boolean laserVisualNeedsRestart = false;
    private long filterSkipCooldownEndTick = Long.MIN_VALUE;

    // Reused mutable positions avoid short-lived BlockPos allocations.
    private final BlockPos.MutableBlockPos targetLookupCursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos chunkCheckCursor = new BlockPos.MutableBlockPos();

    // Cached until dimensions or facing change.
    private @Nullable ExcavatorArea cachedExcavatorArea;
    private @Nullable Direction cachedAreaFacing;
    private int cachedAreaWidth = -1;
    private int cachedAreaHeight = -1;
    private int cachedAreaLength = -1;

    private final ExcavatorUpgradeManager upgrades;
    private final ExcavatorFuelManager fuel;

    /**
     * Development-only stress/grid bypass. While enabled the machine does not
     * consume FE, run the fuel generator, perform storage-capacity checks, or
     * enqueue real item deliveries. Loot is still generated so transport visuals
     * keep their normal representative item/block. This flag is intentionally
     * transient and is never persisted.
     */
    private boolean developmentBenchmarkResourceBypass;

    private final ItemStackHandler outputInventory = new ItemStackHandler(OUTPUT_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            boolean internalDeliveryChange = deliveries != null
                    && deliveries.isInternalDeliveryInsertionInProgress();
            if (deliveries != null) {
                if (internalDeliveryChange) deliveries.onInternalDeliverySlotChanged(slot);
                else deliveries.onOutputSlotChanged(slot);
            }

            // Due deliveries are persisted once after the whole due bucket has
            // been processed. External mutations still need an immediate dirty
            // mark because no batch-level delivery callback follows them.
            if (!internalDeliveryChange) setChanged();
        }
    };

    private final IItemHandler externalOutputHandler = new ExtractOnlyItemHandler(outputInventory);

    /**
     * Combined automation view used on the top, sides, and unsided capability.
     * Output remains extract-only while the virtual final slot accepts furnace fuel.
     */
    private final IItemHandler externalAutomationHandler;

    /**
     * Standard NeoForge/Forge Energy input. External systems may insert power but
     * cannot extract it back out of the excavator.
     */
    private final ExcavatorEnergyStorage energyStorage;

    private final ContainerData menuData = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case ExcavatorMenu.DATA_WIDTH -> selectionWidth;
                case ExcavatorMenu.DATA_HEIGHT -> selectionHeight;
                case ExcavatorMenu.DATA_LENGTH -> selectionLength;
                case ExcavatorMenu.DATA_STATE -> scanState.id();
                case ExcavatorMenu.DATA_SCAN_INDEX -> columns.scanIndex();
                case ExcavatorMenu.DATA_TOTAL_COLUMNS -> getTotalColumns();
                case ExcavatorMenu.DATA_FORCE_FIELD_Y -> getForceFieldY();
                case ExcavatorMenu.DATA_BLOCKS_EXCAVATED -> blocksExcavated;
                case ExcavatorMenu.DATA_PENDING_ITEMS -> getPendingItemCount();
                case ExcavatorMenu.DATA_MAX_AREA_SIZE -> getMaxHorizontalSize();
                case ExcavatorMenu.DATA_FILTER_CAPACITY -> getFilterCapacity();
                case ExcavatorMenu.DATA_ENERGY_STORED -> getEnergyStored();
                case ExcavatorMenu.DATA_ENERGY_CAPACITY -> getEnergyCapacity();
                case ExcavatorMenu.DATA_ENERGY_USAGE -> getCurrentEnergyUsagePerTick();
                case ExcavatorMenu.DATA_ENERGY_PER_BLOCK -> getEnergyPerBlock();
                case ExcavatorMenu.DATA_FUEL_BURN_REMAINING -> fuel.burnTicksRemaining();
                case ExcavatorMenu.DATA_FUEL_BURN_TOTAL -> fuel.burnTicksTotal();
                case ExcavatorMenu.DATA_OVERHEATING -> isOverheating() ? 1 : 0;
                default -> {
                    int filterSlot = index - ExcavatorMenu.DATA_FILTER_START;
                    yield filterSlot >= 0 && filterSlot < MAX_FILTER_SLOTS
                            ? getFilterBlockRegistryId(filterSlot)
                            : 0;
                }
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case ExcavatorMenu.DATA_WIDTH -> setSelectionWidth(value);
                case ExcavatorMenu.DATA_HEIGHT -> setSelectionHeight(value);
                case ExcavatorMenu.DATA_LENGTH -> setSelectionLength(value);
            }
        }

        @Override
        public int getCount() {
            return ExcavatorMenu.DATA_COUNT;
        }
    };

    public ExcavatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EXCAVATOR.get(), pos, state);
        deliveries = new ExcavatorDeliveryManager(outputInventory);
        energyStorage = new ExcavatorEnergyStorage(
                () -> level == null ? Long.MIN_VALUE : level.getGameTime(),
                this::setChanged
        );
        fuel = new ExcavatorFuelManager(energyStorage, this::setChanged);
        externalAutomationHandler = new ExcavatorAutomationItemHandler(outputInventory, fuel.inventory());
        upgrades = new ExcavatorUpgradeManager(
                () -> level != null,
                this::isBusy,
                columns::isExcavationInitialized,
                this::onConfigurationChanged,
                this::onFluidIgnoreModeChanged,
                this::clampSelectionToAreaUpgrade,
                this::setChanged,
                this::setChangedAndSync
        );
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ExcavatorBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Profiler heartbeat. This increments for every loaded excavator tick,
        // independent of scanning/excavation state, so a running profiler can
        // never silently look completely dead while this block entity is ticking.
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.EXCAVATOR_SERVER_TICKS);

        if (!blockEntity.developmentBenchmarkResourceBypass) {
            blockEntity.fuel.tick();

            // Delivery processing is independent from excavation state. A completed
            // quarry may still have material physically travelling toward the machine.
            long deliveryProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.DELIVERY_PROCESSING);
            blockEntity.processDueDeliveries(serverLevel);
            ExcavatorProfiler.end(ExcavatorProfiler.Section.DELIVERY_PROCESSING, deliveryProfile);
        }

        // The base machine is not rated for Nether ambient heat. Keep fuel charging,
        // deliveries and visual cleanup alive, but freeze scanning/excavation work until
        // the Nether Cooling upgrade is installed.
        if (blockEntity.isOverheating()) return;

        switch (blockEntity.scanState) {
            case SCANNING -> blockEntity.scanColumnsBatch(serverLevel);
            case EXCAVATING -> {
                long excavationProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXCAVATION_TICK);
                blockEntity.tickExcavation(serverLevel);
                ExcavatorProfiler.end(ExcavatorProfiler.Section.EXCAVATION_TICK, excavationProfile);
            }
            case STORAGE_FULL -> {
                long excavationProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXCAVATION_TICK);
                blockEntity.retryStorageBlockedTarget(serverLevel);
                ExcavatorProfiler.end(ExcavatorProfiler.Section.EXCAVATION_TICK, excavationProfile);
            }
        }
    }

    public ContainerData getMenuData() {
        return menuData;
    }

    public ItemStackHandler getOutputInventory() {
        return outputInventory;
    }

    public ItemStackHandler getUpgradeInventory() {
        return upgrades.inventory();
    }

    public int getUpgradeSlotCount() {
        return upgrades.activeSlotCount();
    }

    public ItemStackHandler getFuelInventory() {
        return fuel.inventory();
    }

    public boolean isFuelSlotEnabled() {
        return fuel.isEnabled();
    }

    public boolean isOverheating() {
        return level != null
                && Level.NETHER.equals(level.dimension())
                && !upgrades.hasNetherCooling();
    }

    public static boolean isFuel(ItemStack stack) {
        return ExcavatorFuelManager.isFuel(stack);
    }

    public static boolean isExcavatorUpgrade(ItemStack stack) {
        return ExcavatorUpgradeManager.isExcavatorUpgrade(stack);
    }

    public static @Nullable ExcavatorUpgradeType getUpgradeType(ItemStack stack) {
        return ExcavatorUpgradeManager.upgradeType(stack);
    }

    public boolean canInstallUpgrade(int slot, ItemStack stack) {
        return upgrades.canInstall(slot, stack);
    }

    public static boolean upgradesConflict(@Nullable ExcavatorUpgradeType first, @Nullable ExcavatorUpgradeType second) {
        return ExcavatorUpgradeManager.upgradesConflict(first, second);
    }

    public static boolean isUpgradeHotSwappable(@Nullable ExcavatorUpgradeType type) {
        return ExcavatorUpgradeManager.isHotSwappable(type);
    }

    public int getSpeedUpgradeTier() {
        return upgrades.speedTier();
    }

    public int getLaserWorkDurationTicks() {
        return LaserExcavatorConfig.speedInterval(upgrades.speedTier());
    }

    public boolean hasAutoSmeltingUpgrade() {
        return upgrades.hasAutoSmelting();
    }

    public int getLuckUpgradeTier() {
        return upgrades.luckTier();
    }

    public int getLuckLevel() {
        return upgrades.luckLevel();
    }

    public boolean hasSilkTouchUpgrade() {
        return upgrades.hasSilkTouch();
    }

    public boolean hasFluidIgnoreUpgrade() {
        return upgrades.hasFluidIgnore();
    }

    public int getFilterCapacity() {
        return upgrades.filterCapacity();
    }

    public int getMaxHorizontalSize() {
        return upgrades.maxHorizontalSize();
    }

    public @Nullable Block getFilterBlock(int slot) {
        return upgrades.filterBlock(slot);
    }

    public int getFilterBlockRegistryId(int slot) {
        return upgrades.filterBlockRegistryId(slot);
    }

    public boolean setFilterBlock(int slot, @Nullable Block block) {
        return upgrades.setFilterBlock(slot, block);
    }

    private boolean shouldIgnoreTarget(ServerLevel level, BlockPos pos, BlockState state) {
        return upgrades.shouldIgnoreTarget(level, pos, state, LaserExcavatorConfig.unbreakableBlocks());
    }

    private boolean shouldIgnoreTarget(ServerLevel level, BlockPos pos, BlockState state, Set<Block> unbreakableBlocks) {
        return upgrades.shouldIgnoreTarget(level, pos, state, unbreakableBlocks);
    }

    private boolean isFilterCooldownTarget(ServerLevel level, BlockPos pos, BlockState state, Set<Block> unbreakableBlocks) {
        return upgrades.isFilterCooldownTarget(level, pos, state, unbreakableBlocks);
    }

    private boolean usesSharedColumnHeights() {
        return upgrades.usesSharedColumnHeights();
    }

    /**
     * Registers this excavator's whole X/Z footprint once per runtime/load. The
     * persisted local height array remains a reload seed, while all hot target
     * selection reads come from the shared server-level cursor afterwards.
     */
    private void ensureSharedColumnsRegistered(ServerLevel level) {
        if (columns.areSharedColumnsRegistered() || !usesSharedColumnHeights() || !columns.isExcavationInitialized()) return;

        int total = getTotalColumns();
        if (columns.currentHeightCount() != total) return;

        ExcavatorArea area = getExcavatorArea();
        boolean newOwnerRegistration = ExcavatorSharedColumnHeights.beginOwnerRegistration(
                level,
                worldPosition.asLong(),
                area.min().getX(), area.max().getX(),
                area.min().getZ(), area.max().getZ(),
                area.min().getY(), area.max().getY()
        );
        int[] rebuiltActive = new int[total];
        int rebuiltCount = 0;

        for (int i = 0; i < total; i++) {
            int worldX = area.worldXForColumn(i);
            int worldZ = area.worldZForColumn(i);
            int sharedY = newOwnerRegistration
                    ? ExcavatorSharedColumnHeights.register(
                            level,
                            worldX,
                            worldZ,
                            columns.currentHeight(i),
                            area.min().getY(),
                            area.max().getY()
                    )
                    : ExcavatorSharedColumnHeights.getCurrentY(level, worldX, worldZ);

            columns.setCurrentHeight(i, sharedY);
            if (sharedY != ExcavationScanner.NO_SURFACE && sharedY >= area.min().getY()) {
                // Keep columns whose shared cursor is still above this excavator too.
                // They become selectable automatically once another overlapping
                // excavator advances the shared cursor into this machine's Y range.
                rebuiltActive[rebuiltCount++] = i;
            }
        }

        columns.replaceActiveColumns(rebuiltActive, rebuiltCount);
        columns.setSharedColumnsRegistered(true);
    }

    private void unregisterSharedColumns() {
        if (level instanceof ServerLevel serverLevel) {
            ExcavatorSharedColumnHeights.unregisterOwner(serverLevel, worldPosition.asLong());
        }
        columns.setSharedColumnsRegistered(false);
    }

    /**
     * Fluid Ignore changes the target-eligibility mode but not the scanned area.
     * Switch between shared and private column cursors in-place so the running
     * excavation can continue without discarding its scan/progress.
     */
    private void onFluidIgnoreModeChanged() {
        if (!(level instanceof ServerLevel serverLevel) || !columns.isExcavationInitialized()) {
            setChangedAndSync();
            return;
        }

        if (usesSharedColumnHeights()) {
            // Rejoin the shared cursor system from the current private heights.
            // Registration also reconciles this excavator with overlapping owners.
            ensureSharedColumnsRegistered(serverLevel);
        } else {
            detachFromSharedColumns(serverLevel);
        }
        setChangedAndSync();
    }

    private void detachFromSharedColumns(ServerLevel level) {
        if (!columns.areSharedColumnsRegistered()) return;

        int total = getTotalColumns();
        if (columns.currentHeightCount() != total) {
            unregisterSharedColumns();
            return;
        }

        ExcavatorArea area = getExcavatorArea();
        int[] rebuiltActive = new int[total];
        int rebuiltCount = 0;

        for (int i = 0; i < total; i++) {
            int worldX = area.worldXForColumn(i);
            int worldZ = area.worldZForColumn(i);
            int sharedY = ExcavatorSharedColumnHeights.getCurrentY(level, worldX, worldZ);

            // Shared cursors may belong to an overlapping excavator with a taller
            // range. Private mode must stay inside this excavator's own Y range.
            int localY = sharedY == ExcavationScanner.NO_SURFACE
                    ? ExcavationScanner.NO_SURFACE
                    : Math.min(sharedY, area.max().getY());
            columns.setCurrentHeight(i, localY);

            if (localY != ExcavationScanner.NO_SURFACE && localY >= area.min().getY()) {
                rebuiltActive[rebuiltCount++] = i;
            }
        }

        columns.replaceActiveColumns(rebuiltActive, rebuiltCount);
        unregisterSharedColumns();
    }

    @Override
    public void setRemoved() {
        unregisterSharedColumns();
        super.setRemoved();
    }

    private int sharedHeightForColumn(
            ServerLevel level,
            ExcavatorArea area,
            int columnIndex,
            int worldX,
            int worldZ
    ) {
        int sharedY = ExcavatorSharedColumnHeights.getCurrentY(level, worldX, worldZ);
        int localY = columns.currentHeight(columnIndex);
        if (sharedY != localY) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_STALE_LOCAL_AVOIDED);
            columns.setCurrentHeight(columnIndex, sharedY);
        }
        return sharedY;
    }

    private int repairInvalidSharedTarget(
            ServerLevel level,
            ExcavatorArea area,
            int columnIndex,
            int worldX,
            int worldZ,
            int invalidY
    ) {
        int sharedMinY = ExcavatorSharedColumnHeights.getRegisteredMinY(
                level, worldX, worldZ, area.min().getY());
        int recoveredY = findNextTargetY(level, worldX, worldZ, invalidY - 1, sharedMinY);
        int actualY = ExcavatorSharedColumnHeights.advance(
                level, worldX, worldZ, invalidY, recoveredY);
        columns.setCurrentHeight(columnIndex, actualY);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REPAIRS);
        return actualY;
    }

    private ItemStack getExcavationTool(ServerLevel level) {
        return upgrades.excavationTool(level);
    }

    private void clampSelectionToAreaUpgrade() {
        int maxHorizontalSize = getMaxHorizontalSize();
        int width = Math.min(selectionWidth, maxHorizontalSize);
        int length = Math.min(selectionLength, maxHorizontalSize);
        if (width == selectionWidth && length == selectionLength) return;

        selectionWidth = width;
        selectionLength = length;
        onConfigurationChanged();
    }

    /**
     * Sided automation-facing handler used by the NeoForge item capability.
     * The bottom face is output-only. The top, horizontal faces, and unsided
     * access may also insert valid furnace fuel into the dedicated fuel slot.
     * Output extraction remains available from every face for pipe compatibility.
     */
    public IItemHandler getExternalItemHandler(@Nullable Direction side) {
        return side == Direction.DOWN ? externalOutputHandler : externalAutomationHandler;
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public int getEnergyStored() {
        return energyStorage.getEnergyStored();
    }

    public int getEnergyCapacity() {
        return energyStorage.getMaxEnergyStored();
    }

    /**
     * Fills internal FE storage to capacity without applying the external receive-rate limit.
     * Used by development stress tooling; the transient fill is not persisted.
     *
     * Returns the FE added to reach the configured capacity.
     */
    public int debugFillEnergyToCapacity() {
        int capacity = getEnergyCapacity();
        int before = getEnergyStored();
        if (before >= capacity) return 0;
        energyStorage.loadStoredEnergy(capacity);
        return capacity - before;
    }

    /**
     * Enables the lightweight development benchmark mode used by isolated stress
     * grid setups. The flag is runtime-only so ordinary saved
     * excavators can never become permanently free/void-output machines.
     */
    public void debugSetBenchmarkResourceBypass(boolean enabled) {
        developmentBenchmarkResourceBypass = enabled;
    }

    public int getEnergyPerBlock() {
        int tier = Math.max(0, Math.min(5, upgrades.tier(ExcavatorUpgradeType.ENERGY_EFFICIENCY)));
        return LaserExcavatorConfig.energyPerBlock(tier);
    }

    public int getCurrentEnergyUsagePerTick() {
        if (developmentBenchmarkResourceBypass) return 0;
        if (scanState != ExcavatorScanState.EXCAVATING || isOverheating()) return 0;
        if (filterSkipCooldownEndTick != Long.MIN_VALUE) return 0;
        return getRequiredEnergyForNextPoweredTick();
    }

    private boolean hasEnergyForLaserTick() {
        if (developmentBenchmarkResourceBypass) return true;
        int required = getRequiredEnergyForNextPoweredTick();
        return getEnergyStored() >= required;
    }

    private boolean consumeLaserEnergyTick() {
        if (developmentBenchmarkResourceBypass) return true;
        int required = getRequiredEnergyForNextPoweredTick();
        if (!energyStorage.consumeInternal(required)) return false;
        laserEnergySpent = (int) Math.min(Integer.MAX_VALUE, (long) laserEnergySpent + (long) required);
        return true;
    }

    /**
     * Spreads the remaining per-block FE budget over the remaining powered work
     * ticks. This keeps total block cost independent of Speed while still making
     * faster tiers demand more FE/t. Live Speed/Efficiency swaps are handled by
     * redistributing the remaining unpaid energy over the new remaining duration.
     */
    private int getRequiredEnergyForNextPoweredTick() {
        int totalCost = getEnergyPerBlock();
        int remainingEnergy = Math.max(0, totalCost - laserEnergySpent);
        if (remainingEnergy <= 0) return 0;

        int duration = Math.max(1, getLaserWorkDurationTicks());
        int completedTicks = workPhase == ExcavatorWorkPhase.LASER ? Math.max(0, laserPoweredTicks) : 0;
        int remainingTicks = Math.max(1, duration - completedTicks);
        return (int) Math.min(
                Integer.MAX_VALUE,
                ((long) remainingEnergy + (long) remainingTicks - 1L) / (long) remainingTicks
        );
    }

    private void startLaserVisual(ServerLevel level, BlockPos target) {
        // Lifetime is client-local. A resume simply refreshes this target's beam on
        // each observing client, where duplicate target beams are coalesced.
        ExcavatorNetworking.sendLaserStart(level, worldPosition, target);
    }

    public int getSelectionWidth() {
        return selectionWidth;
    }

    public int getSelectionHeight() {
        return selectionHeight;
    }

    public int getSelectionLength() {
        return selectionLength;
    }

    public ExcavatorScanState getScanState() {
        return scanState;
    }


    public int getTotalColumns() {
        return selectionWidth * selectionLength;
    }

    public int getHighestSurfaceY() {
        return columns.highestSurfaceY();
    }

    public int getForceFieldY() {
        return worldPosition.getY() + LaserExcavatorConfig.FORCE_FIELD_HEIGHT.get();
    }

    public int getBlocksExcavated() {
        return blocksExcavated;
    }


    public int getPendingItemCount() {
        return level != null && level.isClientSide ? syncedPendingDeliveryCount : deliveries.pendingCount();
    }


    public boolean hasPendingDeliveries() {
        return level != null && level.isClientSide ? syncedPendingDeliveryCount > 0 : deliveries.hasPending();
    }





    public ExcavatorArea getExcavatorArea() {
        BlockState state = getBlockState();
        Direction facing = state.hasProperty(ExcavatorBlock.FACING)
                ? state.getValue(ExcavatorBlock.FACING)
                : Direction.NORTH;

        if (cachedExcavatorArea != null
                && cachedAreaFacing == facing
                && cachedAreaWidth == selectionWidth
                && cachedAreaHeight == selectionHeight
                && cachedAreaLength == selectionLength) {
            return cachedExcavatorArea;
        }

        cachedAreaFacing = facing;
        cachedAreaWidth = selectionWidth;
        cachedAreaHeight = selectionHeight;
        cachedAreaLength = selectionLength;
        cachedExcavatorArea = ExcavatorArea.create(
                worldPosition,
                facing,
                selectionWidth,
                selectionHeight,
                selectionLength
        );
        return cachedExcavatorArea;
    }



    public void setSelectionWidth(int selectionWidth) {
        int clamped = LaserExcavatorConfig.clampSelectionSize(selectionWidth, getMaxHorizontalSize());
        if (this.selectionWidth == clamped) return;
        if (isBusy()) return;

        this.selectionWidth = clamped;
        onConfigurationChanged();
    }

    public void setSelectionHeight(int selectionHeight) {
        int clamped = LaserExcavatorConfig.clampSelectionSize(selectionHeight, LaserExcavatorConfig.MAX_VERTICAL_SIZE.get());
        if (this.selectionHeight == clamped) return;
        if (isBusy()) return;

        this.selectionHeight = clamped;
        onConfigurationChanged();
    }

    public void setSelectionLength(int selectionLength) {
        int clamped = LaserExcavatorConfig.clampSelectionSize(selectionLength, getMaxHorizontalSize());
        if (this.selectionLength == clamped) return;
        if (isBusy()) return;

        this.selectionLength = clamped;
        onConfigurationChanged();
    }

    private boolean isBusy() {
        return scanState == ExcavatorScanState.SCANNING
                || scanState == ExcavatorScanState.EXCAVATING
                || scanState == ExcavatorScanState.STORAGE_FULL
                || hasPendingDeliveries();
    }

    private void onConfigurationChanged() {
        resetAllWorkData();
        setChangedAndSync();
    }

    public void beginScan() {
        if (!(level instanceof ServerLevel)) return;
        if (isOverheating() || isBusy()) return;

        unregisterSharedColumns();
        clampSelectionToAreaUpgrade();
        columns.beginScan(getTotalColumns());
        blocksExcavated = 0;
        clearActiveAnimation();
        enterScanningState();
    }

    private void scanColumnsBatch(ServerLevel level) {
        long scannerProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.SCANNER);
        int totalColumns = getTotalColumns();
        columns.ensureScanStorage(totalColumns);

        ExcavatorArea area = getExcavatorArea();
        int scannedThisTick = 0;
        int maxColumnsThisTick = LaserExcavatorConfig.SCAN_COLUMNS_PER_TICK.get();

        while (columns.scanIndex() < totalColumns && scannedThisTick < maxColumnsThisTick) {
            int columnIndex = columns.scanIndex();
            int worldX = area.worldXForColumn(columnIndex);
            int worldZ = area.worldZForColumn(columnIndex);

            chunkCheckCursor.set(worldX, worldPosition.getY(), worldZ);
            if (!level.hasChunkAt(chunkCheckCursor)) {
                break;
            }

            int surfaceY = ExcavationScanner.findSurfaceY(
                    level,
                    worldX,
                    worldZ,
                    area.min().getY(),
                    area.max().getY()
            );

            columns.recordScannedSurface(surfaceY);
            scannedThisTick++;
        }

        if (columns.scanIndex() >= totalColumns) {
            enterReadyAfterScan();
        } else if (scannedThisTick > 0) {
            setChanged();
        }

        ExcavatorProfiler.add(ExcavatorProfiler.Counter.SCAN_COLUMNS, scannedThisTick);
        ExcavatorProfiler.end(ExcavatorProfiler.Section.SCANNER, scannerProfile);
    }

    public void beginExcavation() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (isOverheating()) return;
        if (scanState != ExcavatorScanState.READY && scanState != ExcavatorScanState.STORAGE_FULL) return;

        if (!columns.isExcavationInitialized()) {
            initializeExcavation(serverLevel);
        }
        ensureSharedColumnsRegistered(serverLevel);

        if (columns.activeCount() <= 0 && filterSkipCooldownEndTick == Long.MIN_VALUE) {
            finishExcavation();
            return;
        }

        enterExcavatingState();
    }

    public void pauseExcavation() {
        if (scanState != ExcavatorScanState.EXCAVATING && scanState != ExcavatorScanState.STORAGE_FULL) return;
        enterReadyAfterPause();
    }

    private void enterScanningState() {
        scanState = ExcavatorScanState.SCANNING;
        setChangedAndSync();
    }

    private void enterReadyAfterScan() {
        scanState = ExcavatorScanState.READY;
        setChangedAndSync();
    }

    private void enterExcavatingState() {
        scanState = ExcavatorScanState.EXCAVATING;
        clearActiveAnimation();
        setChangedAndSync();
    }

    private void enterReadyAfterPause() {
        scanState = ExcavatorScanState.READY;
        clearStorageWaitState();
        clearActiveAnimation();
        setChangedAndSync();
    }

    private void resumeExcavationAfterStorageWait() {
        clearStorageWaitState();
        scanState = ExcavatorScanState.EXCAVATING;
        setChangedAndSync();
    }

    private void initializeExcavation(ServerLevel level) {
        int total = getTotalColumns();
        // Once excavation starts the scanned surface becomes the mutable logical
        // surface. Transfer the array instead of cloning it so large excavation areas
        // do not retain two full height maps for the same columns.
        columns.beginExcavationState(total);

        ExcavatorArea area = getExcavatorArea();
        for (int i = 0; i < total; i++) {
            int y = columns.currentHeight(i);
            if (y == ExcavationScanner.NO_SURFACE) continue;

            int worldX = area.worldXForColumn(i);
            int worldZ = area.worldZForColumn(i);

            // Never make initialization load an excavation chunk. Keep the scanned
            // height and retain this column as active; target selection will resolve
            // it normally once its chunk is loaded again.
            chunkCheckCursor.set(worldX, worldPosition.getY(), worldZ);
            if (!level.hasChunkAt(chunkCheckCursor)) {
                columns.addActiveColumn(i);
                continue;
            }

            int resolved = findNextTargetY(level, worldX, worldZ, y, area.min().getY());
            columns.setCurrentHeight(i, resolved);
            if (resolved != ExcavationScanner.NO_SURFACE) {
                columns.addActiveColumn(i);
            }
        }

        columns.trimActiveColumns();
        columns.markExcavationInitialized();
        ensureSharedColumnsRegistered(level);
        blocksExcavated = 0;
    }

    private void tickExcavation(ServerLevel level) {
        if (!columns.isExcavationInitialized()) {
            initializeExcavation(level);
        }
        ensureSharedColumnsRegistered(level);

        if (columns.activeCount() <= 0 && filterSkipCooldownEndTick == Long.MIN_VALUE) {
            finishExcavation();
            return;
        }

        if (filterSkipCooldownEndTick != Long.MIN_VALUE) {
            if (level.getGameTime() < filterSkipCooldownEndTick) return;
            filterSkipCooldownEndTick = Long.MIN_VALUE;
            if (columns.activeCount() <= 0) {
                finishExcavation();
                return;
            }
        }

        if (workPhase == ExcavatorWorkPhase.LASER) {
            // A started machine remains in EXCAVATING state when power runs out.
            // The current shot simply stops accumulating powered work ticks until
            // enough FE is supplied again.
            if (!consumeLaserEnergyTick()) {
                laserVisualNeedsRestart = true;
                return;
            }

            if (laserVisualNeedsRestart && activeTarget != null) {
                // Clients own the cosmetic beam lifetime, so a resumed shot sends one
                // refresh event and each client coalesces it with the same target beam.
                startLaserVisual(level, activeTarget);
                laserVisualNeedsRestart = false;
            }

            laserPoweredTicks++;
            if (laserPoweredTicks >= getLaserWorkDurationTicks()) {
                completeActiveTarget(level);

                // Start the next shot immediately so speed upgrades scale exactly.
                if (scanState == ExcavatorScanState.EXCAVATING
                        && workPhase == ExcavatorWorkPhase.NONE) {
                    if (columns.activeCount() <= 0) {
                        finishExcavation();
                    } else if (hasEnergyForLaserTick()) {
                        selectRandomTarget(level);
                    }
                }
            }
            return;
        }

        // Start may be pressed with an empty buffer. Do not start a visual laser
        // until at least one powered tick is available; just wait here.
        if (!hasEnergyForLaserTick()) return;
        selectRandomTarget(level);
    }

    private void selectRandomTarget(ServerLevel level) {
        long targetProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.TARGET_SELECTION);
        try {
            RandomSource random = level.getRandom();
            ExcavatorArea area = getExcavatorArea();
            boolean sharedHeights = usesSharedColumnHeights();
            if (sharedHeights) ensureSharedColumnsRegistered(level);
            else ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_PRIVATE_PATH_SELECTIONS);

            int aboveLocalRangeSkips = 0;
            int maxAboveLocalRangeSkips = Math.max(1, Math.min(columns.activeCount(), 16));

            while (columns.activeCount() > 0) {
                int activeSlot = findLoadedActiveColumnSlot(level, area, random.nextInt(columns.activeCount()));
                if (activeSlot < 0) return;

                int columnIndex = columns.activeColumnAt(activeSlot);
                int worldX = area.worldXForColumn(columnIndex);
                int worldZ = area.worldZForColumn(columnIndex);
                int y = resolveSelectableTargetY(level, area, columnIndex, worldX, worldZ, sharedHeights);

                if (y == ExcavationScanner.NO_SURFACE || y < area.min().getY()) {
                    columns.removeActiveColumnAt(activeSlot);
                    continue;
                }

                if (sharedHeights && y > area.max().getY()) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_OUT_OF_RANGE_SKIPS);
                    if (++aboveLocalRangeSkips >= maxAboveLocalRangeSkips) return;
                    continue;
                }

                targetLookupCursor.set(worldX, y, worldZ);
                BlockState targetState = level.getBlockState(targetLookupCursor);
                if (isFilterCooldownTarget(level, targetLookupCursor, targetState, LaserExcavatorConfig.unbreakableBlocks())) {
                    if (skipFilteredTarget(level, area, activeSlot, columnIndex, worldX, worldZ, y)) {
                        return;
                    }
                    continue;
                }

                beginLaserShot(level, columnIndex, new BlockPos(worldX, y, worldZ), true);
                setChanged();
                return;
            }

            finishExcavation();
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.TARGET_SELECTION, targetProfile);
        }
    }

    /**
     * Advances past one configured filter block without removing it. Lower filter
     * tiers wait for part or all of a normal mining cycle before another target
     * may be selected; Filter V defaults to no cooldown and therefore skips immediately.
     *
     * Returns true when a cooldown was started and target selection should stop for this tick.
     */
    private boolean skipFilteredTarget(
            ServerLevel level,
            ExcavatorArea area,
            int activeSlot,
            int columnIndex,
            int worldX,
            int worldZ,
            int filteredY
    ) {
        int nextY = findNextTargetY(level, worldX, worldZ, filteredY - 1, area.min().getY());
        columns.setCurrentHeight(columnIndex, nextY);
        if (nextY == ExcavationScanner.NO_SURFACE) {
            columns.removeActiveColumnAt(activeSlot);
        }

        int cooldownTicks = LaserExcavatorConfig.filterSkipCooldownTicks(
                upgrades.tier(ExcavatorUpgradeType.FILTER),
                getLaserWorkDurationTicks()
        );
        if (cooldownTicks <= 0) return false;

        filterSkipCooldownEndTick = level.getGameTime() + cooldownTicks;
        setChanged();
        return true;
    }

    private int findLoadedActiveColumnSlot(ServerLevel level, ExcavatorArea area, int preferredSlot) {
        int preferredColumn = columns.activeColumnAt(preferredSlot);
        int preferredX = area.worldXForColumn(preferredColumn);
        int preferredZ = area.worldZForColumn(preferredColumn);
        chunkCheckCursor.set(preferredX, worldPosition.getY(), preferredZ);
        if (level.hasChunkAt(chunkCheckCursor)) return preferredSlot;

        for (int slot = 0; slot < columns.activeCount(); slot++) {
            int columnIndex = columns.activeColumnAt(slot);
            int worldX = area.worldXForColumn(columnIndex);
            int worldZ = area.worldZForColumn(columnIndex);
            chunkCheckCursor.set(worldX, worldPosition.getY(), worldZ);
            if (level.hasChunkAt(chunkCheckCursor)) return slot;
        }
        return -1;
    }

    private int resolveSelectableTargetY(
            ServerLevel level,
            ExcavatorArea area,
            int columnIndex,
            int worldX,
            int worldZ,
            boolean sharedHeights
    ) {
        int y;
        if (sharedHeights) {
            y = sharedHeightForColumn(level, area, columnIndex, worldX, worldZ);
        } else {
            y = columns.currentHeight(columnIndex);
        }

        if (y == ExcavationScanner.NO_SURFACE
                || y < area.min().getY()
                || (sharedHeights && y > area.max().getY())) {
            return y;
        }

        targetLookupCursor.set(worldX, y, worldZ);
        BlockState state = level.getBlockState(targetLookupCursor);
        Set<Block> unbreakableBlocks = LaserExcavatorConfig.unbreakableBlocks();
        if (!state.isAir()
                && (isFilterCooldownTarget(level, targetLookupCursor, state, unbreakableBlocks)
                || !shouldIgnoreTarget(level, targetLookupCursor, state, unbreakableBlocks))) {
            return y;
        }

        if (sharedHeights) {
            return repairInvalidSharedTarget(level, area, columnIndex, worldX, worldZ, y);
        }

        int recoveredY = findNextTargetY(level, worldX, worldZ, y - 1, area.min().getY());
        columns.setCurrentHeight(columnIndex, recoveredY);
        return recoveredY;
    }

    private void beginLaserShot(
            ServerLevel level,
            int columnIndex,
            BlockPos target,
            boolean startVisual
    ) {
        activeColumnIndex = columnIndex;
        activeTarget = target;
        workPhase = ExcavatorWorkPhase.LASER;
        phaseStartGameTime = level.getGameTime();
        laserPoweredTicks = 0;
        laserEnergySpent = 0;
        laserVisualNeedsRestart = !startVisual;

        if (startVisual) {
            startLaserVisual(level, target);
        }
    }

    private void completeActiveTarget(ServerLevel level) {
        if (activeTarget == null || activeColumnIndex < 0) {
            clearActiveAnimation();
            return;
        }

        ExcavatorArea area = getExcavatorArea();
        boolean sharedHeights = usesSharedColumnHeights();
        BlockState state = resolveActiveTargetForCompletion(level, area, sharedHeights);
        if (state == null || activeTarget == null) return;

        BlockPos target = activeTarget;
        if (isFluidBlock(state)) {
            completeFluidTarget(level, target, state, area.min().getY());
            return;
        }

        List<ItemStack> drops;
        ExcavatorLootCache.CachedDrops cachedLoot = null;
        if (isStorageBlockedTarget(target, activeColumnIndex, state)) {
            drops = storageBlockedDrops;
        } else {
            clearStorageWaitState();
            long lootProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.LOOT_GENERATION);
            boolean silkTouch = hasSilkTouchUpgrade();
            int fortuneLevel = silkTouch ? 0 : getLuckLevel();
            cachedLoot = LaserExcavatorConfig.ENABLE_DETERMINISTIC_LOOT_CACHE.get()
                    ? ExcavatorLootCache.get(level, state, silkTouch, fortuneLevel)
                    : null;

            if (cachedLoot != null) {
                drops = cachedLoot.reservationView();
            } else {
                BlockEntity targetBlockEntity = state.hasBlockEntity() ? level.getBlockEntity(target) : null;
                drops = Block.getDrops(
                        state,
                        level,
                        target,
                        targetBlockEntity,
                        null,
                        getExcavationTool(level)
                );
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NORMAL_LOOT_TABLE_CALLS);

                if (LaserExcavatorConfig.ENABLE_DETERMINISTIC_LOOT_CACHE.get()) {
                    cachedLoot = ExcavatorLootCache.observe(
                            level, state, silkTouch, fortuneLevel, drops);
                }
            }

            if (hasAutoSmeltingUpgrade() && !drops.isEmpty()) {
                ExcavatorAutoSmelter.Result smelted = ExcavatorAutoSmelter.smelt(level, drops);
                drops = smelted.stacks();
                if (smelted.changed()) cachedLoot = null;
            }
            ExcavatorProfiler.end(ExcavatorProfiler.Section.LOOT_GENERATION, lootProfile);
        }

        if (!developmentBenchmarkResourceBypass && !deliveries.canFitAll(drops)) {
            waitForStorageCapacity(target, activeColumnIndex, state, drops);
            return;
        }

        ItemStack representative = cachedLoot != null
                ? cachedLoot.representativePrototype()
                : firstRepresentativeDrop(drops);

        if (!removeTargetAndAdvance(level, target, state, area.min().getY())) {
            return;
        }

        int transportDurationTicks = drops.isEmpty() ? 0 : getTransportDurationTicks(target);
        if (!developmentBenchmarkResourceBypass && !drops.isEmpty()) {
            if (cachedLoot != null) {
                enqueuePendingCachedDelivery(level, transportDurationTicks, cachedLoot);
            } else {
                enqueuePendingDelivery(level, transportDurationTicks, drops);
            }
        }

        if (!representative.isEmpty()) {
            ExcavatorNetworking.sendTransportStart(
                    level,
                    worldPosition,
                    target,
                    representative,
                    transportDurationTicks,
                    getForceFieldY()
            );
        }

        clearStorageWaitState();
        clearActiveAnimation();
        setChanged();
    }

    private @Nullable BlockState resolveActiveTargetForCompletion(
            ServerLevel level,
            ExcavatorArea area,
            boolean sharedHeights
    ) {
        BlockPos target = activeTarget;
        if (target == null) return null;

        if (!level.hasChunkAt(target)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LASER_COMPLETIONS_SKIPPED_UNLOADED);
            clearActiveAnimation();
            setChanged();
            return null;
        }

        if (sharedHeights) {
            ensureSharedColumnsRegistered(level);
            int sharedY = ExcavatorSharedColumnHeights.getCurrentY(level, target.getX(), target.getZ());
            if (sharedY != target.getY()) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_INFLIGHT_REDIRECTS);
                columns.setCurrentHeight(activeColumnIndex, sharedY);

                if (sharedY == ExcavationScanner.NO_SURFACE || sharedY < area.min().getY()) {
                    columns.removeActiveColumnByColumnIndex(activeColumnIndex);
                    stopCurrentTarget(true);
                    return null;
                }
                if (sharedY > area.max().getY()) {
                    stopCurrentTarget(true);
                    return null;
                }

                target = new BlockPos(target.getX(), sharedY, target.getZ());
                activeTarget = target;
            }
        }

        BlockState state = level.getBlockState(target);
        if (!state.isAir() && !shouldIgnoreTarget(level, target, state)) return state;

        int recoveredY;
        if (sharedHeights) {
            recoveredY = repairInvalidSharedTarget(
                    level,
                    area,
                    activeColumnIndex,
                    target.getX(),
                    target.getZ(),
                    target.getY()
            );
        } else {
            recoveredY = findNextTargetY(
                    level,
                    target.getX(),
                    target.getZ(),
                    target.getY() - 1,
                    area.min().getY()
            );
            columns.setCurrentHeight(activeColumnIndex, recoveredY);
        }

        if (recoveredY == ExcavationScanner.NO_SURFACE || recoveredY < area.min().getY()) {
            columns.removeActiveColumnByColumnIndex(activeColumnIndex);
            stopCurrentTarget(true);
            return null;
        }
        if (sharedHeights && recoveredY > area.max().getY()) {
            stopCurrentTarget(true);
            return null;
        }

        activeTarget = new BlockPos(target.getX(), recoveredY, target.getZ());
        return level.getBlockState(activeTarget);
    }

    private void stopCurrentTarget(boolean clearStorageWait) {
        if (clearStorageWait) clearStorageWaitState();
        clearActiveAnimation();
        setChanged();
    }

    private boolean removeTargetAndAdvance(
            ServerLevel level,
            BlockPos target,
            BlockState state,
            int minimumY
    ) {
        if (!removeExcavatedBlock(level, target, state)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_REMOVALS_FAILED_COLUMN_RETAINED);
            clearActiveAnimation();
            setChanged();
            return false;
        }

        ExcavatorBlockSyncBatcher.queueRemoval(level, target);

        blocksExcavated++;
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCKS_EXCAVATED);
        advanceColumnAfterRemoval(level, activeColumnIndex, target.getY(), minimumY);
        return true;
    }

    private void completeFluidTarget(
            ServerLevel level,
            BlockPos target,
            BlockState state,
            int minimumY
    ) {
        if (!removeTargetAndAdvance(level, target, state, minimumY)) return;
        clearActiveAnimation();
        setChanged();
    }

    private void waitForStorageCapacity(
            BlockPos target,
            int columnIndex,
            BlockState state,
            List<ItemStack> drops
    ) {
        storageBlockedTarget = target.immutable();
        storageBlockedColumnIndex = columnIndex;
        storageBlockedState = state;

        List<ItemStack> copiedDrops = new ArrayList<>(drops.size());
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) copiedDrops.add(stack.copy());
        }
        storageBlockedDrops = List.copyOf(copiedDrops);

        lastStorageWaitCheckedRevision = deliveries.storageRevision();
        enterStorageWaitState();
    }

    private void enterStorageWaitState() {
        scanState = ExcavatorScanState.STORAGE_FULL;
        clearActiveAnimation();
        setChangedAndSync();
    }

    private void retryStorageBlockedTarget(ServerLevel level) {
        if (storageBlockedTarget == null
                || storageBlockedColumnIndex < 0
                || storageBlockedDrops.isEmpty()) {
            resumeExcavationAfterStorageWait();
            return;
        }

        if (lastStorageWaitCheckedRevision == deliveries.storageRevision()) return;
        lastStorageWaitCheckedRevision = deliveries.storageRevision();

        if (!deliveries.canFitAll(storageBlockedDrops)) return;

        if (!level.hasChunkAt(storageBlockedTarget)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.STORAGE_RETRIES_SKIPPED_UNLOADED);
            resumeExcavationAfterStorageWait();
            return;
        }

        BlockState currentState = level.getBlockState(storageBlockedTarget);
        if (!currentState.equals(storageBlockedState)) {
            resumeExcavationAfterStorageWait();
            return;
        }

        resumeBlockedTargetLaser(level);
    }

    private void resumeBlockedTargetLaser(ServerLevel level) {
        // Evaluate availability while still STORAGE_FULL using the first powered
        // tick of the resumed block's current Speed/Efficiency budget.
        boolean startVisual = hasEnergyForLaserTick();
        scanState = ExcavatorScanState.EXCAVATING;
        beginLaserShot(
                level,
                storageBlockedColumnIndex,
                storageBlockedTarget,
                startVisual
        );
        setChangedAndSync();
    }

    private boolean isStorageBlockedTarget(BlockPos target, int columnIndex, BlockState state) {
        return storageBlockedTarget != null
                && storageBlockedTarget.equals(target)
                && storageBlockedColumnIndex == columnIndex
                && storageBlockedState != null
                && storageBlockedState.equals(state)
                && !storageBlockedDrops.isEmpty();
    }

    private void clearStorageWaitState() {
        storageBlockedTarget = null;
        storageBlockedColumnIndex = -1;
        storageBlockedState = null;
        storageBlockedDrops = List.of();
        lastStorageWaitCheckedRevision = -1L;
    }

    private int findNextTargetY(ServerLevel level, int x, int z, int startY, int minY) {
        long lookupProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.NEXT_TARGET_LOOKUP);
        boolean profile = ExcavatorProfiler.isEnabled();
        int lookups = 0;

        try {
            if (startY == ExcavationScanner.NO_SURFACE) return ExcavationScanner.NO_SURFACE;

            int clampedStart = Math.min(startY, level.getMaxBuildHeight() - 1);
            int clampedMin = Math.max(minY, level.getMinBuildHeight());
            BlockPos.MutableBlockPos cursor = targetLookupCursor;
            Set<Block> unbreakableBlocks = LaserExcavatorConfig.unbreakableBlocks();
            cursor.set(x, clampedStart, z);

            for (int y = clampedStart; y >= clampedMin; y--) {
                cursor.setY(y);
                BlockState state = level.getBlockState(cursor);
                if (profile) lookups++;
                if (!state.isAir()
                        && (isFilterCooldownTarget(level, cursor, state, unbreakableBlocks)
                        || !shouldIgnoreTarget(level, cursor, state, unbreakableBlocks))) {
                    return y;
                }
            }

            return ExcavationScanner.NO_SURFACE;
        } finally {
            if (profile) {
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.NEXT_TARGET_STATE_LOOKUPS, lookups);
            }
            ExcavatorProfiler.end(ExcavatorProfiler.Section.NEXT_TARGET_LOOKUP, lookupProfile);
        }
    }

    private void advanceColumnAfterRemoval(ServerLevel level, int columnIndex, int removedY, int minY) {
        ExcavatorArea area = getExcavatorArea();
        int x = area.worldXForColumn(columnIndex);
        int z = area.worldZForColumn(columnIndex);

        if (usesSharedColumnHeights()) {
            int sharedMinY = ExcavatorSharedColumnHeights.getRegisteredMinY(level, x, z, minY);
            int nextY = findNextTargetY(level, x, z, removedY - 1, sharedMinY);
            int actualY = ExcavatorSharedColumnHeights.advance(level, x, z, removedY, nextY);
            columns.setCurrentHeight(columnIndex, actualY);

            // The shared cursor may continue below this machine because another
            // overlapping excavator registered a deeper range. This machine is
            // finished with the column as soon as that cursor leaves its own range.
            if (actualY == ExcavationScanner.NO_SURFACE || actualY < minY) {
                columns.removeActiveColumnByColumnIndex(columnIndex);
            }
            return;
        }

        int nextY = findNextTargetY(level, x, z, removedY - 1, minY);
        columns.setCurrentHeight(columnIndex, nextY);

        if (nextY == ExcavationScanner.NO_SURFACE) {
            columns.removeActiveColumnByColumnIndex(columnIndex);
        }
    }

    private void enqueuePendingCachedDelivery(
            ServerLevel level, int transportDurationTicks, ExcavatorLootCache.CachedDrops drops
    ) {
        deliveries.enqueueCachedDrops(level.getGameTime() + transportDurationTicks, drops);
        setChanged();
    }

    private void enqueuePendingDelivery(ServerLevel level, int transportDurationTicks, List<ItemStack> drops) {
        if (deliveries.enqueue(level.getGameTime() + transportDurationTicks, drops)) {
            setChanged();
        }
    }

    private int getTransportDurationTicks(BlockPos sourcePos) {
        int ticksPerBlock = LaserExcavatorConfig.TRANSPORT_TICKS_PER_BLOCK.get();

        int forceFieldHeight = LaserExcavatorConfig.FORCE_FIELD_HEIGHT.get();
        int verticalDifference = worldPosition.getY() - sourcePos.getY();
        int liftBaseTicks = (int) Math.ceil((forceFieldHeight - 0.5D) * ticksPerBlock);
        int descentTicks = Math.max(1, (int) Math.ceil((forceFieldHeight - 0.7D) * ticksPerBlock));
        int liftTicks = Math.max(1, verticalDifference * ticksPerBlock + liftBaseTicks);

        int dx = sourcePos.getX() - worldPosition.getX();
        int dz = sourcePos.getZ() - worldPosition.getZ();
        int fieldTicks = Math.max(
                1,
                (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz) * ticksPerBlock)
        );

        return liftTicks + fieldTicks + descentTicks;
    }

    private boolean removeExcavatedBlock(ServerLevel level, BlockPos target, BlockState state) {
        long totalProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_REMOVAL);
        boolean removed;

        boolean directFastPath = LaserExcavatorConfig.ENABLE_DIRECT_TERRAIN_REMOVAL.get()
                && LaserExcavatorConfig.SUPPRESS_NEIGHBOR_UPDATES.get()
                && ExcavatorFastBlockRemoval.isEligible(state);

        if (directFastPath) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DIRECT_CHUNK_REMOVAL_ATTEMPTS);
            long directProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_REMOVAL_DIRECT_CHUNK);
            removed = ExcavatorFastBlockRemoval.removeDirect(level, target);
            ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_REMOVAL_DIRECT_CHUNK, directProfile);
            if (removed) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DIRECT_CHUNK_REMOVAL_SUCCESSES);
            } else {
                // Extremely defensive fallback: if the direct mutation ever refuses
                // a state change, retry through the normal Level wrapper so the
                // logical excavation cursor never advances past a real block.
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NORMAL_LEVEL_SETBLOCK_FALLBACKS);
                long normalProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_REMOVAL_LEVEL_SETBLOCK);
                removed = level.setBlock(
                        target,
                        Blocks.AIR.defaultBlockState(),
                        excavationRemovalFlags(),
                        excavationRemovalDepth()
                );
                ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_REMOVAL_LEVEL_SETBLOCK, normalProfile);
            }
        } else {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NORMAL_LEVEL_SETBLOCK_FALLBACKS);
            long normalProfile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_REMOVAL_LEVEL_SETBLOCK);
            removed = level.setBlock(
                    target,
                    Blocks.AIR.defaultBlockState(),
                    excavationRemovalFlags(),
                    excavationRemovalDepth()
            );
            ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_REMOVAL_LEVEL_SETBLOCK, normalProfile);
        }

        ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_REMOVAL, totalProfile);
        return removed;
    }

    private int excavationRemovalFlags() {
        // Client synchronization is intentionally omitted here. ExcavatorBlockSyncBatcher
        // sends compact section-local removal payloads after a distance-adaptive batching
        // window. Drops are calculated separately and item entities must never be spawned.
        int commonFlags = Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_INVISIBLE;
        return LaserExcavatorConfig.SUPPRESS_NEIGHBOR_UPDATES.get()
                ? commonFlags | Block.UPDATE_KNOWN_SHAPE
                : commonFlags | Block.UPDATE_NEIGHBORS;
    }

    private int excavationRemovalDepth() {
        // Recursive neighbor-shape propagation is optional and follows the server config.
        return LaserExcavatorConfig.SUPPRESS_NEIGHBOR_UPDATES.get()
                ? 0
                : Block.UPDATE_LIMIT;
    }

    private void processDueDeliveries(ServerLevel level) {
        ExcavatorDeliveryManager.ProcessResult result = deliveries.processDue(level.getGameTime());
        if (!result.changed()) return;
        setChanged();
        if (result.becameEmpty()) syncToClient();
    }

    private ItemStack firstRepresentativeDrop(List<ItemStack> drops) {
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                ItemStack result = stack.copy();
                result.setCount(1);
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    private void finishExcavation() {
        unregisterSharedColumns();
        columns.finishExcavation();
        filterSkipCooldownEndTick = Long.MIN_VALUE;
        scanState = ExcavatorScanState.COMPLETE;
        clearActiveAnimation();
        setChangedAndSync();
    }

    private void clearActiveAnimation() {
        activeColumnIndex = -1;
        activeTarget = null;
        workPhase = ExcavatorWorkPhase.NONE;
        phaseStartGameTime = level == null ? 0L : level.getGameTime();
        laserPoweredTicks = 0;
        laserEnergySpent = 0;
        laserVisualNeedsRestart = false;
    }

    private void resetAllWorkData() {
        unregisterSharedColumns();
        scanState = ExcavatorScanState.IDLE;
        columns.resetAll();
        blocksExcavated = 0;
        filterSkipCooldownEndTick = Long.MIN_VALUE;
        clearStorageWaitState();
        clearActiveAnimation();
    }

    private void setChangedAndSync() {
        setChanged();
        syncToClient();
    }

    private void syncToClient() {
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt(TAG_DATA_VERSION, PERSISTENCE_VERSION);
        writeCommonData(tag, false);
        saveExcavationState(tag);
        saveInventories(tag, registries);
        fuel.save(tag, registries);
        upgrades.saveFilter(tag);
        deliveries.save(tag, registries);
    }

    private void saveExcavationState(CompoundTag tag) {
        tag.putIntArray(TAG_SURFACE_HEIGHTS, columns.surfaceHeightsForSave());
        tag.putIntArray(TAG_CURRENT_HEIGHTS, columns.currentHeightsForSave());
        tag.putIntArray(TAG_ACTIVE_COLUMNS, columns.activeColumnsForSave());
        tag.putBoolean(TAG_EXCAVATION_INITIALIZED, columns.isExcavationInitialized());
        tag.putInt(TAG_ENERGY, getEnergyStored());
        if (filterSkipCooldownEndTick != Long.MIN_VALUE) {
            tag.putLong(TAG_FILTER_SKIP_COOLDOWN_END_TICK, filterSkipCooldownEndTick);
        }
    }

    private void saveInventories(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_OUTPUT_INVENTORY, outputInventory.serializeNBT(registries));
        tag.put(TAG_UPGRADE_INVENTORY, upgrades.inventory().serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        loadCommonState(tag);
        if (tag.getBoolean(TAG_CLIENT_SYNC_ONLY)) {
            loadClientSyncState();
            return;
        }

        int persistenceVersion = readPersistenceVersion(tag);
        loadExcavationState(tag);
        loadInventories(tag, registries);
        fuel.load(tag, registries);
        upgrades.loadFilter(tag);
        clampSelectionToAreaUpgrade();
        deliveries.load(tag, registries);
        rebuildRuntimeStateAfterLoad();
        validateLoadedWorkState(persistenceVersion);
    }

    private int readPersistenceVersion(CompoundTag tag) {
        return tag.contains(TAG_DATA_VERSION, Tag.TAG_INT)
                ? Math.max(0, tag.getInt(TAG_DATA_VERSION))
                : 0;
    }

    private void loadCommonState(CompoundTag tag) {
        selectionWidth = LaserExcavatorConfig.clampSelectionSize(
                tag.contains(TAG_SELECTION_WIDTH)
                        ? tag.getInt(TAG_SELECTION_WIDTH)
                        : LaserExcavatorConfig.DEFAULT_WIDTH.get(),
                LaserExcavatorConfig.maxConfiguredHorizontalSize()
        );
        selectionHeight = LaserExcavatorConfig.clampSelectionSize(
                tag.contains(TAG_SELECTION_HEIGHT)
                        ? tag.getInt(TAG_SELECTION_HEIGHT)
                        : LaserExcavatorConfig.DEFAULT_HEIGHT.get(),
                LaserExcavatorConfig.MAX_VERTICAL_SIZE.get()
        );
        selectionLength = LaserExcavatorConfig.clampSelectionSize(
                tag.contains(TAG_SELECTION_LENGTH)
                        ? tag.getInt(TAG_SELECTION_LENGTH)
                        : LaserExcavatorConfig.DEFAULT_LENGTH.get(),
                LaserExcavatorConfig.maxConfiguredHorizontalSize()
        );

        scanState = ExcavatorScanState.byId(tag.getInt(TAG_SCAN_STATE));
        int loadedHighestSurfaceY = tag.contains(TAG_HIGHEST_SURFACE_Y)
                ? tag.getInt(TAG_HIGHEST_SURFACE_Y)
                : ExcavationScanner.NO_SURFACE;
        columns.loadSummary(
                tag.getInt(TAG_SCAN_INDEX),
                loadedHighestSurfaceY,
                tag.getInt(TAG_ACTIVE_COLUMN_COUNT)
        );
        blocksExcavated = Math.max(0, tag.getInt(TAG_BLOCKS_EXCAVATED));
        syncedPendingDeliveryCount = Math.max(0, tag.getInt(TAG_PENDING_ITEM_COUNT));
        workPhase = ExcavatorWorkPhase.byId(tag.getInt(TAG_WORK_PHASE));
        phaseStartGameTime = tag.getLong(TAG_PHASE_START_GAME_TIME);
        laserPoweredTicks = Math.max(0, tag.getInt(TAG_LASER_POWERED_TICKS));
        laserEnergySpent = Math.max(0, tag.getInt(TAG_LASER_ENERGY_SPENT));

        columns.setSharedColumnsRegistered(false);
        laserVisualNeedsRestart = workPhase == ExcavatorWorkPhase.LASER;

        if (tag.contains(TAG_ENERGY)) {
            energyStorage.loadStoredEnergy(tag.getInt(TAG_ENERGY));
        }
        activeTarget = tag.contains(TAG_ACTIVE_TARGET)
                ? BlockPos.of(tag.getLong(TAG_ACTIVE_TARGET))
                : null;
    }

    private void loadClientSyncState() {
        columns.loadClientSyncState(
                scanState == ExcavatorScanState.EXCAVATING
                        || scanState == ExcavatorScanState.STORAGE_FULL
                        || scanState == ExcavatorScanState.COMPLETE
        );
    }

    private void loadExcavationState(CompoundTag tag) {
        filterSkipCooldownEndTick = tag.contains(TAG_FILTER_SKIP_COOLDOWN_END_TICK, Tag.TAG_LONG)
                ? tag.getLong(TAG_FILTER_SKIP_COOLDOWN_END_TICK)
                : Long.MIN_VALUE;
        columns.loadExcavationState(
                tag.getIntArray(TAG_SURFACE_HEIGHTS),
                tag.getIntArray(TAG_CURRENT_HEIGHTS),
                tag.getIntArray(TAG_ACTIVE_COLUMNS),
                tag.getBoolean(TAG_EXCAVATION_INITIALIZED)
        );
    }

    private void loadInventories(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains(TAG_OUTPUT_INVENTORY, Tag.TAG_COMPOUND)) {
            CompoundTag inventoryTag = tag.getCompound(TAG_OUTPUT_INVENTORY);
            if (inventoryTag.getInt(TAG_INVENTORY_SIZE) == OUTPUT_SLOTS) {
                outputInventory.deserializeNBT(registries, inventoryTag);
            }
        }
        if (tag.contains(TAG_UPGRADE_INVENTORY, Tag.TAG_COMPOUND)) {
            CompoundTag inventoryTag = tag.getCompound(TAG_UPGRADE_INVENTORY);
            if (inventoryTag.getInt(TAG_INVENTORY_SIZE) == ExcavatorUpgradeManager.STORED_UPGRADE_SLOTS) {
                upgrades.deserializeInventory(inventoryTag, registries);
            }
        }
    }

    private void rebuildRuntimeStateAfterLoad() {
        syncedPendingDeliveryCount = deliveries.pendingCount();
        columns.clampScanIndex(getTotalColumns());
    }

    private void validateLoadedWorkState(int persistenceVersion) {
        // Do not destructively recover data whose schema version exceeds the version supported by this build.
        if (persistenceVersion > PERSISTENCE_VERSION) return;

        int totalColumns = getTotalColumns();
        if (!columns.isExcavationInitialized()
                && scanState != ExcavatorScanState.IDLE
                && scanState != ExcavatorScanState.COMPLETE
                && !columns.hasExpectedSurfaceCount(totalColumns)) {
            resetAllWorkData();
            return;
        }

        if (columns.isExcavationInitialized() && !columns.hasExpectedCurrentCount(totalColumns)) {
            resetAllWorkData();
        }
    }

    private void writeCommonData(CompoundTag tag, boolean clientSyncOnly) {
        tag.putInt(TAG_SELECTION_WIDTH, selectionWidth);
        tag.putInt(TAG_SELECTION_HEIGHT, selectionHeight);
        tag.putInt(TAG_SELECTION_LENGTH, selectionLength);
        tag.putInt(TAG_SCAN_STATE, scanState.id());
        tag.putInt(TAG_SCAN_INDEX, columns.scanIndex());
        tag.putInt(TAG_HIGHEST_SURFACE_Y, columns.highestSurfaceY());
        tag.putInt(TAG_BLOCKS_EXCAVATED, blocksExcavated);
        tag.putInt(TAG_ACTIVE_COLUMN_COUNT, columns.activeCount());
        tag.putInt(TAG_PENDING_ITEM_COUNT, deliveries.pendingCount());

        if (!clientSyncOnly) {
            tag.putInt(TAG_WORK_PHASE, workPhase.id());
            tag.putLong(TAG_PHASE_START_GAME_TIME, phaseStartGameTime);
            tag.putInt(TAG_LASER_POWERED_TICKS, laserPoweredTicks);
            tag.putInt(TAG_LASER_ENERGY_SPENT, laserEnergySpent);
            if (activeTarget != null) {
                tag.putLong(TAG_ACTIVE_TARGET, activeTarget.asLong());
            }
        } else {
            tag.putBoolean(TAG_CLIENT_SYNC_ONLY, true);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeCommonData(tag, true);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Excavator");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ExcavatorMenu(containerId, inventory, this);
    }
}
