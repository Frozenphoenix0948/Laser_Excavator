package de.balto.laserexcavator.client.renderer;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.renderer.ExcavatorForceFieldRenderer;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.network.excavator.ExcavatorVisualBatchPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/** Stores transient client visuals and precomputed render data. */
@EventBusSubscriber(modid = LaserExcavator.MODID, value = Dist.CLIENT)
public final class ExcavatorClientVisuals {
    private ExcavatorClientVisuals() {}

    // Weak level keys avoid retaining disconnected worlds.
    private static final Map<ClientLevel, Map<BlockPos, VisualSet>> BY_LEVEL = new WeakHashMap<>();

    // Block/item singletons make identity lookup ideal for cached render data.
    private static final Map<Block, BlockCubeTexture> BLOCK_TEXTURE_CACHE = new IdentityHashMap<>();
    private static final Map<Item, Integer> ITEM_MARKER_COLOR_CACHE = new IdentityHashMap<>();
    private static final int DEFAULT_MARKER_RGB = 0x120D0A;

    // The marker render type has no face normals/lightmap, so a raw texture average
    // looks noticeably brighter than the corresponding rendered block. 0.80 is the
    // representative Minecraft cube shade for the three faces normally visible at
    // once: top (1.0), north/south (0.8), west/east (0.6). Items are only darkened
    // slightly because their normal billboard rendering has no directional face shade.
    private static final int BLOCK_MARKER_SHADE_PERCENT = 80;
    private static final int ITEM_MARKER_SHADE_PERCENT = 90;

    /**
     * Network callbacks only append decoded payloads here. The client tick drains
     * this queue once, avoiding thousands of Minecraft main-thread tasks per second.
     */
    private static final ConcurrentLinkedQueue<ExcavatorVisualBatchPayload> PENDING_VISUAL_BATCHES =
            new ConcurrentLinkedQueue<>();

    private static final VisualSet EMPTY = new VisualSet(false);
    private static final int MAX_RECYCLED_LASERS = 32_768;
    private static final int MAX_RECYCLED_TRANSPORTS = 32_768;
    private static final ArrayDeque<LaserVisual> LASER_POOL = new ArrayDeque<>();
    private static final ArrayDeque<TransportVisual> TRANSPORT_POOL = new ArrayDeque<>();
    private static final AtomicInteger PENDING_VISUAL_BATCH_COUNT = new AtomicInteger();
    // Reused only on the client thread when a wrapped ring needs expiry compaction.
    private static Object[] pruneScratch = new Object[0];
    private static long nextTransportSequence = 1L;
    private static ClientLevel lastClientLevel;

    public static final class LaserVisual {
        private float localX;
        private float localBottomY;
        private float localZ;
        private long startGameTime;
        private long endGameTime;
        private float invDurationTicks;

        private LaserVisual init(
                float localX, float localBottomY, float localZ,
                long startGameTime, long endGameTime, float invDurationTicks
        ) {
            this.localX = localX;
            this.localBottomY = localBottomY;
            this.localZ = localZ;
            this.startGameTime = startGameTime;
            this.endGameTime = endGameTime;
            this.invDurationTicks = invDurationTicks;
            return this;
        }

        public float localX() { return localX; }
        public float localBottomY() { return localBottomY; }
        public float localZ() { return localZ; }
        public long startGameTime() { return startGameTime; }
        public long endGameTime() { return endGameTime; }
        public float invDurationTicks() { return invDurationTicks; }
    }

    public record BlockCubeTexture(
            float u0,
            float u1,
            float v0,
            float v1,
            int markerRgb
    ) {}

    public record ItemBillboardTexture(
            float u0,
            float u1,
            float v0,
            float v1
    ) {}

    /**
     * Reusable precomputed transport slot. Lighting is deliberately absent here:
     * the renderer resolves it lazily once per current-position spatial region, so
     * marker-only transports perform no world-light query at all.
     */
    public static final class TransportVisual {
        private long sequenceId;
        private long startGameTime;
        private boolean blockItem;
        private ItemStack visualStack = ItemStack.EMPTY;
        private float sourceX;
        private float sourceY;
        private float sourceZ;
        private float fieldY;
        private float liftDeltaY;
        private float fieldDeltaX;
        private float fieldDeltaZ;
        private float descentDeltaY;
        private float invLiftTicks;
        private float invFieldTicks;
        private float invDescentTicks;
        private int liftEndTicks;
        private int fieldEndTicks;
        private int totalDurationTicks;

        private TransportVisual init(
                long sequenceId, long startGameTime, boolean blockItem,
                ItemStack visualStack,
                float sourceX, float sourceY, float sourceZ, float fieldY,
                float liftDeltaY, float fieldDeltaX, float fieldDeltaZ, float descentDeltaY,
                float invLiftTicks, float invFieldTicks, float invDescentTicks,
                int liftEndTicks, int fieldEndTicks, int totalDurationTicks
        ) {
            this.sequenceId = sequenceId;
            this.startGameTime = startGameTime;
            this.blockItem = blockItem;
            this.visualStack = visualStack;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.sourceZ = sourceZ;
            this.fieldY = fieldY;
            this.liftDeltaY = liftDeltaY;
            this.fieldDeltaX = fieldDeltaX;
            this.fieldDeltaZ = fieldDeltaZ;
            this.descentDeltaY = descentDeltaY;
            this.invLiftTicks = invLiftTicks;
            this.invFieldTicks = invFieldTicks;
            this.invDescentTicks = invDescentTicks;
            this.liftEndTicks = liftEndTicks;
            this.fieldEndTicks = fieldEndTicks;
            this.totalDurationTicks = totalDurationTicks;
            return this;
        }

        public long sequenceId() { return sequenceId; }
        public long startGameTime() { return startGameTime; }
        public boolean blockItem() { return blockItem; }
        public ItemStack visualStack() { return visualStack; }
        public float sourceX() { return sourceX; }
        public float sourceY() { return sourceY; }
        public float sourceZ() { return sourceZ; }
        public float fieldY() { return fieldY; }
        public float liftDeltaY() { return liftDeltaY; }
        public float fieldDeltaX() { return fieldDeltaX; }
        public float fieldDeltaZ() { return fieldDeltaZ; }
        public float descentDeltaY() { return descentDeltaY; }
        public float invLiftTicks() { return invLiftTicks; }
        public float invFieldTicks() { return invFieldTicks; }
        public float invDescentTicks() { return invDescentTicks; }
        public int liftEndTicks() { return liftEndTicks; }
        public int fieldEndTicks() { return fieldEndTicks; }
        public int totalDurationTicks() { return totalDurationTicks; }
    }

    public static final class VisualSet {
        private final CappedRingList<LaserVisual> laserStorage;
        private final CappedRingList<TransportVisual> transportStorage;
        private final List<LaserVisual> laserView;
        private final List<TransportVisual> transportView;
        private final boolean mutable;
        private long nextLaserExpiry = Long.MAX_VALUE;
        private long nextTransportExpiry = Long.MAX_VALUE;

        private VisualSet(boolean mutable) {
            this.mutable = mutable;
            if (mutable) {
                this.laserStorage = new CappedRingList<>(ExcavatorClientVisuals::recycleLaser);
                this.transportStorage = new CappedRingList<>(ExcavatorClientVisuals::recycleTransport);
                this.laserView = Collections.unmodifiableList(laserStorage);
                this.transportView = Collections.unmodifiableList(transportStorage);
            } else {
                this.laserStorage = null;
                this.transportStorage = null;
                this.laserView = List.of();
                this.transportView = List.of();
            }
        }

        public List<LaserVisual> lasers() {
            return laserView;
        }

        public List<TransportVisual> transports() {
            return transportView;
        }

        public boolean hasAny() {
            return !laserView.isEmpty() || !transportView.isEmpty();
        }

        private boolean addOrRefreshLaser(
                float localX,
                float localBottomY,
                float localZ,
                long startGameTime,
                long endGameTime,
                float invDurationTicks,
                int maxSize,
                IngestionStats stats
        ) {
            // A resumed powered shot may send another start for the same target.
            // Coalesce it locally so client-specific laser lifetimes never create
            // duplicate overlapping beams.
            for (int i = 0, size = laserStorage.size(); i < size; i++) {
                LaserVisual existing = laserStorage.get(i);
                if (existing.localX() == localX
                        && existing.localBottomY() == localBottomY
                        && existing.localZ() == localZ) {
                    existing.init(
                            localX, localBottomY, localZ,
                            startGameTime, endGameTime, invDurationTicks
                    );
                    nextLaserExpiry = Math.min(nextLaserExpiry, endGameTime);
                    return false;
                }
            }

            LaserVisual visual = acquireLaser(
                    localX, localBottomY, localZ,
                    startGameTime, endGameTime, invDurationTicks,
                    stats
            );
            boolean evicted = laserStorage.addCapped(visual, maxSize);
            nextLaserExpiry = Math.min(nextLaserExpiry, endGameTime);
            return evicted;
        }

        private boolean hasTransportCapacity(int maxSize) {
            return transportStorage.size() < Math.max(1, maxSize);
        }

        private boolean addTransport(TransportVisual visual, int maxSize) {
            if (!transportStorage.addIfSpace(visual, maxSize)) {
                recycleTransport(visual);
                return false;
            }
            nextTransportExpiry = Math.min(
                    nextTransportExpiry,
                    visual.startGameTime() + visual.totalDurationTicks()
            );
            return true;
        }

        private void prune(long gameTime) {
            if (!mutable) return;

            if (gameTime >= nextLaserExpiry) {
                nextLaserExpiry = laserStorage.removeExpiredAndFindNext(
                        gameTime,
                        LaserVisual::endGameTime
                );
            }

            if (gameTime >= nextTransportExpiry) {
                nextTransportExpiry = transportStorage.removeExpiredAndFindNext(
                        gameTime,
                        transport -> transport.startGameTime() + transport.totalDurationTicks()
                );
            }
        }

        private void clearAndRecycle() {
            if (!mutable) return;
            laserStorage.clearAndRecycle();
            transportStorage.clearAndRecycle();
            nextLaserExpiry = Long.MAX_VALUE;
            nextTransportExpiry = Long.MAX_VALUE;
        }
    }

    private static LaserVisual acquireLaser(
            float localX, float localBottomY, float localZ,
            long startGameTime, long endGameTime, float invDurationTicks,
            IngestionStats stats
    ) {
        LaserVisual visual = LASER_POOL.pollFirst();
        if (visual == null) {
            visual = new LaserVisual();
            if (stats != null) stats.laserAllocations++;
        } else if (stats != null) {
            stats.laserPoolReuses++;
        }
        return visual.init(localX, localBottomY, localZ, startGameTime, endGameTime, invDurationTicks);
    }

    private static TransportVisual acquireTransport(IngestionStats stats) {
        TransportVisual visual = TRANSPORT_POOL.pollFirst();
        if (visual == null) {
            visual = new TransportVisual();
            if (stats != null) stats.transportAllocations++;
        } else if (stats != null) {
            stats.transportPoolReuses++;
        }
        return visual;
    }

    private static void recycleLaser(LaserVisual visual) {
        if (visual == null || LASER_POOL.size() >= MAX_RECYCLED_LASERS) return;
        LASER_POOL.addFirst(visual);
    }

    private static void recycleTransport(TransportVisual visual) {
        if (visual == null || TRANSPORT_POOL.size() >= MAX_RECYCLED_TRANSPORTS) return;
        visual.visualStack = ItemStack.EMPTY;
        TRANSPORT_POOL.addFirst(visual);
    }

    private static void clearAllVisualSets() {
        for (Map<BlockPos, VisualSet> levelMap : BY_LEVEL.values()) {
            for (VisualSet set : levelMap.values()) set.clearAndRecycle();
        }
        BY_LEVEL.clear();
    }

    /**
     * Called directly from the network payload callback. No Minecraft/client state
     * is touched here, so this is safe on the networking thread.
     */
    public static void enqueueVisualBatch(ExcavatorVisualBatchPayload payload) {
        PENDING_VISUAL_BATCHES.add(payload);
        PENDING_VISUAL_BATCH_COUNT.incrementAndGet();

        if (ExcavatorProfiler.isEnabled()) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_PAYLOADS_RECEIVED);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_EVENTS_RECEIVED, payload.eventCount());
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_VISUAL_GROUPS_RECEIVED,
                    payload.groupCount()
            );
        }
    }

    /**
     * Creates an isolated mutable visual set for client-only transport benchmarks.
     * It uses the same pooled TransportVisual lifecycle as network-backed sets but
     * is deliberately not inserted into the world-position visual map.
     */
    public static VisualSet createSyntheticVisualSet() {
        return new VisualSet(true);
    }

    public static boolean addSyntheticTransport(
            VisualSet set,
            ClientLevel level,
            int sourceDeltaX,
            int sourceDeltaY,
            int sourceDeltaZ,
            ItemStack visualStack,
            int ticksPerBlockValue,
            int forceFieldYOffset,
            long startGameTime
    ) {
        if (set == null || level == null || visualStack.isEmpty()) return false;
        int maxTransports = LaserExcavatorClientConfig.MAX_TRANSPORTS_PER_EXCAVATOR.get();
        long gameTime = level.getGameTime();
        if (!set.hasTransportCapacity(maxTransports)) {
            set.prune(gameTime);
            if (!set.hasTransportCapacity(maxTransports)) {
                if (ExcavatorProfiler.isEnabled()) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_LIMIT_REJECTIONS);
                }
                return false;
            }
        }

        boolean profiling = ExcavatorProfiler.isEnabled();
        long started = profiling ? System.nanoTime() : 0L;
        IngestionStats stats = new IngestionStats();
        int syntheticDurationTicks = transportDurationForGeometry(
                sourceDeltaX, sourceDeltaY, sourceDeltaZ,
                ticksPerBlockValue, forceFieldYOffset
        );
        TransportVisual visual = prepareTransport(
                level, sourceDeltaX, sourceDeltaY, sourceDeltaZ, visualStack,
                syntheticDurationTicks, forceFieldYOffset, startGameTime, gameTime, stats
        );
        if (profiling) {
            ExcavatorProfiler.addDuration(
                    ExcavatorProfiler.Section.CLIENT_VISUAL_TRANSPORT_PREPARATION,
                    System.nanoTime() - started
            );
        }
        if (visual == null) return false;
        boolean added = set.addTransport(visual, maxTransports);
        if (profiling) {
            if (added) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORTS_MATERIALIZED);
            } else {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_LIMIT_REJECTIONS);
            }
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_POOL_REUSES,
                    stats.transportPoolReuses
            );
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_ALLOCATIONS,
                    stats.transportAllocations
            );
        }
        return added;
    }

    public static void pruneSyntheticVisualSet(VisualSet set, long gameTime) {
        if (set != null) set.prune(gameTime);
    }

    public static void clearSyntheticVisualSet(VisualSet set) {
        if (set != null) set.clearAndRecycle();
    }

    public static VisualSet get(ClientLevel level, BlockPos excavatorPos) {
        Map<BlockPos, VisualSet> levelMap = BY_LEVEL.get(level);
        if (levelMap == null) return EMPTY;

        VisualSet set = levelMap.get(excavatorPos);
        return set != null ? set : EMPTY;
    }

    public static void clear(ClientLevel level, BlockPos excavatorPos) {
        Map<BlockPos, VisualSet> levelMap = BY_LEVEL.get(level);
        if (levelMap == null) return;

        VisualSet removed = levelMap.remove(excavatorPos);
        if (removed != null) removed.clearAndRecycle();
        if (levelMap.isEmpty()) {
            BY_LEVEL.remove(level);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clearPendingNetworkVisuals();
            clearAllVisualSets();
            if (lastClientLevel != null) {
                ExcavatorForceFieldRenderer.clearCachedTransportStates();
            }
            lastClientLevel = null;
            return;
        }

        // Clear queued visuals whenever the client changes levels.
        if (lastClientLevel != null && lastClientLevel != level) {
            clearPendingNetworkVisuals();
            clearAllVisualSets();
            ExcavatorForceFieldRenderer.clearCachedTransportStates();
        }
        lastClientLevel = level;

        drainPendingVisualBatches(level);

        Map<BlockPos, VisualSet> levelMap = BY_LEVEL.get(level);
        if (levelMap == null || levelMap.isEmpty()) return;

        long gameTime = level.getGameTime();
        Iterator<Map.Entry<BlockPos, VisualSet>> iterator = levelMap.entrySet().iterator();
        while (iterator.hasNext()) {
            VisualSet set = iterator.next().getValue();
            set.prune(gameTime);
            if (!set.hasAny()) {
                set.clearAndRecycle();
                iterator.remove();
            }
        }

        if (levelMap.isEmpty()) {
            BY_LEVEL.remove(level);
        }
    }

    private static void drainPendingVisualBatches(ClientLevel level) {
        if (PENDING_VISUAL_BATCHES.isEmpty()) return;

        boolean profiling = ExcavatorProfiler.isEnabled();
        long ingestionProfile = ExcavatorProfiler.begin(
                profiling,
                ExcavatorProfiler.Section.CLIENT_VISUAL_INGESTION
        );
        long transportPreparationNanos = 0L;

        IngestionStats stats = new IngestionStats();
        long gameTime = level.getGameTime();
        int maxLasers = LaserExcavatorClientConfig.MAX_LASERS_PER_EXCAVATOR.get();
        int maxTransports = LaserExcavatorClientConfig.MAX_TRANSPORTS_PER_EXCAVATOR.get();
        Map<BlockPos, VisualSet> levelMap = BY_LEVEL.get(level);

        int budgetMicros = LaserExcavatorClientConfig.VISUAL_INGESTION_BUDGET_MICROS.get();
        long deadlineNanos = budgetMicros <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + (long) budgetMicros * 1_000L;

        ExcavatorVisualBatchPayload payload;
        while ((payload = PENDING_VISUAL_BATCHES.poll()) != null) {
            PENDING_VISUAL_BATCH_COUNT.updateAndGet(value -> Math.max(0, value - 1));
            stats.payloadsDrained++;

            long payloadServerGameTime = payload.serverGameTime();
            int groupCount = payload.groupCount();
            for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                stats.groupsDrained++;
                BlockPos excavatorPos = payload.excavatorPos(groupIndex);
                VisualSet set = levelMap == null ? null : levelMap.get(excavatorPos);

                int laserStart = payload.groupLaserStart(groupIndex);
                int laserEnd = laserStart + payload.groupLaserCount(groupIndex);
                for (int laserIndex = laserStart; laserIndex < laserEnd; laserIndex++) {
                    stats.eventsDrained++;
                    long startGameTime = payloadServerGameTime - payload.laserAgeTicks(laserIndex);
                    int duration = Math.max(1, LaserExcavatorClientConfig.LASER_VISUAL_DURATION_TICKS.get());
                    long endGameTime = startGameTime + duration;
                    if (gameTime >= endGameTime) {
                        stats.expiredBeforeMaterialization++;
                        continue;
                    }

                    if (set == null) {
                        if (levelMap == null) {
                            levelMap = BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
                        }
                        set = levelMap.computeIfAbsent(excavatorPos.immutable(), ignored -> new VisualSet(true));
                    }

                    boolean evicted = set.addOrRefreshLaser(
                            payload.laserDeltaX(laserIndex) + 0.5F,
                            payload.laserDeltaY(laserIndex) + 0.45F,
                            payload.laserDeltaZ(laserIndex) + 0.5F,
                            startGameTime,
                            endGameTime,
                            1.0F / duration,
                            maxLasers,
                            stats
                    );
                    if (evicted) stats.ringEvictions++;
                    stats.lasersMaterialized++;
                }

                int transportStartIndex = payload.groupTransportStart(groupIndex);
                int transportEnd = transportStartIndex + payload.groupTransportCount(groupIndex);
                if (transportStartIndex < transportEnd) {
                    long transportStart = profiling ? System.nanoTime() : 0L;
                    for (int transportIndex = transportStartIndex; transportIndex < transportEnd; transportIndex++) {
                        stats.eventsDrained++;

                        if (set != null && !set.hasTransportCapacity(maxTransports)) {
                            set.prune(gameTime);
                            if (!set.hasTransportCapacity(maxTransports)) {
                                stats.transportLimitRejections++;
                                continue;
                            }
                        }

                        long startGameTime = payloadServerGameTime - payload.transportAgeTicks(transportIndex);
                        TransportVisual visual = prepareTransport(
                                level,
                                payload.transportDeltaX(transportIndex),
                                payload.transportDeltaY(transportIndex),
                                payload.transportDeltaZ(transportIndex),
                                payload.transportStack(transportIndex),
                                payload.transportDurationTicks(transportIndex),
                                payload.transportForceFieldYOffset(transportIndex),
                                startGameTime,
                                gameTime,
                                stats
                        );
                        if (visual == null) continue;

                        if (set == null) {
                            if (levelMap == null) {
                                levelMap = BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
                            }
                            set = levelMap.computeIfAbsent(excavatorPos.immutable(), ignored -> new VisualSet(true));
                        }

                        if (set.addTransport(visual, maxTransports)) {
                            stats.transportsMaterialized++;
                        } else {
                            stats.transportLimitRejections++;
                        }
                    }
                    if (profiling) transportPreparationNanos += System.nanoTime() - transportStart;
                }
            }

            // Finish the current compact payload atomically, then yield to the next
            // client tick once the configured wall-clock budget is consumed. Event
            // timestamps make deferred payloads join their animations at the proper age.
            if (deadlineNanos != Long.MAX_VALUE
                    && !PENDING_VISUAL_BATCHES.isEmpty()
                    && System.nanoTime() >= deadlineNanos) {
                stats.budgetHits++;
                break;
            }
        }

        stats.pendingPayloadsAfterDrain = Math.max(0, PENDING_VISUAL_BATCH_COUNT.get());

        if (profiling) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_QUEUE_DRAINS);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_PAYLOADS_DRAINED, stats.payloadsDrained);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_GROUPS_DRAINED, stats.groupsDrained);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_EVENTS_DRAINED, stats.eventsDrained);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_LASERS_MATERIALIZED, stats.lasersMaterialized);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORTS_MATERIALIZED, stats.transportsMaterialized);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_EVENTS_EXPIRED, stats.expiredBeforeMaterialization);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_RING_EVICTIONS, stats.ringEvictions);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_LIMIT_REJECTIONS, stats.transportLimitRejections);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_INGESTION_BUDGET_HITS, stats.budgetHits);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_PENDING_PAYLOADS_AFTER_DRAIN, stats.pendingPayloadsAfterDrain);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_LASER_POOL_REUSES, stats.laserPoolReuses);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_POOL_REUSES, stats.transportPoolReuses);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_LASER_ALLOCATIONS, stats.laserAllocations);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_VISUAL_TRANSPORT_ALLOCATIONS, stats.transportAllocations);
            ExcavatorProfiler.addDuration(
                    ExcavatorProfiler.Section.CLIENT_VISUAL_TRANSPORT_PREPARATION,
                    transportPreparationNanos
            );
        }

        ExcavatorProfiler.end(ExcavatorProfiler.Section.CLIENT_VISUAL_INGESTION, ingestionProfile);
    }

    private static TransportVisual prepareTransport(
            ClientLevel level,
            int sourceDeltaX,
            int sourceDeltaY,
            int sourceDeltaZ,
            ItemStack visualStack,
            int totalDurationTicks,
            int forceFieldYOffset,
            long serverStartGameTime,
            long clientGameTime,
            IngestionStats stats
    ) {
        if (visualStack.isEmpty()) return null;

        float sourceX = sourceDeltaX + 0.5F;
        float sourceY = sourceDeltaY + 0.65F;
        float sourceZ = sourceDeltaZ + 0.5F;
        float fieldY = forceFieldYOffset + 0.15F;

        final float destinationX = 0.5F;
        final float destinationY = 0.85F;
        final float destinationZ = 0.5F;

        float liftDeltaY = fieldY - sourceY;
        float fieldDeltaX = destinationX - sourceX;
        float fieldDeltaZ = destinationZ - sourceZ;
        float descentDeltaY = destinationY - fieldY;

        double liftDistance = Math.abs(liftDeltaY);
        double fieldDistance = Math.sqrt(
                fieldDeltaX * fieldDeltaX + fieldDeltaZ * fieldDeltaZ
        );
        double descentDistance = Math.abs(descentDeltaY);
        int totalTicks = Math.max(3, totalDurationTicks);
        int liftTicks;
        int fieldTicks;
        int descentTicks;
        double totalDistance = liftDistance + fieldDistance + descentDistance;
        if (totalDistance <= 1.0E-6D) {
            liftTicks = 1;
            fieldTicks = 1;
            descentTicks = totalTicks - 2;
        } else {
            int distributable = totalTicks - 3;
            int liftExtra = (int) Math.floor(distributable * (liftDistance / totalDistance));
            int fieldExtra = (int) Math.floor(distributable * (fieldDistance / totalDistance));
            liftTicks = 1 + liftExtra;
            fieldTicks = 1 + fieldExtra;
            descentTicks = totalTicks - liftTicks - fieldTicks;
        }
        int fieldEndTicks = liftTicks + fieldTicks;

        // Reject stale queued events before any texture/model lookup or active-slot acquisition.
        if (clientGameTime - serverStartGameTime >= totalTicks) {
            stats.expiredBeforeMaterialization++;
            return null;
        }

        boolean isBlockItem = visualStack.getItem() instanceof BlockItem;

        // Texture/model data and region lighting are resolved lazily only when the
        // renderer selects a textured cube or billboard representation.
        return acquireTransport(stats).init(
                nextTransportSequence++,
                serverStartGameTime,
                isBlockItem,
                visualStack,
                sourceX,
                sourceY,
                sourceZ,
                fieldY,
                liftDeltaY,
                fieldDeltaX,
                fieldDeltaZ,
                descentDeltaY,
                1.0F / liftTicks,
                1.0F / fieldTicks,
                1.0F / descentTicks,
                liftTicks,
                fieldEndTicks,
                totalTicks
        );
    }

    private static VisualSet getOrCreate(ClientLevel level, BlockPos excavatorPos) {
        Map<BlockPos, VisualSet> levelMap = BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
        return levelMap.computeIfAbsent(excavatorPos.immutable(), ignored -> new VisualSet(true));
    }

    public static BlockCubeTexture resolveBlockTexture(BlockItem blockItem) {
        Block block = blockItem.getBlock();
        BlockCubeTexture cached = BLOCK_TEXTURE_CACHE.get(block);
        if (cached != null) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_HITS);
            return cached;
        }

        BlockState state = block.defaultBlockState();
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getBlockRenderer()
                .getBlockModelShaper()
                .getParticleIcon(state);

        BlockCubeTexture resolved = new BlockCubeTexture(
                sprite.getU0(),
                sprite.getU1(),
                sprite.getV0(),
                sprite.getV1(),
                shadeMarkerRgb(averageSpriteRgb(sprite), BLOCK_MARKER_SHADE_PERCENT)
        );
        BLOCK_TEXTURE_CACHE.put(block, resolved);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_MISSES);
        return resolved;
    }

    /**
     * Returns the cached average texture color used by the opaque transport markers.
     * Blocks reuse the existing block render-data cache, so after the first occurrence
     * this is only an identity-map lookup.
     */
    public static int resolveBlockMarkerColor(BlockItem blockItem) {
        return resolveBlockTexture(blockItem).markerRgb();
    }

    /**
     * Non-block item marker colors are cached by item singleton. This intentionally
     * favors the very cheap steady-state path used by distant marker rendering.
     */
    public static int resolveItemMarkerColor(ItemStack stack, ClientLevel level, int seed) {
        if (stack.isEmpty()) return DEFAULT_MARKER_RGB;

        Item item = stack.getItem();
        Integer cached = ITEM_MARKER_COLOR_CACHE.get(item);
        if (cached != null) return cached;

        BakedModel model = Minecraft.getInstance()
                .getItemRenderer()
                .getModel(stack, level, null, seed);
        int resolved = shadeMarkerRgb(averageSpriteRgb(model.getParticleIcon()), ITEM_MARKER_SHADE_PERCENT);
        ITEM_MARKER_COLOR_CACHE.put(item, resolved);
        return resolved;
    }

    /**
     * Calculates an alpha-weighted average of the sprite's first frame. Vanilla 16x16
     * sprites are averaged exactly; larger resource-pack textures are sampled on at most
     * a 16x16 grid so the one-time cache miss remains bounded and cheap.
     */
    private static int averageSpriteRgb(TextureAtlasSprite sprite) {
        int width = Math.max(1, sprite.contents().width());
        int height = Math.max(1, sprite.contents().height());
        int stepX = Math.max(1, (width + 15) / 16);
        int stepY = Math.max(1, (height + 15) / 16);

        long red = 0L;
        long green = 0L;
        long blue = 0L;
        long weight = 0L;

        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                // NativeImage RGBA pixels are packed ABGR in an int.
                int rgba = sprite.getPixelRGBA(0, x, y);
                int alpha = (rgba >>> 24) & 0xFF;
                if (alpha == 0) continue;

                red += (long) (rgba & 0xFF) * alpha;
                green += (long) ((rgba >>> 8) & 0xFF) * alpha;
                blue += (long) ((rgba >>> 16) & 0xFF) * alpha;
                weight += alpha;
            }
        }

        if (weight == 0L) return DEFAULT_MARKER_RGB;

        int r = (int) (red / weight);
        int g = (int) (green / weight);
        int b = (int) (blue / weight);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Pre-bakes a tiny amount of the shading that normal block/item rendering would
     * otherwise contribute. This runs only on a cache miss; marker rendering still
     * uses one cached packed RGB int per marker.
     */
    private static int shadeMarkerRgb(int rgb, int shadePercent) {
        int r = ((rgb >>> 16) & 0xFF) * shadePercent / 100;
        int g = ((rgb >>> 8) & 0xFF) * shadePercent / 100;
        int b = (rgb & 0xFF) * shadePercent / 100;
        return (r << 16) | (g << 8) | b;
    }

    public static ItemBillboardTexture resolveItemTexture(
            ItemStack stack,
            ClientLevel level,
            int seed
    ) {
        BakedModel model = Minecraft.getInstance()
                .getItemRenderer()
                .getModel(stack, level, null, seed);
        TextureAtlasSprite sprite = model.getParticleIcon();
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_ITEM_TEXTURE_RESOLVES);

        return new ItemBillboardTexture(
                sprite.getU0(),
                sprite.getU1(),
                sprite.getV0(),
                sprite.getV1()
        );
    }

    private static void clearPendingNetworkVisuals() {
        PENDING_VISUAL_BATCHES.clear();
        PENDING_VISUAL_BATCH_COUNT.set(0);
    }

    /** Atlas rebuilds invalidate cached UV coordinates. */
    @EventBusSubscriber(
            modid = LaserExcavator.MODID,
            value = Dist.CLIENT
    )
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
            if (!TextureAtlas.LOCATION_BLOCKS.equals(event.getAtlas().location())) return;
            BLOCK_TEXTURE_CACHE.clear();
            ITEM_MARKER_COLOR_CACHE.clear();
            clearAllVisualSets();
            clearPendingNetworkVisuals();
            ExcavatorForceFieldRenderer.clearCachedTransportStates();
        }
    }

    private static int transportDurationForGeometry(
            int sourceDeltaX,
            int sourceDeltaY,
            int sourceDeltaZ,
            int ticksPerBlockValue,
            int forceFieldYOffset
    ) {
        int ticksPerBlock = Math.max(1, ticksPerBlockValue);
        double sourceX = sourceDeltaX + 0.5D;
        double sourceY = sourceDeltaY + 0.65D;
        double sourceZ = sourceDeltaZ + 0.5D;
        double fieldY = forceFieldYOffset + 0.15D;

        int liftTicks = ticksForDistance(Math.abs(fieldY - sourceY), ticksPerBlock);
        int fieldTicks = ticksForDistance(
                Math.sqrt((0.5D - sourceX) * (0.5D - sourceX)
                        + (0.5D - sourceZ) * (0.5D - sourceZ)),
                ticksPerBlock
        );
        int descentTicks = ticksForDistance(Math.abs(0.85D - fieldY), ticksPerBlock);
        return liftTicks + fieldTicks + descentTicks;
    }

    private static int ticksForDistance(double distance, int ticksPerBlock) {
        return Math.max(1, (int) Math.ceil(distance * ticksPerBlock));
    }

    /** Per-drain local counters; published to LongAdders only once after the bulk pass. */
    private static final class IngestionStats {
        long payloadsDrained;
        long groupsDrained;
        long eventsDrained;
        long lasersMaterialized;
        long transportsMaterialized;
        long expiredBeforeMaterialization;
        long ringEvictions;
        long transportLimitRejections;
        long budgetHits;
        long pendingPayloadsAfterDrain;
        long laserPoolReuses;
        long transportPoolReuses;
        long laserAllocations;
        long transportAllocations;
    }


    private static void ensurePruneScratch(int required) {
        if (pruneScratch.length >= required) return;
        int capacity = Math.max(16, pruneScratch.length);
        while (capacity < required) capacity <<= 1;
        pruneScratch = new Object[capacity];
    }

    /**
     * Array-backed circular list with O(1) capped insertion. Callers can either
     * evict the oldest entry or reject a new entry when the configured limit is full.
     */
    private static final class CappedRingList<T> extends AbstractList<T> implements RandomAccess {
        private final Consumer<T> recycler;
        private Object[] elements = new Object[16];
        private int head;
        private int size;

        private CappedRingList(Consumer<T> recycler) {
            this.recycler = recycler;
        }

        @Override
        public T get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            @SuppressWarnings("unchecked")
            T value = (T) elements[(head + index) & (elements.length - 1)];
            return value;
        }

        @Override
        public int size() {
            return size;
        }

        private boolean addCapped(T value, int requestedMaxSize) {
            int maxSize = Math.max(1, requestedMaxSize);
            boolean evicted = false;

            if (size >= maxSize) {
                int removeCount = size - maxSize + 1;
                for (int i = 0; i < removeCount; i++) {
                    @SuppressWarnings("unchecked")
                    T removed = (T) elements[head];
                    elements[head] = null;
                    head = (head + 1) & (elements.length - 1);
                    size--;
                    recycler.accept(removed);
                }
                evicted = true;
            }

            ensureCapacity(Math.min(maxSize, size + 1));
            int tail = (head + size) & (elements.length - 1);
            elements[tail] = value;
            size++;
            modCount++;
            return evicted;
        }

        private boolean addIfSpace(T value, int requestedMaxSize) {
            int maxSize = Math.max(1, requestedMaxSize);
            if (size >= maxSize) return false;

            ensureCapacity(Math.min(maxSize, size + 1));
            int tail = (head + size) & (elements.length - 1);
            elements[tail] = value;
            size++;
            modCount++;
            return true;
        }

        /**
         * Removes all entries whose end time is <= gameTime in one stable compaction
         * pass and returns the next earliest end time.
         */
        private long removeExpiredAndFindNext(long gameTime, ToLongFunction<T> endTime) {
            if (size == 0) return Long.MAX_VALUE;

            int oldSize = size;
            int write = 0;
            long nextExpiry = Long.MAX_VALUE;

            if (head == 0) {
                // The common non-wrapped case can compact directly in-place.
                for (int read = 0; read < oldSize; read++) {
                    @SuppressWarnings("unchecked")
                    T value = (T) elements[read];
                    long end = endTime.applyAsLong(value);
                    if (gameTime >= end) {
                        recycler.accept(value);
                        continue;
                    }
                    elements[write++] = value;
                    if (end < nextExpiry) nextExpiry = end;
                }
                for (int i = write; i < oldSize; i++) elements[i] = null;
            } else {
                // A wrapped ring cannot safely compact into itself because writes can
                // overwrite unread entries. Reuse one shared client-thread scratch array
                // instead of allocating a new array for every excavator/tick.
                ensurePruneScratch(oldSize);
                for (int read = 0; read < oldSize; read++) {
                    T value = get(read);
                    long end = endTime.applyAsLong(value);
                    if (gameTime >= end) {
                        recycler.accept(value);
                        continue;
                    }
                    pruneScratch[write++] = value;
                    if (end < nextExpiry) nextExpiry = end;
                }
                for (int i = 0; i < oldSize; i++) {
                    elements[(head + i) & (elements.length - 1)] = null;
                }
                System.arraycopy(pruneScratch, 0, elements, 0, write);
                for (int i = 0; i < write; i++) pruneScratch[i] = null;
                head = 0;
            }

            if (write != oldSize) modCount++;
            size = write;
            return nextExpiry;
        }

        private void clearAndRecycle() {
            for (int i = 0; i < size; i++) {
                int index = (head + i) & (elements.length - 1);
                @SuppressWarnings("unchecked")
                T value = (T) elements[index];
                elements[index] = null;
                recycler.accept(value);
            }
            if (size > 0) modCount++;
            head = 0;
            size = 0;
        }

        private void ensureCapacity(int required) {
            if (required <= elements.length) return;

            int newCapacity = elements.length;
            while (newCapacity < required) {
                newCapacity = Math.max(newCapacity + 1, newCapacity << 1);
            }

            Object[] grown = new Object[newCapacity];
            for (int i = 0; i < size; i++) {
                grown[i] = get(i);
            }
            elements = grown;
            head = 0;
            modCount++;
        }
    }
}
