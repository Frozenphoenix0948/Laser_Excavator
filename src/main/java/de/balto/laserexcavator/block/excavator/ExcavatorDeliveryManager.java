package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Schedules mined drops until their transport arrival time and inserts them through
 * an indexed output inventory. In-flight deliveries do not reserve slots; failed or
 * partial arrival insertions keep the inserted portion and requeue only the remainder.
 */
public final class ExcavatorDeliveryManager {
    private static final String NBT_DELIVERIES = "PendingDeliveries";
    private static final String NBT_ARRIVAL = "ArrivalGameTime";
    private static final String NBT_STACKS = "Stacks";

    private final ItemStackHandler outputInventory;

    private final Long2ObjectOpenHashMap<DeliveryBucket> pendingDeliveriesByTick = new Long2ObjectOpenHashMap<>();
    private final ArrayList<DeliveryBucket> catchUpBuckets = new ArrayList<>();
    private int pendingDeliveryCount;
    private long lastProcessedGameTime = Long.MIN_VALUE;
    private boolean schedulerCatchUpRequired;

    private final Reference2IntOpenHashMap<Item> itemSlots = new Reference2IntOpenHashMap<>();
    private final Item[] indexedItemBySlot;
    private int emptySlotsMask;
    private boolean indexDirty = true;

    private final ItemStack[] capacityScratchStacks;
    private final int[] capacityScratchCounts;

    private long storageRevision;

    private boolean internalDeliveryInsertionInProgress;

    public ExcavatorDeliveryManager(ItemStackHandler outputInventory) {
        this.outputInventory = outputInventory;
        int slots = outputInventory.getSlots();
        if (slots <= 0 || slots >= Integer.SIZE) {
            throw new IllegalArgumentException("Indexed excavator output inventory requires 1-31 slots, got " + slots);
        }
        this.indexedItemBySlot = new Item[slots];
        this.capacityScratchStacks = new ItemStack[slots];
        this.capacityScratchCounts = new int[slots];
        itemSlots.defaultReturnValue(0);
    }

    public record ProcessResult(boolean changed, boolean becameEmpty) {
        public static final ProcessResult NONE = new ProcessResult(false, false);
    }

    private static final class PendingDelivery {
        private final long arrivalGameTime;
        private final @Nullable ExcavatorLootCache.CachedDrops cachedDrops;
        private final @Nullable List<ItemStack> stacks;
        private @Nullable PendingDelivery nextInBucket;

        private PendingDelivery(
                long arrivalGameTime,
                @Nullable ExcavatorLootCache.CachedDrops cachedDrops,
                @Nullable List<ItemStack> stacks
        ) {
            this.arrivalGameTime = arrivalGameTime;
            this.cachedDrops = cachedDrops;
            this.stacks = stacks;
        }
    }

    private static final class DeliveryBucket {
        private final long arrivalGameTime;
        private @Nullable PendingDelivery first;
        private @Nullable PendingDelivery last;
        private int size;

        private DeliveryBucket(long arrivalGameTime) {
            this.arrivalGameTime = arrivalGameTime;
        }

        private void add(PendingDelivery delivery) {
            delivery.nextInBucket = null;
            if (last == null) {
                first = delivery;
            } else {
                last.nextInBucket = delivery;
            }
            last = delivery;
            size++;
        }
    }

    public int pendingCount() {
        return pendingDeliveryCount;
    }

    public boolean hasPending() {
        return pendingDeliveryCount != 0;
    }

    public long storageRevision() {
        return storageRevision;
    }

    public boolean isInternalDeliveryInsertionInProgress() {
        return internalDeliveryInsertionInProgress;
    }

    public void onOutputSlotChanged(int slot) {
        storageRevision++;
        refreshChangedSlot(slot);
    }

    public void onInternalDeliverySlotChanged(int slot) {
        refreshChangedSlot(slot);
    }

    private void refreshChangedSlot(int slot) {
        if (slot < 0 || slot >= indexedItemBySlot.length) {
            indexDirty = true;
            return;
        }

        if (!indexDirty) {
            refreshSlotIndex(slot);
        }
    }

    /**
     * In-flight transports do not reserve storage.
     * The common single-stack case uses the index directly; multi-stack
     * loot uses reusable scratch state so all stacks "compete" for the same
     * capacity.
     */
    public boolean canFitAll(List<ItemStack> stacks) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.STORAGE_RESERVATION);
        try {
            ItemStack singleStack = null;
            int nonEmptyStackCount = 0;
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                singleStack = stack;
                if (++nonEmptyStackCount > 1) break;
            }
            if (nonEmptyStackCount == 0) return true;
            if (nonEmptyStackCount == 1) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SINGLE_STACK_RESERVATION_CHECKS);
                return canFitUsingIndex(singleStack);
            }
            return canFitUsingVirtualInventory(stacks);
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.STORAGE_RESERVATION, profile);
        }
    }

    public boolean enqueue(long arrivalGameTime, List<ItemStack> stacks) {
        List<ItemStack> stored = null;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (stored == null) stored = new ArrayList<>(Math.min(4, stacks.size()));
            stored.add(stack.copy());
        }
        if (stored == null || stored.isEmpty()) return false;

        addPending(new PendingDelivery(
                arrivalGameTime,
                null,
                List.copyOf(stored)
        ));
        return true;
    }

    public void enqueueCachedDrops(long arrivalGameTime, ExcavatorLootCache.CachedDrops drops) {
        addPending(new PendingDelivery(
                arrivalGameTime,
                drops,
                null
        ));
    }

    public ProcessResult processDue(long gameTime) {
        if (pendingDeliveryCount == 0) {
            lastProcessedGameTime = gameTime;
            schedulerCatchUpRequired = false;
            return ProcessResult.NONE;
        }

        if (lastProcessedGameTime != Long.MIN_VALUE
                && gameTime != lastProcessedGameTime + 1L) {
            // Normally the block entity is called every game tick. A gap means
            // it was unloaded/not ticking, so buckets whose exact tick passed
            // must be collected once instead of waiting for that tick forever.
            schedulerCatchUpRequired = true;
        }
        lastProcessedGameTime = gameTime;

        DeliveryBucket exactBucket = pendingDeliveriesByTick.remove(gameTime);
        catchUpBuckets.clear();
        if (schedulerCatchUpRequired) {
            // After an unload or tick gap, process overdue buckets from oldest arrival
            // tick to newest.
            if (exactBucket != null) {
                catchUpBuckets.add(exactBucket);
                exactBucket = null;
            }

            var iterator = pendingDeliveriesByTick.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getLongKey() <= gameTime) {
                    catchUpBuckets.add(entry.getValue());
                    iterator.remove();
                }
            }
            if (catchUpBuckets.size() > 1) {
                catchUpBuckets.sort((a, b) -> Long.compare(a.arrivalGameTime, b.arrivalGameTime));
            }
            schedulerCatchUpRequired = false;
        }

        if (exactBucket == null && catchUpBuckets.isEmpty()) {
            return ProcessResult.NONE;
        }

        int retryTicks = Math.max(1, LaserExcavatorConfig.DELIVERY_RETRY_TICKS.get());
        if (exactBucket != null) {
            processBucket(exactBucket, gameTime, retryTicks);
        }
        for (int i = 0; i < catchUpBuckets.size(); i++) {
            processBucket(catchUpBuckets.get(i), gameTime, retryTicks);
        }
        catchUpBuckets.clear();

        return new ProcessResult(true, pendingDeliveryCount == 0);
    }

    private void processBucket(DeliveryBucket bucket, long gameTime, int retryTicks) {
        pendingDeliveryCount -= bucket.size;

        PendingDelivery delivery = bucket.first;
        while (delivery != null) {
            PendingDelivery nextDelivery = delivery.nextInBucket;
            delivery.nextInBucket = null;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_BATCHES_PROCESSED);

            List<ItemStack> failed = null;
            if (delivery.cachedDrops != null) {
                for (ItemStack prototype : delivery.cachedDrops.reservationView()) {
                    if (prototype.isEmpty()) continue;
                    ItemStack remainder = insertIndexed(prototype.copy());
                    if (remainder.isEmpty()) {
                        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_STACKS_INSERTED);
                    } else {
                        if (failed == null) failed = new ArrayList<>();
                        failed.add(remainder.copy());
                    }
                }
            } else if (delivery.stacks != null) {
                for (ItemStack stack : delivery.stacks) {
                    if (stack.isEmpty()) continue;

                    ItemStack remainder = insertIndexed(stack.copy());
                    if (remainder.isEmpty()) {
                        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_STACKS_INSERTED);
                    } else {
                        if (failed == null) failed = new ArrayList<>();
                        failed.add(remainder.copy());
                    }
                }
            }

            if (failed != null && !failed.isEmpty()) {
                addPending(new PendingDelivery(
                        gameTime + retryTicks,
                        null,
                        List.copyOf(failed)
                ));
            }

            delivery = nextDelivery;
        }
    }

    private void addPending(PendingDelivery delivery) {
        DeliveryBucket bucket = pendingDeliveriesByTick.get(delivery.arrivalGameTime);
        if (bucket == null) {
            bucket = new DeliveryBucket(delivery.arrivalGameTime);
            pendingDeliveriesByTick.put(delivery.arrivalGameTime, bucket);
        }
        bucket.add(delivery);
        pendingDeliveryCount++;

        if (lastProcessedGameTime != Long.MIN_VALUE
                && delivery.arrivalGameTime <= lastProcessedGameTime) {
            schedulerCatchUpRequired = true;
        }
    }

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (pendingDeliveryCount == 0) return;

        ListTag deliveriesTag = new ListTag();
        for (DeliveryBucket bucket : pendingDeliveriesByTick.values()) {
            for (PendingDelivery delivery = bucket.first; delivery != null; delivery = delivery.nextInBucket) {
                CompoundTag deliveryTag = new CompoundTag();
                deliveryTag.putLong(NBT_ARRIVAL, delivery.arrivalGameTime);

                ListTag stacksTag = new ListTag();
                if (delivery.cachedDrops != null) {
                    for (ItemStack stack : delivery.cachedDrops.reservationView()) {
                        addSavedStack(stacksTag, stack, registries);
                    }
                } else if (delivery.stacks != null) {
                    for (ItemStack stack : delivery.stacks) {
                        addSavedStack(stacksTag, stack, registries);
                    }
                }

                if (!stacksTag.isEmpty()) {
                    deliveryTag.put(NBT_STACKS, stacksTag);
                    deliveriesTag.add(deliveryTag);
                }
            }
        }

        if (!deliveriesTag.isEmpty()) {
            tag.put(NBT_DELIVERIES, deliveriesTag);
        }
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        pendingDeliveriesByTick.clear();
        catchUpBuckets.clear();
        pendingDeliveryCount = 0;
        lastProcessedGameTime = Long.MIN_VALUE;
        schedulerCatchUpRequired = true;
        indexDirty = true;

        if (!tag.contains(NBT_DELIVERIES, Tag.TAG_LIST)) return;
        ListTag deliveriesTag = tag.getList(NBT_DELIVERIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < deliveriesTag.size(); i++) {
            CompoundTag deliveryTag = deliveriesTag.getCompound(i);
            if (!deliveryTag.contains(NBT_STACKS, Tag.TAG_LIST)) continue;

            ListTag stacksTag = deliveryTag.getList(NBT_STACKS, Tag.TAG_COMPOUND);
            List<ItemStack> stacks = new ArrayList<>(stacksTag.size());
            for (int j = 0; j < stacksTag.size(); j++) {
                ItemStack stack = ItemStack.parseOptional(registries, stacksTag.getCompound(j));
                if (!stack.isEmpty()) stacks.add(stack);
            }
            if (stacks.isEmpty()) continue;

            addPending(new PendingDelivery(
                    deliveryTag.getLong(NBT_ARRIVAL),
                    null,
                    List.copyOf(stacks)
            ));
        }
    }

    private static void addSavedStack(
            ListTag stacksTag,
            ItemStack stack,
            HolderLookup.Provider registries
    ) {
        if (stack.isEmpty()) return;
        Tag saved = stack.save(registries);
        if (saved instanceof CompoundTag compound) stacksTag.add(compound);
    }

    private boolean canFitUsingIndex(ItemStack stack) {
        ensureIndexFresh();
        int remaining = stack.getCount();

        int candidates = itemSlots.getInt(stack.getItem());
        while (candidates != 0 && remaining > 0) {
            int slot = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_ITEM_SLOT_CHECKS);

            ItemStack existing = outputInventory.getStackInSlot(slot);
            remaining -= availableSpaceInSlot(slot, existing, existing.getCount(), stack);
        }
        if (remaining <= 0) return true;

        int emptyCandidates = emptySlotsMask;
        while (emptyCandidates != 0 && remaining > 0) {
            int slot = Integer.numberOfTrailingZeros(emptyCandidates);
            emptyCandidates &= emptyCandidates - 1;
            remaining -= availableSpaceInSlot(slot, ItemStack.EMPTY, 0, stack);
        }
        return remaining <= 0;
    }

    private boolean canFitUsingVirtualInventory(List<ItemStack> incoming) {
        int slots = outputInventory.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack existing = outputInventory.getStackInSlot(slot);
            capacityScratchStacks[slot] = existing.isEmpty() ? ItemStack.EMPTY : existing;
            capacityScratchCounts[slot] = existing.isEmpty() ? 0 : existing.getCount();
        }

        for (ItemStack stack : incoming) {
            if (stack.isEmpty()) continue;
            int remaining = stack.getCount();

            for (int slot = 0; slot < slots && remaining > 0; slot++) {
                if (capacityScratchCounts[slot] <= 0) continue;
                ItemStack existing = capacityScratchStacks[slot];
                int free = availableSpaceInSlot(slot, existing, capacityScratchCounts[slot], stack);
                int moved = Math.min(remaining, free);
                capacityScratchCounts[slot] += moved;
                remaining -= moved;
            }

            for (int slot = 0; slot < slots && remaining > 0; slot++) {
                if (capacityScratchCounts[slot] != 0) continue;
                int free = availableSpaceInSlot(slot, ItemStack.EMPTY, 0, stack);
                int moved = Math.min(remaining, free);
                if (moved <= 0) continue;
                capacityScratchStacks[slot] = stack;
                capacityScratchCounts[slot] = moved;
                remaining -= moved;
            }

            if (remaining > 0) return false;
        }
        return true;
    }

    private int availableSpaceInSlot(int slot, ItemStack existing, int existingCount, ItemStack incoming) {
        if (existingCount > 0) {
            if (!ItemStack.isSameItemSameComponents(existing, incoming)) return 0;
            int limit = Math.min(outputInventory.getSlotLimit(slot), existing.getMaxStackSize());
            return Math.max(0, limit - existingCount);
        }

        if (!outputInventory.isItemValid(slot, incoming)) return 0;
        return Math.max(0, Math.min(outputInventory.getSlotLimit(slot), incoming.getMaxStackSize()));
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
            int limit = Math.min(outputInventory.getSlotLimit(slot), existing.getMaxStackSize());
            if (existing.getCount() >= limit) continue;
            remainder = insertIntoKnownSlot(slot, remainder);
        }

        int emptyCandidates = emptySlotsMask;
        while (!remainder.isEmpty() && emptyCandidates != 0) {
            int slot = Integer.numberOfTrailingZeros(emptyCandidates);
            emptyCandidates &= emptyCandidates - 1;
            if (!outputInventory.isItemValid(slot, remainder)) continue;

            int before = remainder.getCount();
            remainder = insertIntoKnownSlot(slot, remainder);
            if (remainder.getCount() < before) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DELIVERY_INDEX_EMPTY_SLOT_USES);
            }
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
        if (!indexDirty) return;
        rebuildIndex();
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

    /**
     * Updates only structural slot state. Count-only mutations leave the item
     * identity unchanged and return before touching either bitmask/map.
     */
    private void refreshSlotIndex(int slot) {
        Item oldItem = indexedItemBySlot[slot];
        ItemStack current = outputInventory.getStackInSlot(slot);
        Item newItem = current.isEmpty() ? null : current.getItem();

        if (oldItem == newItem) return;

        int bit = 1 << slot;
        if (oldItem != null) {
            int oldMask = itemSlots.getInt(oldItem) & ~bit;
            if (oldMask == 0) itemSlots.removeInt(oldItem);
            else itemSlots.put(oldItem, oldMask);
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
