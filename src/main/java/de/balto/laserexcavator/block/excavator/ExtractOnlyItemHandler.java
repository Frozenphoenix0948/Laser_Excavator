package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Read/extract-only view of another IItemHandler.
 *
 * The excavator uses this wrapper for its external item capability so pipes can
 * pull mined material out without being able to push arbitrary items into the
 * machine's output inventory.
 */
public final class ExtractOnlyItemHandler implements IItemHandler {
    private final IItemHandler delegate;

    public ExtractOnlyItemHandler(IItemHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public int getSlots() {
        return delegate.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER);
        try {
            return stack;
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER, profile);
        }
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER);
        try {
            return delegate.extractItem(slot, amount, simulate);
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER, profile);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
