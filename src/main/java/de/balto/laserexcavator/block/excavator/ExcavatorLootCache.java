package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.LaserExcavator;
import net.minecraft.core.registries.BuiltInRegistries;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Runtime cache for deterministic excavator drops. */
public final class ExcavatorLootCache {
    private static final Object UNCACHEABLE = new Object();
    private static final Map<ServerLevel, LevelCache> LEVEL_CACHES = new WeakHashMap<>();

    private ExcavatorLootCache() {}

    public static final class CachedDrops {
        private final List<ItemStack> reservationView;
        private final ItemStack representativePrototype;

        // Non-null only for an EXPECTED preview. The reference must survive
        // auto-smelting, which may require a different delivery representation.
        private final @Nullable ExcavatorLootTableInspector.Rule expectedRule;

        CachedDrops(List<Item> items, int[] counts,
                    @Nullable ExcavatorLootTableInspector.Rule expectedRule) {
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
            // The arrays and new stacks are private to this constructor, so
            // no external mutable input needs a defensive ItemStack copy.
            reservationView = List.copyOf(stored);
            representativePrototype = representative;
        }

        public List<ItemStack> reservationView() { return reservationView; }
        public ItemStack representativePrototype() { return representativePrototype; }

        /** Called only after successful block removal, not when capacity is full. */
        public void commitExpectedAfterRemoval() {
            if (expectedRule != null) expectedRule.commit();
        }
    }

    /** One state-ID table per loot mode. */
    public static final class Table {
        private final Object[] entries;
        // Stored alongside uncompiled states so profiling after warm-up can
        // still attribute actual vanilla loot calls to the original rejection.
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
                // Inspect ALL states first, including deterministic ones.
                // This happens once per world/mode/state, on the server thread.
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LOOT_TABLE_INSPECTIONS);
                ExcavatorLootTableInspector.Inspection inspected =
                        ExcavatorLootTableInspector.inspect(level, state, fortuneLevel, silkTouch);
                ExcavatorLootTableInspector.Rule compiled = inspected.rule();
                boolean expectedDisabled = compiled != null && !compiled.isFixed()
                        && !LaserExcavatorConfig.ENABLE_EXPECTED_LOOT_DROPS.get();
                if (expectedDisabled) {
                    compiled = null;
                }
                if (compiled == null) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LOOT_TABLE_INSPECTION_FALLBACKS);
                } else {
                    ExcavatorProfiler.increment(compiled.isFixed()
                            ? ExcavatorProfiler.Counter.LOOT_TABLE_COMPILED_FIXED
                            : ExcavatorProfiler.Counter.LOOT_TABLE_COMPILED_EXPECTED);
                }
                // An unsupported table may depend on location, block entities,
                // modded hooks or rare randomness. Never certify it using a
                // sequence of identical observations; vanilla is authoritative.
                entry = compiled != null ? compiled : UNCACHEABLE;
                entries[id] = entry;
            }
            if (entry instanceof ExcavatorLootTableInspector.Rule rule) {
                if (!rule.isFixed() && !LaserExcavatorConfig.ENABLE_EXPECTED_LOOT_DROPS.get()) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_MISSES);
                    return null;
                }
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_HITS);
                ExcavatorProfiler.increment(rule.isFixed()
                        ? ExcavatorProfiler.Counter.LOOT_TABLE_FIXED_USES
                        : ExcavatorProfiler.Counter.LOOT_TABLE_EXPECTED_USES);
                return rule.preview();
            }
            if (entry instanceof CachedDrops drops) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_HITS);
                return drops;
            }
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_MISSES);
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

    /** Cached Silk Touch equivalent for filter matching. */
    public static @Nullable Block getSilkTouchFilterEquivalent(ServerLevel level, BlockPos pos, BlockState state, ItemStack silkTouchTool) {
        LevelCache cache = levelCache(level);
        if (cache.silkTouchFilterEquivalents.containsKey(state)) return cache.silkTouchFilterEquivalents.get(state);

        List<ItemStack> drops = Block.getDrops(state, level, pos, state.hasBlockEntity() ? level.getBlockEntity(pos) : null, null, silkTouchTool);
        Block equivalent = drops.size() == 1 && drops.get(0).getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        cache.silkTouchFilterEquivalents.put(state, equivalent);
        return equivalent;
    }
}
