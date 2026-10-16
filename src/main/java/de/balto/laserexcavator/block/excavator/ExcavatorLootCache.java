package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

/** This cache caches compiled constant and fractional expected-value drops per level and loot mode. */
public final class ExcavatorLootCache {
    private static final Object UNCACHEABLE = new Object();
    private static final Map<ServerLevel, LevelCache> LEVEL_CACHES = new WeakHashMap<>();

    private ExcavatorLootCache() {}

    public static final class CachedDrops {
        private final List<ItemStack> reservationView;
        private final ItemStack representativePrototype;

        private final @Nullable ExcavatorLootTableInspector.Rule expectedRule;

        CachedDrops(List<Item> items, int[] counts, @Nullable ExcavatorLootTableInspector.Rule expectedRule) {
            this.expectedRule = expectedRule;
            ArrayList<ItemStack> stored = new ArrayList<>(counts.length);
            ItemStack representative = ItemStack.EMPTY;
            for (int i = 0; i < counts.length; i++) {
                int count = counts[i];
                if (count <= 0) continue;
                Item item = items.get(i);
                stored.add(new ItemStack(item, count));
                if (representative.isEmpty()) representative = new ItemStack(item, 1);
            }
            reservationView = List.copyOf(stored);
            representativePrototype = representative;
        }

        public List<ItemStack> reservationView() { return reservationView; }
        public ItemStack representativePrototype() { return representativePrototype; }

        public void commitExpectedAfterRemoval() {
            if (expectedRule != null) expectedRule.commit();
        }
    }

    /** One state-ID table per loot mode. */
    public static final class Table {
        private final Object[] entries;
        private final boolean silkTouch;
        private final int fortuneLevel;

        @SuppressWarnings("deprecation")
        private Table(boolean silkTouch, int fortuneTier) {
            entries = new Object[Block.BLOCK_STATE_REGISTRY.size()];
            this.silkTouch = silkTouch;
            this.fortuneLevel = silkTouch ? 0 : LaserExcavatorConfig.luckLevel(fortuneTier);
        }

        public @Nullable CachedDrops getCached(ServerLevel level, BlockState state) {
            int id = Block.getId(state);
            Object entry = entries[id];
            if (entry == null) {
                ExcavatorLootTableInspector.Rule compiled = ExcavatorLootTableInspector
                        .inspect(level, state, fortuneLevel, silkTouch).rule();
                if (compiled != null && !compiled.isFixed() && !LaserExcavatorConfig.ENABLE_EXPECTED_LOOT_DROPS.get())
                    compiled = null;
                ExcavatorProfiler.increment(compiled == null
                        ? ExcavatorProfiler.Counter.LOOT_TABLE_INSPECTION_FALLBACKS
                        : compiled.isFixed() ? ExcavatorProfiler.Counter.LOOT_TABLE_COMPILED_FIXED
                        : ExcavatorProfiler.Counter.LOOT_TABLE_COMPILED_EXPECTED);
                entry = compiled != null ? compiled : UNCACHEABLE;
                entries[id] = entry;
            }
            if (entry instanceof ExcavatorLootTableInspector.Rule rule) {
                if (!rule.isFixed() && !LaserExcavatorConfig.ENABLE_EXPECTED_LOOT_DROPS.get()) {
                    return null;
                }
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_HITS);
                return rule.preview();
            }
            return null;
        }

        private void clear() {
            Arrays.fill(entries, null);
        }
    }

    private static final class LevelCache {
        private final Table[] tables = {
                new Table(false, 0), new Table(false, 1), new Table(false, 2),
                new Table(false, 3), new Table(true, 0)
        };
        private final IdentityHashMap<BlockState, Block> silkTouchFilterEquivalents = new IdentityHashMap<>();

        private Table table(boolean silkTouch, int fortuneTier) {
            int index = silkTouch ? 4 : switch (fortuneTier) {
                case 1, 2, 3 -> fortuneTier;
                default -> 0;
            };
            return tables[index];
        }

        private void clear() {
            for (Table table : tables) table.clear();
            silkTouchFilterEquivalents.clear();
        }
    }

    public static Table table(ServerLevel level, boolean silkTouch, int fortuneTier) {
        return levelCache(level).table(silkTouch, fortuneTier);
    }

    public static void clear(MinecraftServer server) {
        synchronized (LEVEL_CACHES) {
            for (Map.Entry<ServerLevel, LevelCache> entry : LEVEL_CACHES.entrySet()) {
                if (entry.getKey().getServer() == server) entry.getValue().clear();
            }
        }
    }

    private static LevelCache levelCache(ServerLevel level) {
        synchronized (LEVEL_CACHES) {
            return LEVEL_CACHES.computeIfAbsent(level, ignored -> new LevelCache());
        }
    }

    public static @Nullable Block getSilkTouchFilterEquivalent(ServerLevel level, BlockPos pos, BlockState state, ItemStack silkTouchTool) {
        LevelCache cache = levelCache(level);
        Block cached = cache.silkTouchFilterEquivalents.get(state);
        if (cached != null || cache.silkTouchFilterEquivalents.containsKey(state)) return cached;

        List<ItemStack> drops = Block.getDrops(state, level, pos,
                state.hasBlockEntity() ? level.getBlockEntity(pos) : null, null, silkTouchTool);
        Block equivalent = drops.size() == 1 && drops.getFirst().getItem() instanceof BlockItem blockItem
                ? blockItem.getBlock() : null;
        cache.silkTouchFilterEquivalents.put(state, equivalent);
        return equivalent;
    }
}
