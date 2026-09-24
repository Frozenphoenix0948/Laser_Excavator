package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

public class ExcavatorMenu extends AbstractContainerMenu {
    public static final int DATA_WIDTH = 0;
    public static final int DATA_HEIGHT = 1;
    public static final int DATA_LENGTH = 2;
    public static final int DATA_STATE = 3;
    public static final int DATA_SCAN_INDEX = 4;
    public static final int DATA_TOTAL_COLUMNS = 5;
    public static final int DATA_FORCE_FIELD_Y = 6;
    public static final int DATA_BLOCKS_EXCAVATED = 7;
    public static final int DATA_PENDING_ITEMS = 8;
    public static final int DATA_MAX_AREA_SIZE = 9;
    public static final int DATA_FILTER_CAPACITY = 10;
    public static final int DATA_FILTER_START = 11;
    public static final int DATA_ENERGY_STORED = DATA_FILTER_START + ExcavatorBlockEntity.MAX_FILTER_SLOTS;
    public static final int DATA_ENERGY_CAPACITY = DATA_ENERGY_STORED + 1;
    public static final int DATA_ENERGY_USAGE = DATA_ENERGY_CAPACITY + 1;
    public static final int DATA_ENERGY_PER_BLOCK = DATA_ENERGY_USAGE + 1;
    public static final int DATA_FUEL_BURN_REMAINING = DATA_ENERGY_PER_BLOCK + 1;
    public static final int DATA_FUEL_BURN_TOTAL = DATA_FUEL_BURN_REMAINING + 1;
    public static final int DATA_OVERHEATING = DATA_FUEL_BURN_TOTAL + 1;
    public static final int DATA_COUNT = DATA_OVERHEATING + 1;

    private static final int SLOT_STEP = 17;

    public static final int BUTTON_START_SCAN = 12;
    public static final int BUTTON_TOGGLE_EXCAVATION = 13;

    public static final int BUTTON_SET_WIDTH_BASE = 1000;
    public static final int BUTTON_SET_HEIGHT_BASE = 2000;
    public static final int BUTTON_SET_LENGTH_BASE = 3000;
    public static final int BUTTON_FILTER_SLOT_BASE = 4000;

    private static final int BUTTON_FILTER_DIRECT_MARKER = 0x50000000;
    private static final int BUTTON_FILTER_DIRECT_MASK = 0xF0000000;
    private static final int BUTTON_FILTER_DIRECT_SLOT_SHIFT = 24;
    private static final int BUTTON_FILTER_DIRECT_BLOCK_MASK = 0x00FFFFFF;

    private static final int OUTPUT_SLOT_START = 0;
    private static final int OUTPUT_SLOT_END = OUTPUT_SLOT_START + ExcavatorBlockEntity.OUTPUT_SLOTS;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final BlockPos excavatorPos;
    private final @Nullable ExcavatorBlockEntity blockEntity;
    private final int upgradeSlotCount;
    private final boolean fuelSlotEnabled;
    private final int fuelSlotIndex;
    private final int upgradeSlotStart;
    private final int upgradeSlotEnd;
    private final int playerInvStart;
    private final int hotbarEnd;

    private record OpenData(BlockPos pos, int upgradeSlots, boolean fuelSlotEnabled) {
    }

    public ExcavatorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, readOpenData(extraData));
    }

    private ExcavatorMenu(int containerId, Inventory inventory, OpenData openData) {
        this(
                containerId,
                inventory,
                new SimpleContainerData(DATA_COUNT),
                ContainerLevelAccess.NULL,
                openData.pos(),
                null,
                new ItemStackHandler(ExcavatorBlockEntity.OUTPUT_SLOTS),
                new ItemStackHandler(ExcavatorBlockEntity.UPGRADE_SLOTS),
                new ItemStackHandler(1),
                openData.upgradeSlots(),
                openData.fuelSlotEnabled()
        );
    }

    private static OpenData readOpenData(RegistryFriendlyByteBuf extraData) {
        BlockPos pos = extraData.readBlockPos();
        int upgradeSlots = Mth.clamp(extraData.readVarInt(), 0, ExcavatorBlockEntity.UPGRADE_SLOTS);
        boolean fuelSlotEnabled = extraData.readBoolean();
        return new OpenData(pos, upgradeSlots, fuelSlotEnabled);
    }

    public ExcavatorMenu(int containerId, Inventory inventory, ExcavatorBlockEntity blockEntity) {
        this(
                containerId,
                inventory,
                blockEntity.getMenuData(),
                ContainerLevelAccess.create(inventory.player.level(), blockEntity.getBlockPos()),
                blockEntity.getBlockPos(),
                blockEntity,
                blockEntity.getOutputInventory(),
                blockEntity.getUpgradeInventory(),
                blockEntity.getFuelInventory(),
                blockEntity.getUpgradeSlotCount(),
                blockEntity.isFuelSlotEnabled()
        );
    }

    private ExcavatorMenu(
            int containerId,
            Inventory inventory,
            ContainerData data,
            ContainerLevelAccess access,
            BlockPos excavatorPos,
            @Nullable ExcavatorBlockEntity blockEntity,
            ItemStackHandler outputInventory,
            ItemStackHandler upgradeInventory,
            ItemStackHandler fuelInventory,
            int upgradeSlotCount,
            boolean fuelSlotEnabled
    ) {
        super(ModMenuTypes.EXCAVATOR_MENU.get(), containerId);

        checkContainerDataCount(data, DATA_COUNT);

        this.data = data;
        this.access = access;
        this.excavatorPos = excavatorPos.immutable();
        this.blockEntity = blockEntity;
        this.upgradeSlotCount = Mth.clamp(upgradeSlotCount, 0, ExcavatorBlockEntity.UPGRADE_SLOTS);
        this.fuelSlotEnabled = fuelSlotEnabled;

        int nextSlot = OUTPUT_SLOT_END;
        this.fuelSlotIndex = fuelSlotEnabled ? nextSlot++ : -1;
        this.upgradeSlotStart = nextSlot;
        this.upgradeSlotEnd = upgradeSlotStart + this.upgradeSlotCount;
        this.playerInvStart = upgradeSlotEnd;
        this.hotbarEnd = playerInvStart + 36;

        addDataSlots(data);
        addOutputSlots(outputInventory);
        if (fuelSlotEnabled) {
            addFuelSlot(fuelInventory);
        }
        addUpgradeSlots(upgradeInventory);
        addPlayerInventory(inventory);
    }

    private void addOutputSlots(ItemStackHandler handler) {
        int startX = 11;
        int startY = 104;

        for (int row = 0; row < ExcavatorBlockEntity.OUTPUT_ROWS; row++) {
            for (int col = 0; col < ExcavatorBlockEntity.OUTPUT_COLUMNS; col++) {
                int slot = col + row * ExcavatorBlockEntity.OUTPUT_COLUMNS;
                addSlot(new SlotItemHandler(handler, slot, startX + col * SLOT_STEP, startY + row * SLOT_STEP) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                });
            }
        }
    }

    private void addFuelSlot(ItemStackHandler handler) {
        addSlot(new SlotItemHandler(handler, 0, 179, 95) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ExcavatorBlockEntity.isFuel(stack);
            }
        });
    }

    private void addUpgradeSlots(ItemStackHandler handler) {
        int x = 179;
        int startY = fuelSlotEnabled ? 140 : 107;

        for (int slot = 0; slot < upgradeSlotCount; slot++) {
            int upgradeSlot = slot;
            addSlot(new SlotItemHandler(handler, slot, x, startY + slot * SLOT_STEP) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return isUpgradeChangeAllowed(stack)
                            && isUpgradeAllowedForSlot(upgradeSlot, stack)
                            && handler.isItemValid(upgradeSlot, stack);
                }

                @Override
                public boolean mayPickup(Player player) {
                    return ExcavatorMenu.this.isUpgradeChangeAllowed(getItem());
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
    }

    private boolean isUpgradeAllowedForSlot(int targetSlot, ItemStack stack) {
        ExcavatorUpgradeType type = ExcavatorBlockEntity.getUpgradeType(stack);
        if (type == null) return false;

        for (int slot = 0; slot < upgradeSlotCount; slot++) {
            if (slot == targetSlot) continue;
            int menuSlot = upgradeSlotStart + slot;
            if (menuSlot >= slots.size()) continue;
            ExcavatorUpgradeType installed = ExcavatorBlockEntity.getUpgradeType(slots.get(menuSlot).getItem());
            if (installed == type || ExcavatorBlockEntity.upgradesConflict(type, installed)) return false;
        }
        return true;
    }

    public boolean isUpgradeConfigurationLocked() {
        ExcavatorScanState state = getScanState();
        return state == ExcavatorScanState.SCANNING
                || state == ExcavatorScanState.EXCAVATING
                || state == ExcavatorScanState.STORAGE_FULL
                || getPendingItemCount() > 0;
    }

    /** Running excavators only lock upgrades that can invalidate target/area configuration. */
    private boolean isUpgradeChangeAllowed(ItemStack stack) {
        if (!isUpgradeConfigurationLocked()) return true;
        return ExcavatorBlockEntity.isUpgradeHotSwappable(ExcavatorBlockEntity.getUpgradeType(stack));
    }

    private void addPlayerInventory(Inventory inventory) {
        int startX = 11;
        int startY = 183;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, startX + col * SLOT_STEP, startY + row * SLOT_STEP));
            }
        }

        int hotbarY = 240;
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, startX + col * SLOT_STEP, hotbarY));
        }
    }

    public BlockPos getExcavatorPos() {
        return excavatorPos;
    }

    public int getUpgradeSlotCount() {
        return upgradeSlotCount;
    }

    public boolean isFuelSlotEnabled() {
        return fuelSlotEnabled;
    }

    public int getSelectionWidth() {
        return data.get(DATA_WIDTH);
    }

    public int getSelectionHeight() {
        return data.get(DATA_HEIGHT);
    }

    public int getSelectionLength() {
        return data.get(DATA_LENGTH);
    }

    public ExcavatorScanState getScanState() {
        return ExcavatorScanState.byId(data.get(DATA_STATE));
    }

    public int getScannedColumns() {
        return data.get(DATA_SCAN_INDEX);
    }

    public int getTotalColumns() {
        return Math.max(1, data.get(DATA_TOTAL_COLUMNS));
    }

    public int getForceFieldY() {
        return data.get(DATA_FORCE_FIELD_Y);
    }

    public int getBlocksExcavated() {
        return Math.max(0, data.get(DATA_BLOCKS_EXCAVATED));
    }

    public int getPendingItemCount() {
        return Math.max(0, data.get(DATA_PENDING_ITEMS));
    }

    public int getMaxAreaSize() {
        return Math.max(1, data.get(DATA_MAX_AREA_SIZE));
    }

    public int getFilterCapacity() {
        return Mth.clamp(data.get(DATA_FILTER_CAPACITY), 0, ExcavatorBlockEntity.MAX_FILTER_SLOTS);
    }

    public int getEnergyStored() {
        return Math.max(0, data.get(DATA_ENERGY_STORED));
    }

    public int getEnergyCapacity() {
        return Math.max(1, data.get(DATA_ENERGY_CAPACITY));
    }

    public int getEnergyUsagePerTick() {
        return Math.max(0, data.get(DATA_ENERGY_USAGE));
    }

    public int getEnergyPerBlock() {
        return Math.max(0, data.get(DATA_ENERGY_PER_BLOCK));
    }

    public int getFuelBurnTicksRemaining() {
        return Math.max(0, data.get(DATA_FUEL_BURN_REMAINING));
    }

    public int getFuelBurnTicksTotal() {
        return Math.max(0, data.get(DATA_FUEL_BURN_TOTAL));
    }

    public float getFuelBurnFraction() {
        int total = getFuelBurnTicksTotal();
        if (total <= 0) return 0.0F;
        return Mth.clamp(getFuelBurnTicksRemaining() / (float) total, 0.0F, 1.0F);
    }

    public boolean isOverheating() {
        return data.get(DATA_OVERHEATING) != 0;
    }

    public boolean isWaitingForEnergy() {
        return getScanState() == ExcavatorScanState.EXCAVATING
                && getEnergyUsagePerTick() > 0
                && getEnergyStored() < getEnergyUsagePerTick();
    }

    public float getEnergyFraction() {
        return Mth.clamp(getEnergyStored() / (float) getEnergyCapacity(), 0.0F, 1.0F);
    }

    public @Nullable Block getFilterBlock(int filterSlot) {
        if (filterSlot < 0 || filterSlot >= ExcavatorBlockEntity.MAX_FILTER_SLOTS) return null;
        int id = data.get(DATA_FILTER_START + filterSlot);
        if (id < 0) return null;
        Block block = BuiltInRegistries.BLOCK.byId(id);
        return block == null || block == Blocks.AIR ? null : block;
    }

    public @Nullable Component getUpgradeConflictMessage(ItemStack stack) {
        ExcavatorUpgradeType type = ExcavatorBlockEntity.getUpgradeType(stack);
        if (type == null) return null;
        if (isUpgradeConfigurationLocked() && !ExcavatorBlockEntity.isUpgradeHotSwappable(type)) {
            return Component.literal(upgradeTypeName(type) + " cannot be changed while the excavator is running.");
        }

        for (int slot = 0; slot < upgradeSlotCount; slot++) {
            ExcavatorUpgradeType installed = ExcavatorBlockEntity.getUpgradeType(
                    slots.get(upgradeSlotStart + slot).getItem()
            );
            if (installed == null) continue;
            if (installed == type) {
                return Component.literal("Only one " + upgradeTypeName(type) + " upgrade can be installed.");
            }
            if (ExcavatorBlockEntity.upgradesConflict(type, installed)) {
                if (type == ExcavatorUpgradeType.SILK_TOUCH) {
                    return Component.literal("Silk Touch cannot be combined with " + upgradeTypeName(installed) + ".");
                }
                return Component.literal(upgradeTypeName(type) + " cannot be combined with Silk Touch.");
            }
        }
        return null;
    }

    private static String upgradeTypeName(ExcavatorUpgradeType type) {
        return switch (type) {
            case SPEED -> "Speed";
            case AUTO_SMELTING -> "Auto-Smelt";
            case AREA -> "Area";
            case LUCK -> "Luck";
            case FILTER -> "Filter";
            case SILK_TOUCH -> "Silk Touch";
            case FLUID_IGNORE -> "Fluid Ignore";
            case ENERGY_EFFICIENCY -> "Energy Efficiency";
            case NETHER_COOLING -> "Nether Cooling";
        };
    }

    public float getScanProgress() {
        return Mth.clamp(getScannedColumns() / (float) getTotalColumns(), 0.0F, 1.0F);
    }

    public static int encodeSetSelectionButton(int dataIndex, int value) {
        return switch (dataIndex) {
            case DATA_WIDTH -> BUTTON_SET_WIDTH_BASE + Math.max(0, value);
            case DATA_HEIGHT -> BUTTON_SET_HEIGHT_BASE + Math.max(0, value);
            case DATA_LENGTH -> BUTTON_SET_LENGTH_BASE + Math.max(0, value);
            default -> throw new IllegalArgumentException("Unsupported selection index: " + dataIndex);
        };
    }

    public static int encodeFilterBlockButton(int filterSlot, @Nullable Block block) {
        if (filterSlot < 0 || filterSlot >= ExcavatorBlockEntity.MAX_FILTER_SLOTS) {
            throw new IllegalArgumentException("Unsupported filter slot: " + filterSlot);
        }

        int encodedBlock = 0;
        if (block != null && block != Blocks.AIR) {
            int blockId = BuiltInRegistries.BLOCK.getId(block);
            if (blockId < 0 || blockId >= BUTTON_FILTER_DIRECT_BLOCK_MASK) {
                throw new IllegalArgumentException("Block registry id is too large for filter button encoding: " + blockId);
            }
            encodedBlock = blockId + 1;
        }

        return BUTTON_FILTER_DIRECT_MARKER
                | (filterSlot << BUTTON_FILTER_DIRECT_SLOT_SHIFT)
                | encodedBlock;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if ((id & BUTTON_FILTER_DIRECT_MASK) == BUTTON_FILTER_DIRECT_MARKER) {
            int filterSlot = (id >>> BUTTON_FILTER_DIRECT_SLOT_SHIFT) & 0x0F;
            int encodedBlock = id & BUTTON_FILTER_DIRECT_BLOCK_MASK;
            Block block = encodedBlock == 0 ? null : BuiltInRegistries.BLOCK.byId(encodedBlock - 1);
            if (block == Blocks.AIR) block = null;
            return setFilterBlock(player, filterSlot, block);
        }

        if (id >= BUTTON_FILTER_SLOT_BASE
                && id < BUTTON_FILTER_SLOT_BASE + ExcavatorBlockEntity.MAX_FILTER_SLOTS) {
            return configureFilterSlot(player, id - BUTTON_FILTER_SLOT_BASE);
        }
        if (id >= BUTTON_SET_WIDTH_BASE && id < BUTTON_SET_HEIGHT_BASE) {
            return setSelection(DATA_WIDTH, id - BUTTON_SET_WIDTH_BASE);
        }
        if (id >= BUTTON_SET_HEIGHT_BASE && id < BUTTON_SET_LENGTH_BASE) {
            return setSelection(DATA_HEIGHT, id - BUTTON_SET_HEIGHT_BASE);
        }
        if (id >= BUTTON_SET_LENGTH_BASE && id < BUTTON_SET_LENGTH_BASE + 1000) {
            return setSelection(DATA_LENGTH, id - BUTTON_SET_LENGTH_BASE);
        }

        return switch (id) {
            case BUTTON_START_SCAN -> startScan(player);
            case BUTTON_TOGGLE_EXCAVATION -> toggleExcavation(player);
            default -> false;
        };
    }

    private boolean configureFilterSlot(Player player, int filterSlot) {
        ItemStack carried = getCarried();
        Block block = null;
        if (!carried.isEmpty()) {
            if (!(carried.getItem() instanceof BlockItem blockItem)) return false;
            block = blockItem.getBlock();
        }
        return setFilterBlock(player, filterSlot, block);
    }

    private boolean setFilterBlock(Player player, int filterSlot, @Nullable Block block) {
        if (filterSlot < 0 || filterSlot >= getFilterCapacity() || isUpgradeConfigurationLocked()) return false;
        if (block != null && block.asItem().getDefaultInstance().isEmpty()) return false;

        if (player.level().isClientSide) {
            data.set(DATA_FILTER_START + filterSlot, block == null ? -1 : BuiltInRegistries.BLOCK.getId(block));
            return true;
        }

        return blockEntity != null && blockEntity.setFilterBlock(filterSlot, block);
    }

    private boolean startScan(Player player) {
        ExcavatorScanState state = getScanState();
        if (isOverheating()) return false;
        if (state == ExcavatorScanState.SCANNING
                || state == ExcavatorScanState.EXCAVATING
                || state == ExcavatorScanState.STORAGE_FULL
                || getPendingItemCount() > 0) {
            return false;
        }

        if (player.level().isClientSide) {
            data.set(DATA_STATE, ExcavatorScanState.SCANNING.id());
            data.set(DATA_SCAN_INDEX, 0);
            data.set(DATA_BLOCKS_EXCAVATED, 0);
            return true;
        }

        if (blockEntity == null) return false;
        blockEntity.beginScan();
        return true;
    }

    private boolean toggleExcavation(Player player) {
        ExcavatorScanState state = getScanState();
        if (state != ExcavatorScanState.READY
                && state != ExcavatorScanState.EXCAVATING
                && state != ExcavatorScanState.STORAGE_FULL) {
            return false;
        }
        if (state == ExcavatorScanState.READY && isOverheating()) return false;

        if (player.level().isClientSide) {
            data.set(
                    DATA_STATE,
                    (state == ExcavatorScanState.EXCAVATING || state == ExcavatorScanState.STORAGE_FULL)
                            ? ExcavatorScanState.READY.id()
                            : ExcavatorScanState.EXCAVATING.id()
            );
            return true;
        }

        if (blockEntity == null) return false;

        if (state == ExcavatorScanState.EXCAVATING || state == ExcavatorScanState.STORAGE_FULL) {
            blockEntity.pauseExcavation();
        } else {
            blockEntity.beginExcavation();
        }
        return true;
    }

    private boolean setSelection(int index, int requested) {
        ExcavatorScanState state = getScanState();
        if (state == ExcavatorScanState.SCANNING
                || state == ExcavatorScanState.EXCAVATING
                || state == ExcavatorScanState.STORAGE_FULL
                || getPendingItemCount() > 0) {
            return false;
        }

        int max = (index == DATA_WIDTH || index == DATA_LENGTH)
                ? getMaxAreaSize()
                : LaserExcavatorConfig.MAX_VERTICAL_SIZE.get();
        int next = LaserExcavatorConfig.clampSelectionSize(requested, max);
        if (data.get(index) == next) {
            return true;
        }

        data.set(index, next);

        if (blockEntity == null) {
            data.set(DATA_STATE, ExcavatorScanState.IDLE.id());
            data.set(DATA_SCAN_INDEX, 0);
            data.set(DATA_BLOCKS_EXCAVATED, 0);
        }

        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.EXCAVATOR.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack source = slot.getItem();
        ItemStack copy = source.copy();

        if (index >= OUTPUT_SLOT_START && index < OUTPUT_SLOT_END) {
            if (!moveItemStackTo(source, playerInvStart, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (fuelSlotEnabled && index == fuelSlotIndex) {
            if (!moveItemStackTo(source, playerInvStart, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= upgradeSlotStart && index < upgradeSlotEnd) {
            if (!isUpgradeChangeAllowed(source)) return ItemStack.EMPTY;
            if (!moveItemStackTo(source, playerInvStart, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= playerInvStart && index < hotbarEnd) {
            if (ExcavatorBlockEntity.isExcavatorUpgrade(source)) {
                if (!isUpgradeChangeAllowed(source)
                        || !moveItemStackTo(source, upgradeSlotStart, upgradeSlotEnd, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (fuelSlotEnabled && ExcavatorBlockEntity.isFuel(source)) {
                if (!moveItemStackTo(source, fuelSlotIndex, fuelSlotIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (source.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        slot.onTake(player, source);
        return copy;
    }

}
