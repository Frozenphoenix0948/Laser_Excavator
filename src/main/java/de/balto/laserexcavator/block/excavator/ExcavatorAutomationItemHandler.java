package de.balto.laserexcavator.block.excavator;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Automation-facing item handler for the excavator.
 *
 * Output slots are extract-only, while the final virtual slot maps to the
 * machine's fuel slot and accepts insertion only. This lets pipes and hoppers
 * feed furnace fuel without allowing automation to push items into output
 * storage or pull the currently loaded fuel back out.
 */
public final class ExcavatorAutomationItemHandler implements IItemHandler {
    private final IItemHandler output;
    private final IItemHandler fuel;

    public ExcavatorAutomationItemHandler(IItemHandler output, IItemHandler fuel) {
        this.output = output;
        this.fuel = fuel;
    }

    @Override
    public int getSlots() {
        return output.getSlots() + fuel.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (isOutputSlot(slot)) {
            return output.getStackInSlot(slot);
        }
        int fuelSlot = toFuelSlot(slot);
        return fuelSlot >= 0 ? fuel.getStackInSlot(fuelSlot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (isOutputSlot(slot)) {
            return stack;
        }
        int fuelSlot = toFuelSlot(slot);
        return fuelSlot >= 0 ? fuel.insertItem(fuelSlot, stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (isOutputSlot(slot)) {
            return output.extractItem(slot, amount, simulate);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        if (isOutputSlot(slot)) {
            return output.getSlotLimit(slot);
        }
        int fuelSlot = toFuelSlot(slot);
        return fuelSlot >= 0 ? fuel.getSlotLimit(fuelSlot) : 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (isOutputSlot(slot)) {
            return false;
        }
        int fuelSlot = toFuelSlot(slot);
        return fuelSlot >= 0 && fuel.isItemValid(fuelSlot, stack);
    }

    private boolean isOutputSlot(int slot) {
        return slot >= 0 && slot < output.getSlots();
    }

    private int toFuelSlot(int slot) {
        int fuelSlot = slot - output.getSlots();
        return fuelSlot >= 0 && fuelSlot < fuel.getSlots() ? fuelSlot : -1;
    }
}
