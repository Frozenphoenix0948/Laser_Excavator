package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Furnace-fuel slot and fuel-to-FE conversion. */
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

    public ItemStackHandler inventory() { return inventory; }
    public boolean isEnabled() { return LaserExcavatorConfig.fuelSlotEnabled(); }
    public int burnTicksRemaining() { return Math.max(0, burnTicksRemaining); }
    public int burnTicksTotal() { return Math.max(0, burnTicksTotal); }

    public static boolean isFuel(ItemStack stack) { return !stack.isEmpty() && stack.getBurnTime(RecipeType.SMELTING) > 0; }

    public void tick() {
        if (!isEnabled() || burnTicksRemaining <= 0 && inventory.getStackInSlot(0).isEmpty()) return;
        int budget = Math.max(1, LaserExcavatorConfig.FUEL_BURN_TICKS_PER_SERVER_TICK.get());
        int energyPerTick = Math.max(1, LaserExcavatorConfig.FUEL_ENERGY_PER_BURN_TICK.get());

        while (budget > 0) {
            if (burnTicksRemaining <= 0 && (energyStorage.remainingCapacity() < energyPerTick || !consumeFuelItem())) return;
            int requested = Math.min(budget, burnTicksRemaining);
            int consumed = energyStorage.addInternalUnits(energyPerTick, requested);
            if (consumed <= 0) return;
            burnTicksRemaining -= consumed;
            budget -= consumed;
            if (consumed < requested) return;
        }
    }

    private boolean consumeFuelItem() {
        ItemStack stack = inventory.getStackInSlot(0);
        int burnTime = stack.getBurnTime(RecipeType.SMELTING);
        if (burnTime <= 0) return false;

        ItemStack remainder = stack.copyWithCount(1).getCraftingRemainingItem();
        if (!canStoreRemainderAfterConsumption(stack, remainder)) return false;

        ItemStack consumed = inventory.extractItem(0, 1, false);
        if (consumed.isEmpty()) return false;

        if (!remainder.isEmpty()) {
            ItemStack current = inventory.getStackInSlot(0);
            if (current.isEmpty()) inventory.setStackInSlot(0, remainder);
            else {
                ItemStack merged = current.copy();
                merged.grow(remainder.getCount());
                inventory.setStackInSlot(0, merged);
            }
        }

        burnTicksRemaining = burnTime;
        burnTicksTotal = burnTime;
        return true;
    }

    private boolean canStoreRemainderAfterConsumption(ItemStack fuel, ItemStack remainder) {
        if (remainder.isEmpty()) return true;
        int limit = inventory.getSlotLimit(0);
        if (fuel.getCount() == 1) return remainder.getCount() <= Math.min(limit, remainder.getMaxStackSize());
        return ItemStack.isSameItemSameComponents(fuel, remainder)
                && fuel.getCount() - 1 + remainder.getCount() <= Math.min(limit, fuel.getMaxStackSize());
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
        if (tag.contains(TAG_FUEL_INVENTORY, Tag.TAG_COMPOUND)) inventory.deserializeNBT(registries, tag.getCompound(TAG_FUEL_INVENTORY));
    }
}
