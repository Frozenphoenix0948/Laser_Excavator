package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-wide runtime cache for deterministic excavator block drops.
 *
 * Each cache entry is keyed by the canonical BlockState, Silk Touch
 * state and Fortune level. Unknown combinations are learned from real loot-table
 * results. A result must remain identical for CONFIRMATIONS_REQUIRED
 * observations before it is promoted to the fast cache; any mismatch marks that
 * exact state/tool combination permanently uncacheable for the current server
 * session. This keeps randomized drops such as gravel/leaves out of the fast
 * path without maintaining a block-ID list.
 *
 * Non-Silk ore states are conservatively rejected up front. Silk Touch uses
 * a separate key and can therefore still become cacheable when its result is
 * deterministic. Block-entity states are also rejected because their drops may
 * depend on NBT/inventory contents that are not represented by the cache key.
 */
public final class ExcavatorLootCache {
    /**
     * Common terrain reaches this very quickly under hundreds of excavators,
     * while chance-based loot is extremely unlikely to survive this many real
     * observations without revealing a mismatch.
     */
    private static final int CONFIRMATIONS_REQUIRED = 256;
    private static final int AUDIT_INTERVAL_HITS = 1_024;

    private static final byte LEARNING = 0;
    private static final byte CACHED = 1;
    private static final byte UNCACHEABLE = 2;

    private static final TagKey<Block> COMMON_ORES = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores")
    );
    private static final TagKey<Block> FORGE_ORES_COMPAT = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("forge", "ores")
    );

    /** Weak server keys make integrated/dedicated-server restarts self-cleaning. */
    private static final Map<MinecraftServer, ServerCache> SERVER_CACHES =
            Collections.synchronizedMap(new WeakHashMap<>());

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

        public List<ItemStack> reservationView() {
            return reservationView;
        }

        public ItemStack representativePrototype() {
            return representativePrototype;
        }

    }

    private static final class Entry {
        private byte state = LEARNING;
        private int confirmations;
        private @Nullable CachedDrops candidate;
        private @Nullable CachedDrops cached;
        private int hitsSinceAudit;
        private boolean auditPending;
    }

    private static final class ServerCache {
        /** BlockStates are canonical StateHolder instances, so identity keys avoid hashing properties. */
        private final IdentityHashMap<BlockState, Int2ObjectOpenHashMap<Entry>> byState = new IdentityHashMap<>();
        /** Map specifically for blocks broken with silktouch, used only for the filter matching*/
        private final IdentityHashMap<BlockState, Block> silkTouchFilterEquivalents = new IdentityHashMap<>();

        private Entry entry(BlockState state, int toolKey) {
            Int2ObjectOpenHashMap<Entry> byTool = byState.get(state);
            if (byTool == null) {
                byTool = new Int2ObjectOpenHashMap<>(2);
                byState.put(state, byTool);
            }
            Entry entry = byTool.get(toolKey);
            if (entry == null) {
                entry = new Entry();
                byTool.put(toolKey, entry);
            }
            return entry;
        }
    }

    public static @Nullable CachedDrops get(
            ServerLevel level,
            BlockState state,
            boolean silkTouch,
            int fortuneLevel
    ) {
        ServerCache cache = serverCache(level.getServer());
        int toolKey = toolKey(silkTouch, fortuneLevel);
        Entry entry = cache.entry(state, toolKey);

        if (entry.state == CACHED) {
            if (++entry.hitsSinceAudit >= AUDIT_INTERVAL_HITS) {
                entry.hitsSinceAudit = 0;
                entry.auditPending = true;
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_AUDITS);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_MISSES);
                return null;
            }
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_HITS);
            return entry.cached;
        }

        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_MISSES);
        return null;
    }

    /**
     * Feeds one real loot-table result into the learning state. The returned
     * descriptor is non-null only when this observation promotes the combination
     * to the deterministic cache.
     */
    public static @Nullable CachedDrops observe(
            ServerLevel level,
            BlockState state,
            boolean silkTouch,
            int fortuneLevel,
            List<ItemStack> drops
    ) {
        ServerCache cache = serverCache(level.getServer());
        int toolKey = toolKey(silkTouch, fortuneLevel);
        Entry entry = cache.entry(state, toolKey);

        if (entry.state == CACHED) {
            if (!entry.auditPending) return entry.cached;
            entry.auditPending = false;
            if (entry.cached != null && sameDrops(entry.cached.reservationView(), drops)) {
                return entry.cached;
            }
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_AUDIT_FAILURES);
            markUncacheable(entry);
            return null;
        }
        if (entry.state == UNCACHEABLE) return null;

        if (!eligible(state, silkTouch)) {
            markUncacheable(entry);
            return null;
        }

        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_LEARNING_OBSERVATIONS);

        if (entry.candidate == null) {
            entry.candidate = new CachedDrops(drops);
            entry.confirmations = 1;
            return null;
        }

        if (!sameDrops(entry.candidate.reservationView(), drops)) {
            markUncacheable(entry);
            return null;
        }

        entry.confirmations++;
        if (entry.confirmations < CONFIRMATIONS_REQUIRED) return null;

        entry.cached = entry.candidate;
        entry.candidate = null;
        entry.state = CACHED;
        entry.hitsSinceAudit = 0;
        entry.auditPending = false;
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_PROMOTIONS);
        return entry.cached;
    }

    public static void clear(MinecraftServer server) {
        SERVER_CACHES.remove(server);
    }

    private static ServerCache serverCache(MinecraftServer server) {
        synchronized (SERVER_CACHES) {
            return SERVER_CACHES.computeIfAbsent(server, ignored -> new ServerCache());
        }
    }

    private static int toolKey(boolean silkTouch, int fortuneLevel) {
        int normalizedFortune = silkTouch ? 0 : Math.max(0, fortuneLevel);
        return (normalizedFortune << 1) | (silkTouch ? 1 : 0);
    }

    private static boolean eligible(BlockState state, boolean silkTouch) {
        if (state.hasBlockEntity()) return false;

        // User-visible policy: normal ore drops keep using their loot table.
        // Silk Touch is a separate cache key and may be learned independently.
        if (!silkTouch && (state.is(COMMON_ORES) || state.is(FORGE_ORES_COMPAT))) {
            return false;
        }
        return true;
    }

    private static boolean sameDrops(List<ItemStack> cached, List<ItemStack> observed) {
        int observedNonEmpty = 0;
        for (ItemStack stack : observed) {
            if (!stack.isEmpty()) observedNonEmpty++;
        }
        if (cached.size() != observedNonEmpty) return false;

        int cachedIndex = 0;
        for (ItemStack observedStack : observed) {
            if (observedStack.isEmpty()) continue;
            ItemStack cachedStack = cached.get(cachedIndex++);
            if (cachedStack.getCount() != observedStack.getCount()
                    || !ItemStack.isSameItemSameComponents(cachedStack, observedStack)) {
                return false;
            }
        }
        return true;
    }

    private static void markUncacheable(Entry entry) {
        entry.state = UNCACHEABLE;
        entry.candidate = null;
        entry.cached = null;
        entry.confirmations = 0;
        entry.hitsSinceAudit = 0;
        entry.auditPending = false;
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.DETERMINISTIC_LOOT_CACHE_REJECTIONS);
    }

    /**
     * Function to either cache a blocks silk touch loot or return what is in the cache.
     */
    public static @Nullable Block getSilkTouchFilterEquivalent(ServerLevel level, BlockPos pos, BlockState state, ItemStack silkTouchTool
    ) {
        ServerCache cache = serverCache(level.getServer());

        if (cache.silkTouchFilterEquivalents.containsKey(state)) {
            return cache.silkTouchFilterEquivalents.get(state);
        }

        List<ItemStack> drops = Block.getDrops(state, level, pos, state.hasBlockEntity() ? level.getBlockEntity(pos) : null, null, silkTouchTool);

        Block equivalent = null;

        if (drops.size() == 1 && drops.getFirst().getItem() instanceof BlockItem blockItem) {
            equivalent = blockItem.getBlock();
        }

        cache.silkTouchFilterEquivalents.put(state, equivalent);
        return equivalent;
    }
}
