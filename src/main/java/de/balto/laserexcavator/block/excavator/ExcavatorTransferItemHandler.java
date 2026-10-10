package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.Objects;

public final class ExcavatorTransferItemHandler implements ResourceHandler<ItemResource> {
    private final ItemStackHandler output;
    private final ItemStackHandler fuel;
    private final boolean allowFuel;
    private final SlotJournal[] journals;

    public ExcavatorTransferItemHandler(ItemStackHandler output, ItemStackHandler fuel, boolean allowFuel) {
        this.output = output;
        this.fuel = fuel;
        this.allowFuel = allowFuel;
        journals = new SlotJournal[size()];
        for (int i = 0; i < journals.length; i++) journals[i] = new SlotJournal(i);
    }

    @Override public int size() { return output.getSlots() + (allowFuel ? fuel.getSlots() : 0); }

    private ItemStackHandler inventory(int index) {
        Objects.checkIndex(index, size());
        return index < output.getSlots() ? output : fuel;
    }

    private int slot(int index) { return index < output.getSlots() ? index : index - output.getSlots(); }

    @Override
    public ItemResource getResource(int index) {
        return ItemResource.of(inventory(index).getStackInSlot(slot(index)));
    }

    @Override
    public long getAmountAsLong(int index) {
        return inventory(index).getStackInSlot(slot(index)).getCount();
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        var handler = inventory(index);
        return resource.isEmpty() ? 0 : Math.min(handler.getSlotLimit(slot(index)), resource.getMaxStackSize());
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        var handler = inventory(index);
        return !resource.isEmpty() && index >= output.getSlots() && handler.isItemValid(slot(index), resource.toStack());
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER);
        try {
            if (amount <= 0 || !isValid(index, resource)) return 0;
            int requested = Math.min(amount, (int) getCapacityAsLong(index, resource));
            ItemStack stack = resource.toStack(requested);
            int accepted = requested - fuel.insertItem(slot(index), stack, true).getCount();
            if (accepted <= 0) return 0;
            journals[index].update(transaction);
            return accepted - fuel.insertItem(slot(index), resource.toStack(accepted), false).getCount();
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER, profile);
        }
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER);
        try {
            var handler = inventory(index);
            if (amount <= 0 || resource.isEmpty() || index >= output.getSlots()) return 0;
            ItemStack current = handler.getStackInSlot(slot(index));
            if (!resource.equals(ItemResource.of(current))) return 0;
            int extracted = handler.extractItem(slot(index), amount, true).getCount();
            if (extracted <= 0) return 0;
            journals[index].update(transaction);
            return handler.extractItem(slot(index), extracted, false).getCount();
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.EXTERNAL_ITEM_HANDLER, profile);
        }
    }

    private final class SlotJournal extends SnapshotJournal<ItemStack> {
        private final int index;

        SlotJournal(int index) { this.index = index; }

        void update(TransactionContext transaction) { updateSnapshots(transaction); }

        @Override protected ItemStack createSnapshot() {
            return inventory(index).getStackInSlot(slot(index)).copy();
        }

        @Override protected void revertToSnapshot(ItemStack snapshot) {
            inventory(index).setStackInSlot(slot(index), snapshot);
        }
    }
}
