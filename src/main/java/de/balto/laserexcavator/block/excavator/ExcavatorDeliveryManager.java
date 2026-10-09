package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * This Manager handles the deliveries so everything that happens between
 * breaking a block and it being put into the inventory.
 */
public final class ExcavatorDeliveryManager {
    private static final String NBT_DELIVERIES = "PendingDeliveries";
    private static final String NBT_ARRIVAL = "ArrivalGameTime";
    private static final String NBT_STACK = "Stack";
    private static final String NBT_STACKS = "Stacks"; // To support old version.

    private static final IdentityHashMap<Item, ArrayList<WeakReference<ItemStack>>> STACK_CACHE = new IdentityHashMap<>();
    private static int stackCacheMisses;

    private final ItemStackHandler outputInventory;
    private final Long2ObjectOpenHashMap<ArrayList<Delivery>> pendingByTick = new Long2ObjectOpenHashMap<>();
    private final ArrayList<Long> catchUpTicks = new ArrayList<>();
    private final Reference2IntOpenHashMap<Item> itemSlots = new Reference2IntOpenHashMap<>();
    private final Item[] indexedItemBySlot;
    private int emptySlotsMask, pendingDeliveryCount;
    private boolean indexDirty = true, schedulerCatchUpRequired, internalDeliveryInsertionInProgress;
    private long lastProcessedGameTime = Long.MIN_VALUE, storageRevision;

    public ExcavatorDeliveryManager(ItemStackHandler outputInventory) {
        this.outputInventory = outputInventory;
        int slots = outputInventory.getSlots();
        if (slots <= 0 || slots >= Integer.SIZE) throw new IllegalArgumentException("Indexed excavator output inventory requires 1-31 slots, got " + slots);
        indexedItemBySlot = new Item[slots];
        itemSlots.defaultReturnValue(0);
    }

    public record ProcessResult(boolean changed, boolean becameEmpty) {
        public static final ProcessResult NONE = new ProcessResult(false, false);
        private static final ProcessResult CHANGED = new ProcessResult(true, false), EMPTY = new ProcessResult(true, true);
    }

    private static final class Delivery {
        private final ItemStack item;
        private int count;
        private Delivery(ItemStack item, int count) { this.item = item; this.count = count; }
    }

    public int pendingCount() { return pendingDeliveryCount; }
    public boolean hasPending() { return pendingDeliveryCount != 0; }
    public long storageRevision() { return storageRevision; }
    public boolean isInternalDeliveryInsertionInProgress() { return internalDeliveryInsertionInProgress; }

    public void onOutputSlotChanged(int slot) {
        storageRevision++;
        refreshChangedSlot(slot);
    }

    public void onInternalDeliverySlotChanged(int slot) { refreshChangedSlot(slot); }

    private void refreshChangedSlot(int slot) {
        if (slot < 0 || slot >= indexedItemBySlot.length) indexDirty = true;
        else if (!indexDirty) refreshSlotIndex(slot);
    }

    public boolean canFitAll(List<ItemStack> stacks) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.STORAGE_RESERVATION);
        try {
            if (stacks.size() == 1 && !stacks.get(0).isEmpty()) ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SINGLE_STACK_RESERVATION_CHECKS);
            ensureIndexFresh();
            if (emptySlotsMask != 0) return true;
            for (ItemStack stack : stacks) if (!stack.isEmpty() && !canMerge(stack)) return false;
            return true;
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.STORAGE_RESERVATION, profile);
        }
    }

    public boolean enqueue(long arrivalGameTime, List<ItemStack> stacks) {
        boolean added = false;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            addNew(arrivalGameTime, stack);
            added = true;
        }
        return added;
    }

    public void enqueueCachedDrops(long arrivalGameTime, ExcavatorLootCache.CachedDrops drops) {
        for (ItemStack stack : drops.reservationView()) {
            if (stack.isEmpty()) continue;
            schedule(arrivalGameTime, new Delivery(stack, stack.getCount()));
            pendingDeliveryCount++;
        }
    }

    public ProcessResult processDue(long gameTime) {
        if (pendingDeliveryCount == 0) {
            lastProcessedGameTime = gameTime;
            schedulerCatchUpRequired = false;
            return ProcessResult.NONE;
        }
        if (lastProcessedGameTime != Long.MIN_VALUE && gameTime != lastProcessedGameTime + 1L) schedulerCatchUpRequired = true;
        lastProcessedGameTime = gameTime;
        boolean changed = false;

        if (schedulerCatchUpRequired) {
            catchUpTicks.clear();
            for (long tick : pendingByTick.keySet()) if (tick <= gameTime) catchUpTicks.add(tick);
            catchUpTicks.sort(Long::compare);
            for (long tick : catchUpTicks) {
                ArrayList<Delivery> deliveries = pendingByTick.remove(tick);
                if (deliveries != null) {
                    process(deliveries, gameTime);
                    changed = true;
                }
            }
            catchUpTicks.clear();
            schedulerCatchUpRequired = false;
        } else {
            ArrayList<Delivery> deliveries = pendingByTick.remove(gameTime);
            if (deliveries != null) {
                process(deliveries, gameTime);
                changed = true;
            }
        }
        if (!changed) return ProcessResult.NONE;
        return pendingDeliveryCount == 0 ? ProcessResult.EMPTY : ProcessResult.CHANGED;
    }

    private void process(ArrayList<Delivery> deliveries, long gameTime) {
        int retryTicks = 0;
        for (int i = 0; i < deliveries.size(); i++) {
            Delivery delivery = deliveries.get(i);
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_BATCHES_PROCESSED);
            ItemStack remainder = insertIndexed(materialize(delivery));
            if (remainder.isEmpty()) {
                pendingDeliveryCount--;
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_STACKS_INSERTED);
            } else {
                if (retryTicks == 0) retryTicks = Math.max(1, LaserExcavatorConfig.DELIVERY_RETRY_TICKS.get());
                delivery.count = remainder.getCount();
                schedule(gameTime + retryTicks, delivery);
            }
        }
    }

    private void addNew(long tick, ItemStack stack) {
        schedule(tick, new Delivery(sharedStack(stack), stack.getCount()));
        pendingDeliveryCount++;
    }

    private void schedule(long tick, Delivery delivery) {
        ArrayList<Delivery> deliveries = pendingByTick.get(tick);
        if (deliveries == null) {
            deliveries = new ArrayList<>();
            pendingByTick.put(tick, deliveries);
        }
        deliveries.add(delivery);
        if (lastProcessedGameTime != Long.MIN_VALUE && tick <= lastProcessedGameTime) schedulerCatchUpRequired = true;
    }

    private static ItemStack materialize(Delivery delivery) {
        ItemStack stack = delivery.item.copy();
        stack.setCount(delivery.count);
        return stack;
    }

    private static synchronized ItemStack sharedStack(ItemStack stack) {
        ArrayList<WeakReference<ItemStack>> variants = STACK_CACHE.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>(1));
        for (int i = variants.size() - 1; i >= 0; i--) {
            ItemStack cached = variants.get(i).get();
            if (cached == null) variants.remove(i);
            else if (ItemStack.isSameItemSameComponents(cached, stack)) return cached;
        }
        ItemStack cached = stack.copy();
        cached.setCount(1);
        variants.add(new WeakReference<>(cached));
        if ((++stackCacheMisses & 4095) == 0) cleanStackCache();
        return cached;
    }

    private static void cleanStackCache() {
        var iterator = STACK_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            ArrayList<WeakReference<ItemStack>> variants = iterator.next().getValue();
            variants.removeIf(ref -> ref.get() == null);
            if (variants.isEmpty()) iterator.remove();
        }
    }

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (pendingDeliveryCount == 0) return;
        ListTag deliveriesTag = new ListTag();
        for (var entry : pendingByTick.long2ObjectEntrySet()) {
            for (Delivery delivery : entry.getValue()) {
                CompoundTag deliveryTag = new CompoundTag();
                deliveryTag.putLong(NBT_ARRIVAL, entry.getLongKey());
                Tag saved = materialize(delivery).save(registries);
                if (saved instanceof CompoundTag compound) {
                    deliveryTag.put(NBT_STACK, compound);
                    deliveriesTag.add(deliveryTag);
                }
            }
        }
        if (!deliveriesTag.isEmpty()) tag.put(NBT_DELIVERIES, deliveriesTag);
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        pendingByTick.clear();
        catchUpTicks.clear();
        pendingDeliveryCount = 0;
        lastProcessedGameTime = Long.MIN_VALUE;
        schedulerCatchUpRequired = true;
        indexDirty = true;
        ListTag deliveriesTag = tag.getListOrEmpty(NBT_DELIVERIES);
        for (int i = 0; i < deliveriesTag.size(); i++) {
            CompoundTag deliveryTag = deliveriesTag.getCompoundOrEmpty(i);
            long tick = deliveryTag.getLongOr(NBT_ARRIVAL, 0L);
            CompoundTag stackTag = deliveryTag.getCompound(NBT_STACK).orElse(null);
            if (stackTag != null) {
                ItemStack stack = parseStack(registries, stackTag);
                if (!stack.isEmpty()) addNew(tick, stack);
                continue;
            }
            ListTag stacksTag = deliveryTag.getListOrEmpty(NBT_STACKS);
            for (int j = 0; j < stacksTag.size(); j++) {
                ItemStack stack = parseStack(registries, stacksTag.getCompoundOrEmpty(j));
                if (!stack.isEmpty()) addNew(tick, stack);
            }
        }
    }

    private static ItemStack parseStack(HolderLookup.Provider registries, CompoundTag compound) {
        return ItemStack.OPTIONAL_CODEC
                .parse(registries.createSerializationContext(NbtOps.INSTANCE), compound)
                .result().orElse(ItemStack.EMPTY);
    }

    private boolean canMerge(ItemStack stack) {
        int candidates = itemSlots.getInt(stack.getItem());
        while (candidates != 0) {
            int slot = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_ITEM_SLOT_CHECKS);
            ItemStack existing = outputInventory.getStackInSlot(slot);
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < Math.min(outputInventory.getSlotLimit(slot), existing.getMaxStackSize())) return true;
        }
        return false;
    }

    private ItemStack insertIndexed(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ensureIndexFresh();
        ItemStack remainder = stack;
        int candidates = itemSlots.getInt(stack.getItem());
        while (candidates != 0 && !remainder.isEmpty()) {
            int slot = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_ITEM_SLOT_CHECKS);
            ItemStack existing = outputInventory.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameComponents(existing, remainder)) continue;
            if (existing.getCount() >= Math.min(outputInventory.getSlotLimit(slot), existing.getMaxStackSize())) continue;
            remainder = insertIntoKnownSlot(slot, remainder);
        }

        int empty = emptySlotsMask;
        while (!remainder.isEmpty() && empty != 0) {
            int slot = Integer.numberOfTrailingZeros(empty);
            empty &= empty - 1;
            if (!outputInventory.isItemValid(slot, remainder)) continue;
            int before = remainder.getCount();
            remainder = insertIntoKnownSlot(slot, remainder);
            if (remainder.getCount() < before) ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_EMPTY_SLOT_USES);
        }
        return remainder;
    }

    private ItemStack insertIntoKnownSlot(int slot, ItemStack stack) {
        internalDeliveryInsertionInProgress = true;
        try {
            return outputInventory.insertItem(slot, stack, false);
        } finally {
            internalDeliveryInsertionInProgress = false;
        }
    }

    private void ensureIndexFresh() {
        if (indexDirty) rebuildIndex();
    }

    private void rebuildIndex() {
        itemSlots.clear();
        emptySlotsMask = 0;
        for (int slot = 0; slot < outputInventory.getSlots(); slot++) {
            ItemStack stack = outputInventory.getStackInSlot(slot);
            int bit = 1 << slot;
            if (stack.isEmpty()) {
                indexedItemBySlot[slot] = null;
                emptySlotsMask |= bit;
            } else {
                Item item = stack.getItem();
                indexedItemBySlot[slot] = item;
                itemSlots.put(item, itemSlots.getInt(item) | bit);
            }
        }
        indexDirty = false;
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_REBUILDS);
    }

    private void refreshSlotIndex(int slot) {
        Item oldItem = indexedItemBySlot[slot];
        ItemStack current = outputInventory.getStackInSlot(slot);
        Item newItem = current.isEmpty() ? null : current.getItem();
        if (oldItem == newItem) return;

        int bit = 1 << slot;
        if (oldItem != null) {
            int mask = itemSlots.getInt(oldItem) & ~bit;
            if (mask == 0) itemSlots.removeInt(oldItem); else itemSlots.put(oldItem, mask);
        }
        if (newItem == null) {
            indexedItemBySlot[slot] = null;
            emptySlotsMask |= bit;
        } else {
            indexedItemBySlot[slot] = newItem;
            emptySlotsMask &= ~bit;
            itemSlots.put(newItem, itemSlots.getInt(newItem) | bit);
        }
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_SLOT_UPDATES);
    }
}
