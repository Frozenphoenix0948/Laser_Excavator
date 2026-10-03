package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Runtime cache for deterministic excavator drops. */
public final class ExcavatorLootCache {
    private static final int CONFIRMATIONS_REQUIRED = 512;
    private static final Object UNCACHEABLE = new Object();
    private static final Map<ServerLevel, LevelCache> LEVEL_CACHES = new WeakHashMap<>();

    private ExcavatorLootCache() {}

    public static final class CachedDrops {
        private final List<ItemStack> reservationView;
        private final ItemStack representativePrototype;

        private CachedDrops(List<ItemStack> observedDrops) {
            ArrayList<ItemStack> stored = new ArrayList<>(observedDrops.size());
            ItemStack representative = ItemStack.EMPTY;
            for (ItemStack stack : observedDrops) {
                if (stack.isEmpty()) continue;
                ItemStack copy = stack.copy();
                stored.add(copy);
                if (representative.isEmpty()) {
                    representative = copy.copy();
                    representative.setCount(1);
                }
            }
            reservationView = List.copyOf(stored);
            representativePrototype = representative;
        }

        public List<ItemStack> reservationView() { return reservationView; }
        public ItemStack representativePrototype() { return representativePrototype; }
    }

    private static final class LearningEntry {
        private final CachedDrops drops;
        private int observations = 1;

        private LearningEntry(List<ItemStack> drops) { this.drops = new CachedDrops(drops); }
    }

    /** One state-ID table per loot mode. */
    public static final class Table {
        private final Object[] entries;

        @SuppressWarnings("deprecation")
        private Table() { entries = new Object[Block.BLOCK_STATE_REGISTRY.size()]; }

        public @Nullable CachedDrops getCached(BlockState state) {
            Object entry = entries[Block.getId(state)];
            if (entry instanceof CachedDrops drops) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_HITS);
                return drops;
            }
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_MISSES);
            return null;
        }

        public @Nullable CachedDrops observe(BlockState state, List<ItemStack> observedDrops) {
            int stateId = Block.getId(state);
            Object entry = entries[stateId];
            if (entry == UNCACHEABLE) return null;
            if (entry instanceof CachedDrops drops) return drops;

            if (entry == null) {
                if (state.hasBlockEntity()) {
                    entries[stateId] = UNCACHEABLE;
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_REJECTIONS);
                    return null;
                }
                entries[stateId] = new LearningEntry(observedDrops);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_LEARNING_OBSERVATIONS);
                return null;
            }

            LearningEntry learning = (LearningEntry) entry;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_LEARNING_OBSERVATIONS);
            if (!sameDrops(learning.drops.reservationView(), observedDrops)) {
                entries[stateId] = UNCACHEABLE;
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_REJECTIONS);
                return null;
            }
            if (++learning.observations < CONFIRMATIONS_REQUIRED) return null;

            entries[stateId] = learning.drops;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_PROMOTIONS);
            return learning.drops;
        }

        private void clear() { Arrays.fill(entries, null); }
    }

    private static final class LevelCache {
        private final Table[] tables = {new Table(), new Table(), new Table(), new Table(), new Table()};
        private final IdentityHashMap<BlockState, Block> silkTouchFilterEquivalents = new IdentityHashMap<>();

        private Table table(boolean silkTouch, int fortuneTier) {
            int index = silkTouch ? 4 : switch (fortuneTier) { case 1 -> 1; case 2 -> 2; case 3 -> 3; default -> 0; };
            return tables[index];
        }

        private void clear() {
            for (Table table : tables) table.clear();
            silkTouchFilterEquivalents.clear();
        }
    }

    public static Table table(ServerLevel level, boolean silkTouch, int fortuneTier) { return levelCache(level).table(silkTouch, fortuneTier); }

    public static void clear(MinecraftServer server) {
        synchronized (LEVEL_CACHES) {
            for (Map.Entry<ServerLevel, LevelCache> entry : LEVEL_CACHES.entrySet()) {
                if (entry.getKey().getServer() == server) entry.getValue().clear();
            }
        }
    }

    private static LevelCache levelCache(ServerLevel level) {
        synchronized (LEVEL_CACHES) { return LEVEL_CACHES.computeIfAbsent(level, ignored -> new LevelCache()); }
    }

    private static boolean sameDrops(List<ItemStack> cached, List<ItemStack> observed) {
        int observedNonEmpty = 0;
        for (ItemStack stack : observed) if (!stack.isEmpty()) observedNonEmpty++;
        if (cached.size() != observedNonEmpty) return false;

        int cachedIndex = 0;
        for (ItemStack observedStack : observed) {
            if (observedStack.isEmpty()) continue;
            ItemStack cachedStack = cached.get(cachedIndex++);
            if (cachedStack.getCount() != observedStack.getCount() || !ItemStack.isSameItemSameComponents(cachedStack, observedStack)) return false;
        }
        return true;
    }

    /** Cached Silk Touch equivalent for filter matching. */
    public static @Nullable Block getSilkTouchFilterEquivalent(ServerLevel level, BlockPos pos, BlockState state, ItemStack silkTouchTool) {
        LevelCache cache = levelCache(level);
        if (cache.silkTouchFilterEquivalents.containsKey(state)) return cache.silkTouchFilterEquivalents.get(state);

        List<ItemStack> drops = Block.getDrops(state, level, pos, state.hasBlockEntity() ? level.getBlockEntity(pos) : null, null, silkTouchTool);
        Block equivalent = drops.size() == 1 && drops.getFirst().getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        cache.silkTouchFilterEquivalents.put(state, equivalent);
        return equivalent;
    }
}
