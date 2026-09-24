package de.balto.laserexcavator.client;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.BlockUpdateDebugMode;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.network.excavator.ExcavatorSectionBlockUpdatePayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * Applies excavator removals to the client immediately while scheduling expensive
 * render-section rebuilds as distance-dependent visual snapshots.
 *
 * The scheduling unit is Minecraft's native 16x16x16 render section. Repeated silent
 * block changes in the same section share one pending refresh. Nearby sections refresh
 * frequently, while distant sections accumulate changes for longer before one mesh rebuild.
 * A 512-slot primitive timing wheel schedules refreshes from their distance-based cadence.
 *
 * Boundary-neighbor dirties are still suppressed when the corresponding adjacent block
 * is air, and moving into a new 16-block camera region can only promote pending refreshes
 * earlier; it never postpones already scheduled work.
 */
@EventBusSubscriber(modid = LaserExcavator.MODID, value = Dist.CLIENT)
public final class ExcavatorClientBlockUpdates {
    private static final int SILENT_CLIENT_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Chunk-aligned 16^3 sections close to the camera refresh almost immediately. Far sections
     * deliberately collect many silent block-state changes before one visual mesh snapshot.
     */
    private static final double MIN_REFRESH_DISTANCE = 64.0;
    private static final double MAX_REFRESH_DISTANCE = 320.0;
    private static final int MIN_REFRESH_TICKS = 2;
    private static final int MAX_REFRESH_TICKS = 480;
    private static final double REFRESH_FALLOFF_EXPONENT = 3.0;

    /** 512 slots cover the full 480-tick distance interval without an early modulo revisit. */
    private static final int REMESH_WHEEL_SIZE = 512;
    private static final int REMESH_WHEEL_MASK = REMESH_WHEEL_SIZE - 1;

    /**
     * Sections whose client block states are newer than their submitted mesh.
     * Each section owns one authoritative refresh tick; superseded wheel entries are ignored.
     */
    private static final Long2ObjectOpenHashMap<DirtySectionState> DIRTY_SECTIONS =
            new Long2ObjectOpenHashMap<>();

    private static final LongOpenHashSet DEBUG_SUPPRESSED_DIRTY_SECTIONS = new LongOpenHashSet();

    private static final LongArrayFIFOQueue[] REMESH_WHEEL = createRemeshWheel();

    private static ClientLevel pendingLevel;
    private static ViewState lastViewState;
    private static BlockUpdateDebugMode lastDebugMode = BlockUpdateDebugMode.NORMAL;
    private static long clientTickIndex;

    private ExcavatorClientBlockUpdates() {}

    public static void applySectionRemovals(ExcavatorSectionBlockUpdatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;

        if (pendingLevel != level) {
            clearForLevel(level);
        }

        SectionPos sectionPos = SectionPos.of(payload.sectionX(), payload.sectionY(), payload.sectionZ());

        // If the section is no longer client-loaded, a later normal chunk sync already
        // contains the authoritative server state, so there is nothing useful to apply.
        if (!level.hasChunk(sectionPos.x(), sectionPos.z())) return;

        BlockUpdateDebugMode debugMode = LaserExcavatorClientConfig.blockUpdateDebugMode();
        if (debugMode == BlockUpdateDebugMode.NO_BLOCK_SYNC) {
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_BLOCK_SYNC_POSITIONS_SKIPPED,
                    payload.localPositions().size()
            );
            return;
        }

        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.CLIENT_BLOCK_UPDATE_APPLY);
        try {
            boolean touchWest = false;
            boolean touchEast = false;
            boolean touchDown = false;
            boolean touchUp = false;
            boolean touchNorth = false;
            boolean touchSouth = false;

            boolean rebuildWest = false;
            boolean rebuildEast = false;
            boolean rebuildDown = false;
            boolean rebuildUp = false;
            boolean rebuildNorth = false;
            boolean rebuildSouth = false;

            long applied = 0L;
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
            int minX = sectionPos.minBlockX();
            int minY = sectionPos.minBlockY();
            int minZ = sectionPos.minBlockZ();

            List<Short> localPositions = payload.localPositions();
            for (int i = 0, size = localPositions.size(); i < size; i++) {
                short packed = localPositions.get(i);
                int localX = SectionPos.sectionRelativeX(packed);
                int localY = SectionPos.sectionRelativeY(packed);
                int localZ = SectionPos.sectionRelativeZ(packed);

                int worldX = minX + localX;
                int worldY = minY + localY;
                int worldZ = minZ + localZ;
                mutablePos.set(worldX, worldY, worldZ);
                boolean changed = level.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), SILENT_CLIENT_FLAGS);
                if (!changed) continue;
                applied++;

                // Only dirty a neighboring render section if at least one adjacent block on
                // that boundary is non-air. If all adjacent blocks are air (or the chunk is
                // not loaded), removing this section's boundary block cannot change the
                // neighboring section's mesh.
                if (localX == 0) {
                    touchWest = true;
                    if (!rebuildWest) {
                        neighborPos.set(worldX - 1, worldY, worldZ);
                        rebuildWest = neighborNeedsRebuild(level, neighborPos);
                    }
                }
                if (localX == 15) {
                    touchEast = true;
                    if (!rebuildEast) {
                        neighborPos.set(worldX + 1, worldY, worldZ);
                        rebuildEast = neighborNeedsRebuild(level, neighborPos);
                    }
                }
                if (localY == 0) {
                    touchDown = true;
                    if (!rebuildDown) {
                        neighborPos.set(worldX, worldY - 1, worldZ);
                        rebuildDown = neighborNeedsRebuild(level, neighborPos);
                    }
                }
                if (localY == 15) {
                    touchUp = true;
                    if (!rebuildUp) {
                        neighborPos.set(worldX, worldY + 1, worldZ);
                        rebuildUp = neighborNeedsRebuild(level, neighborPos);
                    }
                }
                if (localZ == 0) {
                    touchNorth = true;
                    if (!rebuildNorth) {
                        neighborPos.set(worldX, worldY, worldZ - 1);
                        rebuildNorth = neighborNeedsRebuild(level, neighborPos);
                    }
                }
                if (localZ == 15) {
                    touchSouth = true;
                    if (!rebuildSouth) {
                        neighborPos.set(worldX, worldY, worldZ + 1);
                        rebuildSouth = neighborNeedsRebuild(level, neighborPos);
                    }
                }
            }

            if (applied == 0L) return;

            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_BLOCK_UPDATES_APPLIED, applied);

            queueDirty(sectionPos.x(), sectionPos.y(), sectionPos.z());
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_PRIMARY_SECTION_DIRTY_REQUESTS);

            int neighborRequests = 0;
            int neighborAvoided = 0;

            if (touchWest) {
                if (rebuildWest) {
                    queueDirty(sectionPos.x() - 1, sectionPos.y(), sectionPos.z());
                    neighborRequests++;
                } else {
                    neighborAvoided++;
                }
            }
            if (touchEast) {
                if (rebuildEast) {
                    queueDirty(sectionPos.x() + 1, sectionPos.y(), sectionPos.z());
                    neighborRequests++;
                } else {
                    neighborAvoided++;
                }
            }
            if (touchDown) {
                if (rebuildDown) {
                    queueDirty(sectionPos.x(), sectionPos.y() - 1, sectionPos.z());
                    neighborRequests++;
                } else {
                    neighborAvoided++;
                }
            }
            if (touchUp) {
                if (rebuildUp) {
                    queueDirty(sectionPos.x(), sectionPos.y() + 1, sectionPos.z());
                    neighborRequests++;
                } else {
                    neighborAvoided++;
                }
            }
            if (touchNorth) {
                if (rebuildNorth) {
                    queueDirty(sectionPos.x(), sectionPos.y(), sectionPos.z() - 1);
                    neighborRequests++;
                } else {
                    neighborAvoided++;
                }
            }
            if (touchSouth) {
                if (rebuildSouth) {
                    queueDirty(sectionPos.x(), sectionPos.y(), sectionPos.z() + 1);
                    neighborRequests++;
                } else {
                    neighborAvoided++;
                }
            }

            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_NEIGHBOR_SECTION_DIRTY_REQUESTS,
                    neighborRequests
            );
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_NEIGHBOR_SECTION_DIRTY_AVOIDED_AIR,
                    neighborAvoided
            );
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.CLIENT_BLOCK_UPDATE_APPLY, profile);
        }
    }

    private static boolean neighborNeedsRebuild(ClientLevel level, BlockPos pos) {
        // Never cause an unloaded neighboring chunk to be touched just for a render rebuild.
        // Its normal chunk sync will build the correct mesh when it becomes client-loaded.
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        return !level.getBlockState(pos).isAir();
    }

    private static void queueDirty(int sectionX, int sectionY, int sectionZ) {
        queueDirty(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    private static void queueDirty(long packedSection) {
        DirtySectionState state = DIRTY_SECTIONS.get(packedSection);
        if (state != null) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_ABSORBED_PENDING);
            return;
        }

        SectionPos sectionPos = SectionPos.of(packedSection);
        int intervalTicks = refreshIntervalTicks(lastViewState, sectionPos);
        long candidateTick = clientTickIndex + intervalTicks;
        state = new DirtySectionState(clientTickIndex);
        DIRTY_SECTIONS.put(packedSection, state);
        scheduleRefresh(packedSection, state, candidateTick, intervalTicks);
    }

    private static void scheduleRefresh(
            long packedSection,
            DirtySectionState state,
            long scheduledTick,
            int intervalTicks
    ) {
        state.scheduledTick = Math.max(clientTickIndex + 1L, scheduledTick);
        state.scheduledIntervalTicks = Math.max(1, intervalTicks);
        REMESH_WHEEL[(int) (state.scheduledTick & REMESH_WHEEL_MASK)].enqueue(packedSection);
    }

    /**
     * Returns a continuous distance-dependent remesh interval. Nearby sections refresh
     * quickly, while distant sections accumulate more block changes per mesh rebuild.
     */
    private static int refreshIntervalTicks(ViewState view, SectionPos sectionPos) {
        if (view == null) return MIN_REFRESH_TICKS;

        double dx = sectionPos.minBlockX() + 8.0 - view.eyeX;
        double dy = sectionPos.minBlockY() + 8.0 - view.eyeY;
        double dz = sectionPos.minBlockZ() + 8.0 - view.eyeZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance <= MIN_REFRESH_DISTANCE) return MIN_REFRESH_TICKS;
        if (distance >= MAX_REFRESH_DISTANCE) return MAX_REFRESH_TICKS;

        double t = (distance - MIN_REFRESH_DISTANCE) / (MAX_REFRESH_DISTANCE - MIN_REFRESH_DISTANCE);
        double curved = Math.pow(t, REFRESH_FALLOFF_EXPONENT);
        return MIN_REFRESH_TICKS + (int) Math.round(curved * (MAX_REFRESH_TICKS - MIN_REFRESH_TICKS));
    }

    /**
     * Distance-scheduled remesh maintenance. There is no persistent FIFO backlog anymore:
     * each dirty section waits in the wheel slot for its chosen visual snapshot time.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        clientTickIndex++;

        if (level == null) {
            DIRTY_SECTIONS.clear();
            DEBUG_SUPPRESSED_DIRTY_SECTIONS.clear();
            clearRemeshWheel();
            pendingLevel = null;
            lastViewState = null;
            lastDebugMode = BlockUpdateDebugMode.NORMAL;
            return;
        }
        if (pendingLevel != level) {
            clearForLevel(level);
            return;
        }

        ViewState previousView = lastViewState;
        lastViewState = captureViewState(minecraft);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_SCHEDULER_TICKS);

        BlockUpdateDebugMode debugMode = LaserExcavatorClientConfig.blockUpdateDebugMode();

        if (lastDebugMode == BlockUpdateDebugMode.NO_REMESH
                && debugMode != BlockUpdateDebugMode.NO_REMESH
                && !DEBUG_SUPPRESSED_DIRTY_SECTIONS.isEmpty()) {
            long catchup = DEBUG_SUPPRESSED_DIRTY_SECTIONS.size();
            var iterator = DEBUG_SUPPRESSED_DIRTY_SECTIONS.iterator();
            while (iterator.hasNext()) queueDirty(iterator.nextLong());
            DEBUG_SUPPRESSED_DIRTY_SECTIONS.clear();
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_REMESH_CATCHUP_SECTIONS, catchup);
        }
        lastDebugMode = debugMode;

        if (debugMode == BlockUpdateDebugMode.NO_REMESH) {
            long before = DEBUG_SUPPRESSED_DIRTY_SECTIONS.size();
            DEBUG_SUPPRESSED_DIRTY_SECTIONS.addAll(DIRTY_SECTIONS.keySet());
            long added = DEBUG_SUPPRESSED_DIRTY_SECTIONS.size() - before;
            if (added > 0L) {
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_REMESH_SECTIONS_SUPPRESSED, added);
            }
            DIRTY_SECTIONS.clear();
            clearRemeshWheel();
            return;
        }

        // Walking/flying into a new 16-block camera region promotes nearby pending snapshots
        // without scanning the whole pending set every tick or every frame.
        if (viewSectionChanged(previousView, lastViewState) && !DIRTY_SECTIONS.isEmpty()) {
            promotePendingForViewChange();
        }

        int pendingCount = DIRTY_SECTIONS.size();
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_PENDING_SAMPLES, pendingCount);
        ExcavatorProfiler.recordMax(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_PENDING_PEAK, pendingCount);
        if (pendingCount == 0) {
            clearRemeshWheel();
            return;
        }

        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.CLIENT_SECTION_DIRTY_FLUSH);
        try {
            processCurrentWheelSlot(minecraft, level);
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.CLIENT_SECTION_DIRTY_FLUSH, profile);
        }
    }

    private static void promotePendingForViewChange() {
        var iterator = DIRTY_SECTIONS.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long packedSection = entry.getLongKey();
            DirtySectionState state = entry.getValue();
            SectionPos sectionPos = SectionPos.of(packedSection);
            int intervalTicks = refreshIntervalTicks(lastViewState, sectionPos);
            long candidateTick = clientTickIndex + intervalTicks;
            if (candidateTick < state.scheduledTick) {
                scheduleRefresh(packedSection, state, candidateTick, intervalTicks);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_SECTION_REFRESH_PROMOTIONS);
            }
        }
    }

    private static void processCurrentWheelSlot(Minecraft minecraft, ClientLevel level) {
        LongArrayFIFOQueue bucket = REMESH_WHEEL[(int) (clientTickIndex & REMESH_WHEEL_MASK)];
        if (bucket.isEmpty()) return;

        int submitted = 0;
        long entriesVisited = 0L;
        long staleEntries = 0L;
        long dueEntries = 0L;
        long skippedUnloaded = 0L;
        long dirtyAgeTotal = 0L;

        while (!bucket.isEmpty()) {
            long packedSection = bucket.dequeueLong();
            entriesVisited++;

            DirtySectionState state = DIRTY_SECTIONS.get(packedSection);
            if (state == null) {
                staleEntries++;
                continue;
            }

            // A promoted section may leave a superseded wheel entry behind; scheduledTick
            // identifies the authoritative entry when that slot is visited.
            if (state.scheduledTick != clientTickIndex) {
                staleEntries++;
                continue;
            }

            dueEntries++;

            SectionPos sectionPos = SectionPos.of(packedSection);
            if (!level.hasChunk(sectionPos.x(), sectionPos.z())) {
                DIRTY_SECTIONS.remove(packedSection);
                skippedUnloaded++;
                continue;
            }

            minecraft.levelRenderer.setSectionDirty(sectionPos.x(), sectionPos.y(), sectionPos.z());
            DIRTY_SECTIONS.remove(packedSection);
            submitted++;

            long dirtyAge = Math.max(0L, clientTickIndex - state.firstDirtyTick);
            dirtyAgeTotal += dirtyAge;
            ExcavatorProfiler.recordMax(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_AGE_TICKS_MAX, dirtyAge);
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.CLIENT_SECTION_REFRESH_INTERVAL_TICKS_TOTAL,
                    state.scheduledIntervalTicks
            );
        }

        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_SECTION_REFRESH_WHEEL_ENTRIES, entriesVisited);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_SECTION_REFRESH_STALE_WHEEL_ENTRIES, staleEntries);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_SECTION_REFRESH_DUE_ENTRIES, dueEntries);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_UNIQUE_SECTION_DIRTY_CALLS, submitted);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_AGE_TICKS_TOTAL, dirtyAgeTotal);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.CLIENT_SECTION_DIRTY_SKIPPED_UNLOADED, skippedUnloaded);
    }

    private static boolean viewSectionChanged(ViewState before, ViewState after) {
        if (before == null || after == null) return before != after;
        return before.sectionX != after.sectionX
                || before.sectionY != after.sectionY
                || before.sectionZ != after.sectionZ;
    }

    private static ViewState captureViewState(Minecraft minecraft) {
        if (minecraft.player == null) return null;
        Vec3 eye = minecraft.player.getEyePosition();
        return new ViewState(
                eye.x,
                eye.y,
                eye.z,
                SectionPos.blockToSectionCoord((int) Math.floor(eye.x)),
                SectionPos.blockToSectionCoord((int) Math.floor(eye.y)),
                SectionPos.blockToSectionCoord((int) Math.floor(eye.z))
        );
    }

    private static void clearForLevel(ClientLevel level) {
        DIRTY_SECTIONS.clear();
        DEBUG_SUPPRESSED_DIRTY_SECTIONS.clear();
        clearRemeshWheel();
        pendingLevel = level;
        lastViewState = captureViewState(Minecraft.getInstance());
        lastDebugMode = LaserExcavatorClientConfig.blockUpdateDebugMode();
    }

    private static LongArrayFIFOQueue[] createRemeshWheel() {
        LongArrayFIFOQueue[] wheel = new LongArrayFIFOQueue[REMESH_WHEEL_SIZE];
        for (int i = 0; i < wheel.length; i++) wheel[i] = new LongArrayFIFOQueue();
        return wheel;
    }

    private static void clearRemeshWheel() {
        for (LongArrayFIFOQueue bucket : REMESH_WHEEL) bucket.clear();
    }

    private static final class DirtySectionState {
        final long firstDirtyTick;
        long scheduledTick;
        int scheduledIntervalTicks;

        DirtySectionState(long tick) {
            this.firstDirtyTick = tick;
        }
    }

    private record ViewState(
            double eyeX,
            double eyeY,
            double eyeZ,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {}
}
