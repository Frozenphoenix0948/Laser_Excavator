package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Owns the optional furnace-fuel slot and converts vanilla/modded furnace burn
 * time into internal FE. Burning pauses while the internal energy buffer is full.
 */
public final class ExcavatorFuelManager {
    private static final String TAG_FUEL_INVENTORY = "FuelInventory";
    private static final String TAG_BURN_TICKS_REMAINING = "FuelBurnTicksRemaining";
    private static final String TAG_BURN_TICKS_TOTAL = "FuelBurnTicksTotal";

    public static final int FUEL_SLOTS = 1;

    private final ExcavatorEnergyStorage energyStorage;
    private final Runnable changeListener;
    private int burnTicksRemaining;
    private int burnTicksTotal;

    private final ItemStackHandler inventory = new ItemStackHandler(FUEL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return isEnabled() && isFuel(stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            changeListener.run();
        }
    };

    public ExcavatorFuelManager(ExcavatorEnergyStorage energyStorage, Runnable changeListener) {
        this.energyStorage = energyStorage;
        this.changeListener = changeListener;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public boolean isEnabled() {
        return LaserExcavatorConfig.fuelSlotEnabled();
    }

    public int burnTicksRemaining() {
        return Math.max(0, burnTicksRemaining);
    }

    public int burnTicksTotal() {
        return Math.max(0, burnTicksTotal);
    }

    public static boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && stack.getBurnTime(RecipeType.SMELTING) > 0;
    }

    public void tick() {
        if (!isEnabled()) return;
        if (energyStorage.getEnergyStored() >= energyStorage.getMaxEnergyStored()) return;

        int burnTicksBudget = Math.max(1, LaserExcavatorConfig.FUEL_BURN_TICKS_PER_SERVER_TICK.get());
        int energyPerBurnTick = Math.max(1, LaserExcavatorConfig.FUEL_ENERGY_PER_BURN_TICK.get());

        while (burnTicksBudget > 0) {
            int freeEnergy = energyStorage.getMaxEnergyStored() - energyStorage.getEnergyStored();
            int ticksThatFit = freeEnergy / energyPerBurnTick;
            if (ticksThatFit <= 0) return;

            if (burnTicksRemaining <= 0) {
                if (inventory.getStackInSlot(0).isEmpty() || !consumeFuelItem()) return;
            }

            int ticksToConsume = Math.min(burnTicksBudget, Math.min(burnTicksRemaining, ticksThatFit));
            if (ticksToConsume <= 0) return;

            long requestedEnergy = (long) ticksToConsume * (long) energyPerBurnTick;
            int generated = energyStorage.addInternal((int) Math.min(Integer.MAX_VALUE, requestedEnergy));
            int consumedTicks = generated / energyPerBurnTick;
            if (consumedTicks <= 0) return;

            burnTicksRemaining -= consumedTicks;
            burnTicksBudget -= consumedTicks;
        }
    }

    private boolean consumeFuelItem() {
        ItemStack stack = inventory.getStackInSlot(0);
        int burnTime = stack.getBurnTime(RecipeType.SMELTING);
        if (burnTime <= 0) return false;

        ItemStack consumed = inventory.extractItem(0, 1, false);
        if (consumed.isEmpty()) return false;

        ItemStack remainder = consumed.getCraftingRemainingItem();
        if (!remainder.isEmpty() && inventory.getStackInSlot(0).isEmpty()) {
            inventory.setStackInSlot(0, remainder);
        }

        burnTicksRemaining = burnTime;
        burnTicksTotal = burnTime;
        return true;
    }

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_FUEL_INVENTORY, inventory.serializeNBT(registries));
        if (burnTicksRemaining > 0) {
            tag.putInt(TAG_BURN_TICKS_REMAINING, burnTicksRemaining);
            tag.putInt(TAG_BURN_TICKS_TOTAL, Math.max(burnTicksRemaining, burnTicksTotal));
        }
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        burnTicksRemaining = Math.max(0, tag.getInt(TAG_BURN_TICKS_REMAINING));
        burnTicksTotal = Math.max(burnTicksRemaining, tag.getInt(TAG_BURN_TICKS_TOTAL));
        if (tag.contains(TAG_FUEL_INVENTORY, Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(registries, tag.getCompound(TAG_FUEL_INVENTORY));
        }
    }
}
