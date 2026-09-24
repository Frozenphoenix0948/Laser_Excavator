package de.balto.laserexcavator.block.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorArea;
import de.balto.laserexcavator.client.renderer.ExcavatorClientVisuals;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.TransportDebugMode;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ExcavatorTransportRenderer {

    // Keep block billboards at normal size through the lower half of their visual range,
    // then taper through 16 precomputed size bands toward the large-marker appearance.
    // Corner offsets for all bands are rebuilt once per frame so the hot billboard path avoids per-vertex scaling.
    private static final float BLOCK_BILLBOARD_MIN_SCALE = 0.60F;
    private static final int BLOCK_BILLBOARD_TAPER_BAND_COUNT = 16;
    private static final int BLOCK_BILLBOARD_TAPER_LAST_BAND = BLOCK_BILLBOARD_TAPER_BAND_COUNT - 1;
    private static final float[] BLOCK_BILLBOARD_TAPER_SCALES = createBlockBillboardTaperScales();

    private static final byte DISTANCE_TIER_UNCLASSIFIED = -1;
    private static final byte DISTANCE_TIER_INDIVIDUAL = 0;
    private static final byte DISTANCE_TIER_BATCHED = 1;
    private static final byte DISTANCE_TIER_HIDDEN = 2;

    private static final byte VISUAL_BLOCK_CUBE = 0;
    private static final byte VISUAL_BLOCK_BILLBOARD = 1;
    private static final byte VISUAL_BLOCK_MARKER = 2;
    private static final byte VISUAL_ITEM_BILLBOARD = 3;
    private static final byte VISUAL_ITEM_MARKER = 4;

    private static final byte TRANSPORT_PHASE_LIFT = 0;
    private static final byte TRANSPORT_PHASE_FIELD = 1;
    private static final byte TRANSPORT_PHASE_DESCENT = 2;

    // Expiration cleanup remains deliberately bounded so large completion bursts cannot
    // monopolize a rendered frame. Spatial membership uses a separate intrusive timing
    // wheel driven by predicted 16-block boundary crossings.
    private static final int MAX_CLEANUP_OPERATIONS_PER_FRAME = 6;
    private static final int SPATIAL_REGION_SIZE = 16;
    private static final int SPATIAL_REGION_SHIFT = 4;
    private static final int REGION_TIMING_WHEEL_SIZE = 128;
    private static final int REGION_TIMING_WHEEL_MASK = REGION_TIMING_WHEEL_SIZE - 1;
    // Membership is refreshed at most once per client tick. With the minimum configured
    // transport travel time (1 tick/block), smoothstep velocity peaks below 1.5 blocks/tick,
    // so 2 blocks safely cover partial-tick motion between membership updates.
    private static final double REGION_TICK_MOTION_PADDING = 2.0D;
    private static final double REGION_PREDICTION_EPSILON = 1.0E-6D;

    // Conservative padding around a fixed current-position 16^3 region. Region culling
    // only rejects transports when their complete current region is outside the frustum.
    private static final double TRANSPORT_REGION_FRUSTUM_PADDING = 2.5D;
    private static final double BLOCK_ROTATION_RADIANS_PER_TICK = Math.toRadians(5.0D);
    private static final double ITEM_ROTATION_RADIANS_PER_TICK = Math.toRadians(9.0D);

    private static final RenderType TRANSPORT_ATLAS_RENDER_TYPE =
            RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS);

    private final ExcavatorTransportGeometry.Position transportPosition = new ExcavatorTransportGeometry.Position();
    private final BlockPos.MutableBlockPos regionLightSamplePos = new BlockPos.MutableBlockPos();
    private ExcavatorTransportGeometry.RotatingCube rotatingCubeGeometry;
    private ExcavatorTransportGeometry.RotatingBillboard blockBillboardGeometry;
    private final float[][] blockBillboardBandCornerX = new float[BLOCK_BILLBOARD_TAPER_BAND_COUNT][4];
    private final float[][] blockBillboardBandCornerY = new float[BLOCK_BILLBOARD_TAPER_BAND_COUNT][4];
    private final float[][] blockBillboardBandCornerZ = new float[BLOCK_BILLBOARD_TAPER_BAND_COUNT][4];
    private ExcavatorTransportGeometry.RotatingBillboard itemBillboardGeometry;
    private final Vector3f normalScratch = new Vector3f();
    private boolean blockNormalsPrepared;
    private int cubeProfileSampleCursor;
    private int billboardProfileSampleCursor;
    private int regionMigrationProfileSampleCursor;
    private float blockBillboardNormalX;
    private float blockBillboardNormalY;
    private float blockBillboardNormalZ;
    private float cubeSouthNormalX;
    private float cubeSouthNormalY;
    private float cubeSouthNormalZ;
    private float cubeNorthNormalX;
    private float cubeNorthNormalY;
    private float cubeNorthNormalZ;
    private float cubeEastNormalX;
    private float cubeEastNormalY;
    private float cubeEastNormalZ;
    private float cubeWestNormalX;
    private float cubeWestNormalY;
    private float cubeWestNormalZ;
    private float cubeUpNormalX;
    private float cubeUpNormalY;
    private float cubeUpNormalZ;
    private float cubeDownNormalX;
    private float cubeDownNormalY;
    private float cubeDownNormalZ;
    private float cachedBlockHalfSize = Float.NaN;
    private float cachedItemHalfSize = Float.NaN;
    private float billboardDistanceSq;
    private float blockBillboardShrinkStartSq;
    private float blockBillboardShrinkInvRangeSq;
    private float largeMarkerDistanceSq;
    private float smallMarkerDistanceSq;
    private float batchDistance;
    private float batchDistanceSq;
    private int maxBatchedMarkers;
    private int minBatchedMarkers;
    private float batchFalloffExponent;
    private float transportMaxRenderDistance;
    private float transportMaxRenderDistanceSq;

    // Shared frame state. A BlockEntityRenderer instance is reused for every excavator,
    // so camera lookup, trigonometry and geometry preparation only need to happen once
    // per rendered frame instead of once per block entity.
    private long lastVisualConfigGameTime = Long.MIN_VALUE;
    private long lastPreparedRenderTimeBits = Long.MIN_VALUE;
    private double frameCameraWorldX;
    private double frameCameraWorldY;
    private double frameCameraWorldZ;
    private Frustum frameFrustum;
    private final FrustumCullStats reusableFrustumCullStats = new FrustumCullStats();
    /**
     * Camera-independent transport cache with camera-relative LOD classification.
     * Membership follows each transport's current position through fixed 16^3 regions.
     * Moving between regions is O(1) swap-remove + append. Each occupied region is
     * classified as INDIVIDUAL, BATCHED, or HIDDEN.
     */
    private final Map<ExcavatorBlockEntity, TransportRenderState> transportStates = new WeakHashMap<>();
    private int batchRepresentativeVisibilityStamp;

    void render(
            ExcavatorClientVisuals.VisualSet visuals,
            ClientLevel level,
            ExcavatorBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource
    ) {
        renderInternal(
                visuals, level, blockEntity, partialTick, poseStack, bufferSource,
                DISTANCE_TIER_UNCLASSIFIED
        );
    }

    /**
     * Synthetic transport benchmark entry point. Everything after the spatial-region
     * tier decision is the normal production transport renderer. AUTO passes
     * DISTANCE_TIER_UNCLASSIFIED and therefore uses the production classifier.
     */
    void renderForStressTest(
            ExcavatorClientVisuals.VisualSet visuals,
            ClientLevel level,
            ExcavatorBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            byte forcedTier
    ) {
        renderInternal(visuals, level, blockEntity, partialTick, poseStack, bufferSource, forcedTier);
    }

    void clearCachedState(ExcavatorBlockEntity blockEntity) {
        transportStates.remove(blockEntity);
    }

    void clearAllCachedStates() {
        transportStates.clear();
    }

    void clearStressTestState(ExcavatorBlockEntity blockEntity) {
        clearCachedState(blockEntity);
    }

    static byte stressTierAuto() { return DISTANCE_TIER_UNCLASSIFIED; }
    static byte stressTierIndividual() { return DISTANCE_TIER_INDIVIDUAL; }
    static byte stressTierBatched() { return DISTANCE_TIER_BATCHED; }
    static byte stressTierHidden() { return DISTANCE_TIER_HIDDEN; }

    private void renderInternal(
            ExcavatorClientVisuals.VisualSet visuals,
            ClientLevel level,
            ExcavatorBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            byte forcedTier
    ) {
        List<ExcavatorClientVisuals.TransportVisual> transports = visuals.transports();
        if (transports.isEmpty()) {
            transportStates.remove(blockEntity);
            return;
        }

        boolean profiling = ExcavatorProfiler.isEnabled();
        long transportProfile = ExcavatorProfiler.begin(profiling, ExcavatorProfiler.Section.TRANSPORT_RENDER);
        long frameSetupProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.FRAME_SETUP
        );
        long gameTime = level.getGameTime();
        double renderGameTime = gameTime + partialTick;
        prepareSharedFrameState(gameTime, renderGameTime);

        BlockPos origin = blockEntity.getBlockPos();
        float cameraX = (float) (frameCameraWorldX - origin.getX());
        float cameraY = (float) (frameCameraWorldY - origin.getY());
        float cameraZ = (float) (frameCameraWorldZ - origin.getZ());
        ExcavatorProfiler.end(ExcavatorProfiler.TransportSubsection.FRAME_SETUP, frameSetupProfile);

        // A fully hidden effect does not need a renderer-side transport cache at all.
        // The VisualSet remains authoritative, so when the camera comes back in range the
        // current live transports are materialized lazily in one normal sync pass. This
        // removes per-frame CachedTransport creation, spatial grouping and cleanup
        // and tier-list traversal for excavators whose complete possible transport envelope
        // is farther away than the maximum transport render distance.
        long hiddenBoundsProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.HIDDEN_WHOLE_EFFECT_TEST
        );
        boolean wholeEffectHidden = forcedTier == DISTANCE_TIER_HIDDEN
                || (isAutomaticTierMode(forcedTier) && isWholeTransportEnvelopeHidden(blockEntity));
        ExcavatorProfiler.end(
                ExcavatorProfiler.TransportSubsection.HIDDEN_WHOLE_EFFECT_TEST, hiddenBoundsProfile
        );
        if (wholeEffectHidden) {
            transportStates.remove(blockEntity);
            if (profiling) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_WHOLE_EFFECT_REJECTS);
                ExcavatorProfiler.add(
                        ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_WHOLE_EFFECT_VISUALS_SKIPPED,
                        transports.size()
                );
            }
            ExcavatorProfiler.end(ExcavatorProfiler.Section.TRANSPORT_RENDER, transportProfile);
            return;
        }

        PoseStack.Pose basePose = poseStack.last();
        blockNormalsPrepared = false;
        VertexConsumer atlasConsumer = null;

        TransportRenderState state = transportStates.computeIfAbsent(
                blockEntity, ignored -> new TransportRenderState()
        );
        long cacheSyncProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.CACHE_SYNC
        );
        syncNewTransports(state, transports, gameTime, renderGameTime, origin, profiling);
        ExcavatorProfiler.end(ExcavatorProfiler.TransportSubsection.CACHE_SYNC, cacheSyncProfile);
        cleanupTransportCacheBounded(state, transports, gameTime, profiling);

        TransportDebugMode debugMode = LaserExcavatorClientConfig.transportDebugMode();
        if (debugMode == TransportDebugMode.DISABLED) {
            // Keep transport lifetime/cache state active while skipping LOD and geometry.
            ExcavatorProfiler.end(ExcavatorProfiler.Section.TRANSPORT_RENDER, transportProfile);
            return;
        }

        long regionMembershipProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.REGION_MEMBERSHIP
        );
        updateSpatialRegionMembership(state, gameTime, renderGameTime, origin, profiling);
        ExcavatorProfiler.end(
                ExcavatorProfiler.TransportSubsection.REGION_MEMBERSHIP, regionMembershipProfile
        );

        long regionLodProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.SPATIAL_REGION_LOD
        );
        classifySpatialRegions(state, forcedTier, profiling);
        ExcavatorProfiler.end(
                ExcavatorProfiler.TransportSubsection.SPATIAL_REGION_LOD, regionLodProfile
        );

        // Fixed current-position regions are deliberately classified conservatively from
        // their nearest point. If every occupied region is HIDDEN, every current transport
        // is beyond the configured visual radius and the renderer can stop immediately.
        if (!state.regions.isEmpty() && state.hiddenRegions.size() == state.regions.size()) {
            if (profiling) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_ALL_REGIONS_EARLY_RETURNS);
                ExcavatorProfiler.add(
                        ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_ALL_REGIONS_VISUALS_SKIPPED,
                        state.activeTransportCount
                );
                ExcavatorProfiler.add(
                        ExcavatorProfiler.Counter.TRANSPORT_ACTIVE_TRANSPORT_SAMPLES,
                        state.activeTransportCount
                );
                ExcavatorProfiler.add(
                        ExcavatorProfiler.Counter.TRANSPORT_ACTIVE_REGION_SAMPLES,
                        state.regions.size()
                );
                ExcavatorProfiler.add(
                        ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_REGIONS,
                        state.hiddenRegions.size()
                );
                ExcavatorProfiler.add(
                        ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_SOURCE_VISUALS,
                        state.activeTransportCount
                );
            }
            ExcavatorProfiler.end(ExcavatorProfiler.Section.TRANSPORT_RENDER, transportProfile);
            return;
        }

        if (debugMode == TransportDebugMode.MARKERS_ONLY) {
            renderMarkersOnlyDiagnostic(
                    state, level, origin, renderGameTime, cameraX, cameraY, cameraZ, profiling
            );
            ExcavatorProfiler.end(ExcavatorProfiler.Section.TRANSPORT_RENDER, transportProfile);
            return;
        }

        boolean emitGeometry = debugMode != TransportDebugMode.PROCESS_ONLY;

        int blockCubes = 0;
        int blockBillboards = 0;
        int itemBillboards = 0;
        int individualMarkers = 0;
        int individualVisited = 0;
        FrustumCullStats frustumCullStats = profiling ? reusableFrustumCullStats : null;
        if (frustumCullStats != null) frustumCullStats.reset();

        List<TransportRegion> frameIndividualRegions = state.individualRegions;
        List<TransportRegion> frameBatchedRegions = state.batchedRegions;

        float nearestBatchedRegionDistanceSq = nearestBatchedRegionDistanceSq(frameBatchedRegions);
        int batchTargetMarkers = calculateBatchTargetMarkers(nearestBatchedRegionDistanceSq);

        int maximumQueuedMarkers = state.individualTransportCount + batchTargetMarkers;
        if (emitGeometry) ExcavatorTransportMarkerRenderer.reserveMarkers(maximumQueuedMarkers);

        // INDIVIDUAL is one persistent LOD regime. Cube, billboard, large-marker, and small-marker
        // are only visual representations chosen from the transport's exact current
        // distance; their fixed 16^3 region membership does not depend on representation.
        long individualProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.INDIVIDUAL_TRANSPORTS
        );
        if (emitGeometry && !frameIndividualRegions.isEmpty()) {
            atlasConsumer = bufferSource.getBuffer(TRANSPORT_ATLAS_RENDER_TYPE);
        }
        for (TransportRegion region : frameIndividualRegions) {
            if (!isTransportRegionVisible(region, frustumCullStats)) continue;
            for (CachedTransport cached : region.members) {
                byte representation = renderIndividualTransport(
                        cached, level, origin, renderGameTime, cameraX, cameraY, cameraZ,
                        basePose, atlasConsumer, profiling, emitGeometry
                );
                if (profiling) {
                    individualVisited++;
                    if (representation == VISUAL_BLOCK_CUBE) blockCubes++;
                    else if (representation == VISUAL_BLOCK_BILLBOARD) blockBillboards++;
                    else if (representation == VISUAL_ITEM_BILLBOARD) itemBillboards++;
                    else individualMarkers++;
                }
            }
        }
        ExcavatorProfiler.end(ExcavatorProfiler.TransportSubsection.INDIVIDUAL_TRANSPORTS, individualProfile);

        // BATCHED regions provide the candidate set for continuous representative sampling.
        long batchProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.BATCHED_MARKERS
        );
        int batchedMarkersQueued = queueBatchedRegionMarkers(
                frameBatchedRegions, state.visibleRegionScratch, state.batchedRepresentatives,
                level, origin, renderGameTime, batchTargetMarkers,
                cameraX, cameraY, cameraZ, frustumCullStats, profiling, emitGeometry
        );
        ExcavatorProfiler.end(ExcavatorProfiler.TransportSubsection.BATCHED_MARKERS, batchProfile);

        if (profiling) {
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.TRANSPORTS_VISITED,
                    (long) individualVisited + batchedMarkersQueued
            );
            int activeRegions = state.regions.size();
            int activeTransports = state.activeTransportCount;
            int batchedSourceVisuals = 0;
            int hiddenSourceVisuals = 0;
            for (TransportRegion region : state.batchedRegions) batchedSourceVisuals += region.members.size();
            for (TransportRegion region : state.hiddenRegions) hiddenSourceVisuals += region.members.size();
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.TRANSPORT_ACTIVE_TRANSPORT_SAMPLES,
                    activeTransports
            );
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_ACTIVE_REGION_SAMPLES, activeRegions);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_INDIVIDUAL_REGIONS_VISITED, frameIndividualRegions.size());
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_BATCHED_REGIONS_VISITED, frameBatchedRegions.size());
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_REGIONS, state.hiddenRegions.size());
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_HIDDEN_SOURCE_VISUALS, hiddenSourceVisuals);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_FRUSTUM_REGION_TESTS, frustumCullStats.regionsTested);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_FRUSTUM_REGIONS_CULLED, frustumCullStats.regionsCulled);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_FRUSTUM_MEMBERS_CULLED, frustumCullStats.membersCulled);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.BLOCK_CUBES_RENDERED, blockCubes);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.BLOCK_BILLBOARDS_RENDERED, blockBillboards);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.INDIVIDUAL_MARKERS_RENDERED, individualMarkers);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.NON_BLOCK_SPRITES_RENDERED, itemBillboards);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.BATCH_SOURCE_VISUALS, batchedSourceVisuals);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.BATCH_MARKERS_RENDERED, batchedMarkersQueued);
            if (batchTargetMarkers > 0) {
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.BATCH_TARGET_MARKERS_TOTAL, batchTargetMarkers);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BATCH_TARGET_MARKER_SAMPLES);
            }
        }

        ExcavatorProfiler.end(ExcavatorProfiler.Section.TRANSPORT_RENDER, transportProfile);
    }

    private void renderMarkersOnlyDiagnostic(
            TransportRenderState state,
            ClientLevel level,
            BlockPos origin,
            double renderGameTime,
            float cameraX,
            float cameraY,
            float cameraZ,
            boolean profiling
    ) {
        int reserveCount = 0;
        for (TransportRegion region : state.regions) reserveCount += region.members.size();
        ExcavatorTransportMarkerRenderer.reserveMarkers(reserveCount);

        FrustumCullStats stats = profiling ? reusableFrustumCullStats : null;
        if (stats != null) stats.reset();

        int queued = 0;
        for (TransportRegion region : state.regions) {
            if (!isTransportRegionVisible(region, stats)) continue;
            for (CachedTransport cached : region.members) {
                if (!cached.active) continue;
                calculateCachedTransportPosition(cached, renderGameTime);
                float relX = transportPosition.x - cameraX;
                float relY = transportPosition.y - cameraY;
                float relZ = transportPosition.z - cameraZ;
                float distanceSq = relX * relX + relY * relY + relZ * relZ;
                if (distanceSq > transportMaxRenderDistanceSq) continue;
                ExcavatorTransportMarkerRenderer.queueBatchedSmallMarkerReserved(
                        relX, relY, relZ,
                        resolveLitTransportMarkerColor(cached, level, origin, profiling)
                );
                queued++;
            }
        }

        if (profiling) {
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORTS_VISITED, queued);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_FRUSTUM_REGION_TESTS, stats.regionsTested);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_FRUSTUM_REGIONS_CULLED, stats.regionsCulled);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_FRUSTUM_MEMBERS_CULLED, stats.membersCulled);
        }
    }

    /**
     * Discovers only newly appended transports. New visuals are materialized directly in
     * the spatial region containing their current interpolated position, then prediction-
     * gated tick updates move them through fixed 16^3 regions as the trajectory advances.
     */
    private void syncNewTransports(
            TransportRenderState state,
            List<ExcavatorClientVisuals.TransportVisual> transports,
            long gameTime,
            double renderGameTime,
            BlockPos origin,
            boolean profiling
    ) {
        int size = transports.size();
        if (size == 0) return;

        int firstNewIndex = size;
        for (int index = size - 1; index >= 0; index--) {
            ExcavatorClientVisuals.TransportVisual transport = transports.get(index);
            if (transport.sequenceId() <= state.lastSeenSequence) break;
            firstNewIndex = index;
        }
        if (firstNewIndex == size) return;

        int added = 0;
        int newRegions = 0;
        for (int index = firstNewIndex; index < size; index++) {
            ExcavatorClientVisuals.TransportVisual transport = transports.get(index);
            state.lastSeenSequence = Math.max(state.lastSeenSequence, transport.sequenceId());
            if (!hasRenderableTexture(transport)) continue;

            long endGameTime = transport.startGameTime() + transport.totalDurationTicks();
            if (gameTime >= endGameTime) continue;

            CachedTransport cached = new CachedTransport(transport, endGameTime);
            // Queued visual packets can arrive several ticks after their server start time.
            // Materialize directly into the region containing the transport *now* rather
            // than briefly placing an aged transport back in the source region encoded by its event.
            calculateCachedTransportPosition(cached, renderGameTime);
            int cellX = Mth.floor(cached.frameX) >> SPATIAL_REGION_SHIFT;
            int cellY = Mth.floor(cached.frameY) >> SPATIAL_REGION_SHIFT;
            int cellZ = Mth.floor(cached.frameZ) >> SPATIAL_REGION_SHIFT;
            long key = packSpatialRegionKey(cellX, cellY, cellZ);
            TransportRegion region = state.regionsByKey.get(key);
            if (region == null) {
                region = new TransportRegion(key, cellX, cellY, cellZ, origin);
                addRegion(state, region);
                if (profiling) newRegions++;
            }

            cached.active = true;
            addToRegion(state, region, cached);
            linkInsertionTail(state, cached);
            linkExpirationBucket(state, cached);
            scheduleRegionCheck(state, cached, predictNextRegionCheckTick(cached, renderGameTime));
            if (profiling) added++;
        }

        if (profiling) {
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_CACHE_ADDITIONS, added);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_ADDITIONS, newRegions);
        }
    }

    /**
     * Fast whole-effect HIDDEN test. The complete transport path is contained by the
     * union of the selected excavation volume, the force-field plane and the excavator
     * destination. If the nearest point of that conservative world-space envelope is
     * already beyond max render distance, every present and future transport is hidden.
     * This is deliberately evaluated before renderer-cache synchronization.
     */
    private boolean isWholeTransportEnvelopeHidden(ExcavatorBlockEntity blockEntity) {
        ExcavatorArea area = blockEntity.getExcavatorArea();
        BlockPos min = area.min();
        BlockPos max = area.max();
        BlockPos origin = blockEntity.getBlockPos();
        double fieldY = blockEntity.getForceFieldY();

        double minX = Math.min(min.getX(), origin.getX());
        double minY = Math.min(Math.min(min.getY(), origin.getY()), fieldY);
        double minZ = Math.min(min.getZ(), origin.getZ());
        double maxX = Math.max(max.getX() + 1.0D, origin.getX() + 1.0D);
        double maxY = Math.max(Math.max(max.getY() + 1.0D, origin.getY() + 1.0D), fieldY + 1.0D);
        double maxZ = Math.max(max.getZ() + 1.0D, origin.getZ() + 1.0D);

        return distanceSqToBounds3D(
                frameCameraWorldX, frameCameraWorldY, frameCameraWorldZ,
                minX, minY, minZ, maxX, maxY, maxZ
        ) >= transportMaxRenderDistanceSq;
    }

    /**
     * Processes only timing-wheel entries whose predicted 16-block boundary crossing is
     * due. Each active transport is linked into exactly one of 128 intrusive buckets, so
     * ordinary ticks do no full transport scan and scheduling/removal remain O(1). If an
     * excavator was not rendered for a while, at most one full wheel rotation is visited
     * to catch every overdue absolute tick.
     */
    private void updateSpatialRegionMembership(
            TransportRenderState state,
            long gameTime,
            double renderGameTime,
            BlockPos origin,
            boolean profiling
    ) {
        if (state.lastRegionMembershipTick == gameTime) return;

        long previousTick = state.lastRegionMembershipTick;
        state.lastRegionMembershipTick = gameTime;

        long firstTick;
        if (previousTick == Long.MIN_VALUE || gameTime <= previousTick) {
            firstTick = gameTime;
        } else {
            long elapsed = gameTime - previousTick;
            firstTick = elapsed >= REGION_TIMING_WHEEL_SIZE
                    ? gameTime - REGION_TIMING_WHEEL_SIZE + 1L
                    : previousTick + 1L;
        }

        int bucketsVisited = 0;
        int wheelEntriesVisited = 0;
        int futureRotationSkips = 0;
        int checks = 0;
        int migrations = 0;
        int newRegions = 0;
        int removedRegions = 0;

        for (long tick = firstTick; tick <= gameTime; tick++) {
            int bucketIndex = (int) (tick & REGION_TIMING_WHEEL_MASK);
            bucketsVisited++;

            CachedTransport cached = state.regionCheckWheel[bucketIndex];
            while (cached != null) {
                CachedTransport next = cached.regionCheckNext;
                wheelEntriesVisited++;

                // A wheel slot can also contain a crossing from a future rotation. The
                // absolute scheduled tick distinguishes it without any heap or tree lookup.
                if (!cached.active) {
                    unscheduleRegionCheck(state, cached);
                    cached = next;
                    continue;
                }
                if (cached.scheduledRegionCheckTick > gameTime) {
                    futureRotationSkips++;
                    cached = next;
                    continue;
                }

                unscheduleRegionCheck(state, cached);

                boolean sample = profiling && ((regionMigrationProfileSampleCursor++ & 0xFF) == 0);
                long sampled = ExcavatorProfiler.begin(
                        sample, ExcavatorProfiler.TransportSubsection.REGION_POSITION_SAMPLE
                );
                calculateCachedTransportPosition(cached, renderGameTime);
                ExcavatorProfiler.end(
                        ExcavatorProfiler.TransportSubsection.REGION_POSITION_SAMPLE, sampled
                );

                sampled = ExcavatorProfiler.begin(
                        sample, ExcavatorProfiler.TransportSubsection.REGION_KEY_SAMPLE
                );
                int cellX = Mth.floor(cached.frameX) >> SPATIAL_REGION_SHIFT;
                int cellY = Mth.floor(cached.frameY) >> SPATIAL_REGION_SHIFT;
                int cellZ = Mth.floor(cached.frameZ) >> SPATIAL_REGION_SHIFT;
                long key = packSpatialRegionKey(cellX, cellY, cellZ);
                TransportRegion current = cached.region;
                boolean sameRegion = current != null && current.key == key;
                ExcavatorProfiler.end(
                        ExcavatorProfiler.TransportSubsection.REGION_KEY_SAMPLE, sampled
                );

                checks++;
                if (!sameRegion) {
                    sampled = ExcavatorProfiler.begin(
                            sample, ExcavatorProfiler.TransportSubsection.REGION_MOVE_SAMPLE
                    );
                    TransportRegion target = state.regionsByKey.get(key);
                    if (target == null) {
                        target = new TransportRegion(key, cellX, cellY, cellZ, origin);
                        addRegion(state, target);
                        newRegions++;
                    }
                    if (moveToRegion(state, cached, target)) removedRegions++;
                    migrations++;
                    ExcavatorProfiler.end(
                            ExcavatorProfiler.TransportSubsection.REGION_MOVE_SAMPLE, sampled
                    );
                }

                scheduleRegionCheck(state, cached, predictNextRegionCheckTick(cached, renderGameTime));
                cached = next;
            }
        }

        if (profiling) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_REGION_TICK_PASSES);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_POTENTIAL_SCAN_VISITS, state.activeTransportCount);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_WHEEL_BUCKETS_VISITED, bucketsVisited);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_WHEEL_ENTRIES_VISITED, wheelEntriesVisited);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_WHEEL_FUTURE_SKIPS, futureRotationSkips);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_MEMBERSHIP_CHECKS, checks);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_MIGRATIONS, migrations);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_ADDITIONS, newRegions);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_REMOVALS, removedRegions);
        }
    }

    private static void scheduleRegionCheck(TransportRenderState state, CachedTransport cached, long tick) {
        unscheduleRegionCheck(state, cached);
        cached.scheduledRegionCheckTick = tick;
        if (tick == Long.MAX_VALUE || !cached.active) return;

        int bucketIndex = (int) (tick & REGION_TIMING_WHEEL_MASK);
        CachedTransport head = state.regionCheckWheel[bucketIndex];
        cached.regionCheckWheelIndex = bucketIndex;
        cached.regionCheckPrev = null;
        cached.regionCheckNext = head;
        if (head != null) head.regionCheckPrev = cached;
        state.regionCheckWheel[bucketIndex] = cached;
    }

    private static void unscheduleRegionCheck(TransportRenderState state, CachedTransport cached) {
        int bucketIndex = cached.regionCheckWheelIndex;
        if (bucketIndex < 0) {
            cached.scheduledRegionCheckTick = Long.MAX_VALUE;
            return;
        }

        CachedTransport previous = cached.regionCheckPrev;
        CachedTransport next = cached.regionCheckNext;
        if (previous == null) {
            if (state.regionCheckWheel[bucketIndex] == cached) {
                state.regionCheckWheel[bucketIndex] = next;
            }
        } else {
            previous.regionCheckNext = next;
        }
        if (next != null) next.regionCheckPrev = previous;

        cached.regionCheckWheelIndex = -1;
        cached.regionCheckPrev = null;
        cached.regionCheckNext = null;
        cached.scheduledRegionCheckTick = Long.MAX_VALUE;
    }

    /**
     * Predicts the next client tick on which this deterministic smoothstep trajectory can
     * leave its current 16^3 region. The inverse smoothstep is solved only when an exact
     * membership check is already due, so the binary search runs near actual boundary
     * crossings rather than once per frame. A prediction is only an optimization hint: if
     * rounding puts a due check just before the crossing, the next prediction schedules the
     * following tick and the padded region bounds keep LOD/culling conservative meanwhile.
     */
    private long predictNextRegionCheckTick(CachedTransport cached, double fromTime) {
        if (fromTime >= cached.endGameTime) return Long.MAX_VALUE;

        double crossing = Double.POSITIVE_INFINITY;

        if (fromTime < cached.liftEndGameTime) {
            crossing = nextAxisRegionBoundaryTime(
                    fromTime,
                    cached.startGameTime, cached.liftEndGameTime,
                    cached.sourceY, cached.fieldY,
                    cached.frameY
            );
            if (Double.isFinite(crossing)) return regionCheckTickForCrossing(crossing, fromTime);
        }

        if (fromTime < cached.fieldEndGameTime) {
            double fieldFrom = Math.max(fromTime, cached.liftEndGameTime);
            float currentX = fromTime >= cached.liftEndGameTime ? cached.frameX : cached.sourceX;
            float currentZ = fromTime >= cached.liftEndGameTime ? cached.frameZ : cached.sourceZ;
            double xCrossing = nextAxisRegionBoundaryTime(
                    fieldFrom,
                    cached.liftEndGameTime, cached.fieldEndGameTime,
                    cached.sourceX, 0.5F,
                    currentX
            );
            double zCrossing = nextAxisRegionBoundaryTime(
                    fieldFrom,
                    cached.liftEndGameTime, cached.fieldEndGameTime,
                    cached.sourceZ, 0.5F,
                    currentZ
            );
            crossing = Math.min(xCrossing, zCrossing);
            if (Double.isFinite(crossing)) return regionCheckTickForCrossing(crossing, fromTime);
        }

        if (fromTime < cached.endGameTime) {
            double descentFrom = Math.max(fromTime, cached.fieldEndGameTime);
            float currentY = fromTime >= cached.fieldEndGameTime ? cached.frameY : cached.fieldY;
            crossing = nextAxisRegionBoundaryTime(
                    descentFrom,
                    cached.fieldEndGameTime, cached.endGameTime,
                    cached.fieldY, 0.85F,
                    currentY
            );
            if (Double.isFinite(crossing)) return regionCheckTickForCrossing(crossing, fromTime);
        }

        return Long.MAX_VALUE;
    }

    private static long regionCheckTickForCrossing(double crossingTime, double fromTime) {
        if (crossingTime <= fromTime + REGION_PREDICTION_EPSILON) {
            return ((long) Math.floor(fromTime)) + 1L;
        }
        long tick = (long) Math.ceil(crossingTime - REGION_PREDICTION_EPSILON);
        return Math.max(tick, (long) Math.floor(fromTime));
    }

    /** Earliest crossing of a 16-block boundary during one monotonic smoothstep axis. */
    private static double nextAxisRegionBoundaryTime(
            double fromTime,
            double segmentStartTime,
            double segmentEndTime,
            float segmentStart,
            float segmentEnd,
            float currentValue
    ) {
        if (fromTime >= segmentEndTime) return Double.POSITIVE_INFINITY;
        double delta = (double) segmentEnd - segmentStart;
        if (Math.abs(delta) <= REGION_PREDICTION_EPSILON) return Double.POSITIVE_INFINITY;

        double effectiveFrom = Math.max(fromTime, segmentStartTime);
        double value = effectiveFrom <= segmentStartTime + REGION_PREDICTION_EPSILON
                ? segmentStart
                : currentValue;
        int cell = Mth.floor(value) >> SPATIAL_REGION_SHIFT;
        double boundary = delta > 0.0D
                ? (cell + 1) * (double) SPATIAL_REGION_SIZE
                : cell * (double) SPATIAL_REGION_SIZE;

        if (delta > 0.0D) {
            if (boundary > segmentEnd + REGION_PREDICTION_EPSILON) return Double.POSITIVE_INFINITY;
        } else {
            if (boundary < segmentEnd - REGION_PREDICTION_EPSILON) return Double.POSITIVE_INFINITY;
        }

        double fraction = (boundary - segmentStart) / delta;
        if (fraction < -REGION_PREDICTION_EPSILON || fraction > 1.0D + REGION_PREDICTION_EPSILON) {
            return Double.POSITIVE_INFINITY;
        }
        fraction = Math.max(0.0D, Math.min(1.0D, fraction));
        double u = inverseSmoothstep(fraction);
        return segmentStartTime + u * (segmentEndTime - segmentStartTime);
    }

    /** Inverts 3u^2-2u^3 on [0,1]; the rare 20-step search stays sub-millitick accurate. */
    private static double inverseSmoothstep(double value) {
        double low = 0.0D;
        double high = 1.0D;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) * 0.5D;
            double smooth = mid * mid * (3.0D - 2.0D * mid);
            if (smooth < value) low = mid;
            else high = mid;
        }
        return (low + high) * 0.5D;
    }

    /**
     * Classifies every occupied current-position region as exactly one visible regime.
     * The nearest point of the fixed region AABB keeps a boundary region in the
     * higher-quality tier until the complete 16^3 region crosses the distance threshold.
     */
    private void classifySpatialRegions(
            TransportRenderState state,
            byte forcedTier,
            boolean profiling
    ) {
        state.individualRegions.clear();
        state.batchedRegions.clear();
        state.hiddenRegions.clear();
        state.individualTransportCount = 0;

        int tested = 0;
        for (TransportRegion region : state.regions) {
            if (region.members.isEmpty()) continue;
            byte tier = forcedTier;
            if (isAutomaticTierMode(forcedTier)) {
                double nearestDistanceSq = distanceSqToAabb3D(
                        frameCameraWorldX, frameCameraWorldY, frameCameraWorldZ, region.worldLodBounds
                );
                tier = classifyDistanceTier((float) nearestDistanceSq);
            }

            region.tier = tier;
            if (tier == DISTANCE_TIER_INDIVIDUAL) {
                state.individualRegions.add(region);
                state.individualTransportCount += region.members.size();
            } else if (tier == DISTANCE_TIER_BATCHED) {
                state.batchedRegions.add(region);
            } else {
                state.hiddenRegions.add(region);
            }
            tested++;
        }

        if (profiling) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_REGION_LOD_PASSES);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_LOD_TESTS, tested);
        }
    }

    private static boolean isAutomaticTierMode(byte tier) {
        return tier == DISTANCE_TIER_UNCLASSIFIED;
    }

    private static double distanceSqToBounds3D(
            double x, double y, double z,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ
    ) {
        double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0D);
        double dy = y < minY ? minY - y : (y > maxY ? y - maxY : 0.0D);
        double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqToAabb3D(double x, double y, double z, AABB box) {
        double dx = x < box.minX ? box.minX - x : (x > box.maxX ? x - box.maxX : 0.0D);
        double dy = y < box.minY ? box.minY - y : (y > box.maxY ? y - box.maxY : 0.0D);
        double dz = z < box.minZ ? box.minZ - z : (z > box.maxZ ? z - box.maxZ : 0.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double maxDistanceSqToAabb3D(double x, double y, double z, AABB box) {
        double dx = Math.max(Math.abs(x - box.minX), Math.abs(x - box.maxX));
        double dy = Math.max(Math.abs(y - box.minY), Math.abs(y - box.maxY));
        double dz = Math.max(Math.abs(z - box.minZ), Math.abs(z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Removes expired or server-trimmed transports with a separate fixed operation
     * budget. Each cached transport participates in two intrusive indexes: insertion
     * order and an end-tick bucket. Removing through either path unlinks it from both
     * immediately, so cleanup never spends budget revisiting stale queue entries.
     */
    private void cleanupTransportCacheBounded(
            TransportRenderState state,
            List<ExcavatorClientVisuals.TransportVisual> transports,
            long gameTime,
            boolean profiling
    ) {
        long firstRemainingSequence = transports.isEmpty()
                ? Long.MAX_VALUE
                : transports.get(0).sequenceId();
        boolean hasExpired = hasExpiredBucket(state, gameTime);
        boolean hasTrimWork = state.insertionHead != null
                && state.insertionHead.sequenceId < firstRemainingSequence;
        if (!hasExpired && !hasTrimWork) return;

        long cleanupProfile = ExcavatorProfiler.begin(
                profiling, ExcavatorProfiler.TransportSubsection.CACHE_CLEANUP
        );

        int operations = 0;
        int removedRegions = 0;
        int removedTransports = 0;

        while (operations < MAX_CLEANUP_OPERATIONS_PER_FRAME && hasExpiredBucket(state, gameTime)) {
            long expiryTick = state.expirationBuckets.firstLongKey();
            ExpirationBucket bucket = state.expirationBuckets.get(expiryTick);
            CachedTransport cached = bucket != null ? bucket.first : null;
            if (cached == null) {
                // Defensive recovery for a malformed empty bucket. Normal unlinking removes
                // empty buckets immediately, so this branch should never execute.
                state.expirationBuckets.remove(expiryTick);
                continue;
            }

            boolean regionRemoved = removeCachedTransport(state, cached);
            operations++;
            if (profiling) {
                if (regionRemoved) removedRegions++;
                removedTransports++;
            }
        }

        while (operations < MAX_CLEANUP_OPERATIONS_PER_FRAME && state.insertionHead != null) {
            CachedTransport cached = state.insertionHead;
            if (cached.sequenceId >= firstRemainingSequence) break;

            boolean regionRemoved = removeCachedTransport(state, cached);
            operations++;
            if (profiling) {
                if (regionRemoved) removedRegions++;
                removedTransports++;
            }
        }

        if (profiling) {
            boolean cleanupBacklog = hasExpiredBucket(state, gameTime)
                    || (state.insertionHead != null
                    && state.insertionHead.sequenceId < firstRemainingSequence);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_CACHE_REMOVALS, removedTransports);
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_REGION_REMOVALS, removedRegions);
            if (cleanupBacklog && operations >= MAX_CLEANUP_OPERATIONS_PER_FRAME) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_CLEANUP_BUDGET_HITS);
            }
        }

        ExcavatorProfiler.end(
                ExcavatorProfiler.TransportSubsection.CACHE_CLEANUP,
                cleanupProfile
        );
    }

    private static boolean hasExpiredBucket(TransportRenderState state, long gameTime) {
        return !state.expirationBuckets.isEmpty()
                && state.expirationBuckets.firstLongKey() <= gameTime;
    }

    private static void linkInsertionTail(TransportRenderState state, CachedTransport cached) {
        cached.insertionPrev = state.insertionTail;
        cached.insertionNext = null;
        if (state.insertionTail == null) {
            state.insertionHead = cached;
        } else {
            state.insertionTail.insertionNext = cached;
        }
        state.insertionTail = cached;
    }

    private static void unlinkInsertion(TransportRenderState state, CachedTransport cached) {
        CachedTransport previous = cached.insertionPrev;
        CachedTransport next = cached.insertionNext;
        if (previous == null) {
            if (state.insertionHead == cached) state.insertionHead = next;
        } else {
            previous.insertionNext = next;
        }
        if (next == null) {
            if (state.insertionTail == cached) state.insertionTail = previous;
        } else {
            next.insertionPrev = previous;
        }
        cached.insertionPrev = null;
        cached.insertionNext = null;
    }

    private static void linkExpirationBucket(TransportRenderState state, CachedTransport cached) {
        ExpirationBucket bucket = state.expirationBuckets.get(cached.endGameTime);
        if (bucket == null) {
            bucket = new ExpirationBucket(cached.endGameTime);
            state.expirationBuckets.put(cached.endGameTime, bucket);
        }

        cached.expirationBucket = bucket;
        cached.expiryPrev = bucket.last;
        cached.expiryNext = null;
        if (bucket.last == null) {
            bucket.first = cached;
        } else {
            bucket.last.expiryNext = cached;
        }
        bucket.last = cached;
        bucket.size++;
    }

    private static void unlinkExpirationBucket(TransportRenderState state, CachedTransport cached) {
        ExpirationBucket bucket = cached.expirationBucket;
        if (bucket == null) return;

        CachedTransport previous = cached.expiryPrev;
        CachedTransport next = cached.expiryNext;
        if (previous == null) {
            bucket.first = next;
        } else {
            previous.expiryNext = next;
        }
        if (next == null) {
            bucket.last = previous;
        } else {
            next.expiryPrev = previous;
        }

        cached.expirationBucket = null;
        cached.expiryPrev = null;
        cached.expiryNext = null;
        bucket.size--;
        if (bucket.size == 0) {
            state.expirationBuckets.remove(bucket.endGameTime);
        }
    }

    private byte classifyDistanceTier(float distanceSq) {
        if (distanceSq >= transportMaxRenderDistanceSq) return DISTANCE_TIER_HIDDEN;
        if (distanceSq >= batchDistanceSq) return DISTANCE_TIER_BATCHED;
        return DISTANCE_TIER_INDIVIDUAL;
    }

    /**
     * Allocation-free key for fixed current-position 16^3 regions. Coordinates are local
     * to the excavator and use 21 signed bits per axis, far beyond any practical excavator
     * volume. Block/item kind is intentionally not part of spatial membership.
     */
    private static long packSpatialRegionKey(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL)
                | (((long) z & 0x1FFFFFL) << 21)
                | (((long) y & 0x1FFFFFL) << 42);
    }

    private void addRegion(TransportRenderState state, TransportRegion region) {
        region.stateIndex = state.regions.size();
        state.regions.add(region);
        state.regionsByKey.put(region.key, region);
    }

    private void removeRegion(TransportRenderState state, TransportRegion region) {
        state.regionsByKey.remove(region.key);
        int index = region.stateIndex;
        if (index < 0 || index >= state.regions.size()) return;
        int lastIndex = state.regions.size() - 1;
        TransportRegion moved = state.regions.get(lastIndex);
        if (index != lastIndex) {
            state.regions.set(index, moved);
            moved.stateIndex = index;
        }
        state.regions.remove(lastIndex);
        region.stateIndex = -1;
    }

    private void addToRegion(TransportRenderState state, TransportRegion region, CachedTransport cached) {
        cached.region = region;
        cached.regionMemberIndex = region.members.size();
        region.members.add(cached);
        state.activeTransportCount++;
    }

    /**
     * Moves one active transport between current-position regions without changing the
     * global active count. Returns true when the source region becomes empty and is removed.
     */
    private boolean moveToRegion(TransportRenderState state, CachedTransport cached, TransportRegion target) {
        TransportRegion sourceRegion = cached.region;
        if (sourceRegion == target) return false;

        boolean sourceRegionRemoved = false;
        if (sourceRegion != null) {
            int index = cached.regionMemberIndex;
            int lastIndex = sourceRegion.members.size() - 1;
            CachedTransport moved = sourceRegion.members.get(lastIndex);
            if (index != lastIndex) {
                sourceRegion.members.set(index, moved);
                moved.regionMemberIndex = index;
            }
            sourceRegion.members.remove(lastIndex);
            if (sourceRegion.members.isEmpty()) {
                removeRegion(state, sourceRegion);
                sourceRegionRemoved = true;
            }
        }

        cached.region = target;
        cached.regionMemberIndex = target.members.size();
        target.members.add(cached);
        return sourceRegionRemoved;
    }

    private boolean removeFromRegion(TransportRenderState state, CachedTransport cached) {
        TransportRegion region = cached.region;
        if (region == null) return false;
        int index = cached.regionMemberIndex;
        int lastIndex = region.members.size() - 1;
        CachedTransport moved = region.members.get(lastIndex);
        if (index != lastIndex) {
            region.members.set(index, moved);
            moved.regionMemberIndex = index;
        }
        region.members.remove(lastIndex);
        state.activeTransportCount--;
        cached.region = null;
        cached.regionMemberIndex = -1;
        if (!region.members.isEmpty()) return false;
        removeRegion(state, region);
        return true;
    }

    /** Returns true when removing this transport also removed its now-empty spatial region. */
    private boolean removeCachedTransport(TransportRenderState state, CachedTransport cached) {
        if (!cached.active) return false;
        unlinkInsertion(state, cached);
        unlinkExpirationBucket(state, cached);
        unscheduleRegionCheck(state, cached);
        boolean removedRegion = removeFromRegion(state, cached);
        cached.active = false;
        return removedRegion;
    }

    private float nearestBatchedRegionDistanceSq(List<TransportRegion> regions) {
        double nearest = Double.POSITIVE_INFINITY;
        for (TransportRegion region : regions) {
            if (region.members.isEmpty()) continue;
            double distanceSq = distanceSqToAabb3D(
                    frameCameraWorldX, frameCameraWorldY, frameCameraWorldZ, region.worldLodBounds
            );
            if (distanceSq < nearest) nearest = distanceSq;
        }
        return nearest >= Float.MAX_VALUE ? Float.POSITIVE_INFINITY : (float) nearest;
    }

    private int calculateBatchTargetMarkers(float nearestDistanceSq) {
        if (!Float.isFinite(nearestDistanceSq)) return 0;
        float distance = (float) Math.sqrt(Math.max(0.0F, nearestDistanceSq));
        if (distance <= batchDistance) return maxBatchedMarkers;
        if (distance >= transportMaxRenderDistance) return minBatchedMarkers;

        float span = Math.max(0.001F, transportMaxRenderDistance - batchDistance);
        float t = Mth.clamp((distance - batchDistance) / span, 0.0F, 1.0F);
        float remaining = 1.0F - t;
        float factor = (float) Math.pow(remaining, batchFalloffExponent);
        int target = Math.round(minBatchedMarkers + (maxBatchedMarkers - minBatchedMarkers) * factor);

        // Four-marker steps avoid representative-list churn from sub-block camera motion
        // while remaining visually continuous at the scale of hundreds of transports.
        int quantized = ((target + 2) / 4) * 4;
        return Mth.clamp(quantized, minBatchedMarkers, maxBatchedMarkers);
    }

    /**
     * Stable representative rendering for the continuous BATCHED regime.
     * Representatives persist while their transport remains active, visible and batched.
     * When the distance-derived cap shrinks or grows, the existing representatives are
     * trimmed/refilled instead of resampling the complete set every frame.
     */
    private int queueBatchedRegionMarkers(
            List<TransportRegion> regions,
            List<TransportRegion> visibleRegions,
            List<CachedTransport> stableRepresentatives,
            ClientLevel level,
            BlockPos origin,
            double renderGameTime,
            int requestedMaxMarkers,
            float cameraX,
            float cameraY,
            float cameraZ,
            FrustumCullStats frustumCullStats,
            boolean profiling,
            boolean emitGeometry
    ) {
        visibleRegions.clear();
        if (regions.isEmpty() || requestedMaxMarkers <= 0) {
            clearStableRepresentatives(stableRepresentatives);
            return 0;
        }

        int visibleSourceCount = 0;
        int visibilityStamp = ++batchRepresentativeVisibilityStamp;
        if (visibilityStamp == Integer.MIN_VALUE) {
            batchRepresentativeVisibilityStamp = 1;
            visibilityStamp = 1;
            for (TransportRenderState batchState : transportStates.values()) {
                for (TransportRegion region : batchState.regions) region.batchVisibilityStamp = 0;
            }
        }

        for (int regionMemberIndex = 0, regionCount = regions.size(); regionMemberIndex < regionCount; regionMemberIndex++) {
            TransportRegion region = regions.get(regionMemberIndex);
            if (!isTransportRegionVisible(region, frustumCullStats)) continue;
            region.batchVisibilityStamp = visibilityStamp;
            visibleRegions.add(region);
            visibleSourceCount += region.members.size();
        }
        if (visibleSourceCount <= 0) {
            clearStableRepresentatives(stableRepresentatives);
            return 0;
        }

        int target = Math.min(Math.max(1, requestedMaxMarkers), visibleSourceCount);

        for (int i = stableRepresentatives.size() - 1; i >= 0; i--) {
            CachedTransport cached = stableRepresentatives.get(i);
            TransportRegion region = cached.region;
            if (!cached.active
                    || region == null
                    || region.tier != DISTANCE_TIER_BATCHED
                    || region.batchVisibilityStamp != visibilityStamp) {
                cached.batchRepresentative = false;
                stableRepresentatives.remove(i);
            }
        }

        while (stableRepresentatives.size() > target) {
            CachedTransport removed = stableRepresentatives.remove(stableRepresentatives.size() - 1);
            removed.batchRepresentative = false;
        }

        if (stableRepresentatives.size() < target) {
            int needed = target - stableRepresentatives.size();
            int regionCursor = 0;
            int flatBase = 0;
            TransportRegion current = visibleRegions.get(0);
            double sampleStep = (double) visibleSourceCount / (double) target;
            double samplePosition = sampleStep * 0.5D;

            for (int slot = 0; slot < target && needed > 0; slot++) {
                int wanted = Math.min(visibleSourceCount - 1, (int) samplePosition);
                samplePosition += sampleStep;

                while (current != null) {
                    int currentCount = current.members.size();
                    if (wanted < flatBase + currentCount) break;
                    flatBase += currentCount;
                    regionCursor++;
                    current = regionCursor < visibleRegions.size() ? visibleRegions.get(regionCursor) : null;
                }
                if (current == null) break;

                int logicalIndex = wanted - flatBase;
                CachedTransport candidate = current.members.get(logicalIndex);
                if (candidate == null || candidate.batchRepresentative) continue;
                candidate.batchRepresentative = true;
                stableRepresentatives.add(candidate);
                needed--;
            }

            if (needed > 0) {
                outer:
                for (TransportRegion region : visibleRegions) {
                    for (CachedTransport candidate : region.members) {
                        if (candidate.batchRepresentative) continue;
                        candidate.batchRepresentative = true;
                        stableRepresentatives.add(candidate);
                        if (--needed <= 0) break outer;
                    }
                }
            }
        }

        int queued = 0;
        for (int i = 0; i < stableRepresentatives.size(); i++) {
            CachedTransport cached = stableRepresentatives.get(i);
            calculateCachedTransportPosition(cached, renderGameTime);
            if (emitGeometry) {
                ExcavatorTransportMarkerRenderer.queueBatchedSmallMarkerReserved(
                        transportPosition.x - cameraX,
                        transportPosition.y - cameraY,
                        transportPosition.z - cameraZ,
                        resolveLitTransportMarkerColor(cached, level, origin, profiling)
                );
            }
            queued++;
        }
        return queued;
    }

    private static void clearStableRepresentatives(List<CachedTransport> representatives) {
        for (CachedTransport cached : representatives) cached.batchRepresentative = false;
        representatives.clear();
    }

    private boolean hasRenderableTexture(ExcavatorClientVisuals.TransportVisual transport) {
        return !transport.visualStack().isEmpty();
    }

    private boolean isTransportRegionVisible(TransportRegion region, FrustumCullStats stats) {
        if (stats != null) stats.regionsTested++;
        boolean visible = frameFrustum.isVisible(region.worldFrustumBounds);
        if (!visible && stats != null) {
            stats.regionsCulled++;
            stats.membersCulled += region.members.size();
        }
        return visible;
    }

    private static final class FrustumCullStats {
        private int regionsTested;
        private int regionsCulled;
        private int membersCulled;

        private void reset() {
            regionsTested = 0;
            regionsCulled = 0;
            membersCulled = 0;
        }
    }

    private static final class CachedTransport {
        private final long sequenceId;
        private final boolean blockItem;
        private final ItemStack visualStack;
        private ExcavatorClientVisuals.ItemBillboardTexture itemTexture;
        private int markerRgb = -1;
        // Final marker RGB is cached for the current 16^3 spatial region. A transport
        // only recomputes this when it crosses a region boundary, so distant markers
        // do not perform light lookups or RGB math every frame.
        private int litMarkerRgb = -1;
        private long litMarkerRegionKey = Long.MIN_VALUE;
        private float blockU0;
        private float blockU1;
        private float blockV0;
        private float blockV1;
        private boolean appearanceResolved;
        private final long startGameTime;
        private final long liftEndGameTime;
        private final long fieldEndGameTime;
        private final long endGameTime;

        // Compact per-transport trajectory cache shared by spatial membership,
        // individual rendering and continuous representative batching.
        private final float sourceX;
        private final float sourceY;
        private final float sourceZ;
        private final float fieldY;
        private final float liftCurve2;
        private final float liftCurve3;
        private final float fieldXCurve2;
        private final float fieldXCurve3;
        private final float fieldZCurve2;
        private final float fieldZCurve3;
        private final float descentCurve2;
        private final float descentCurve3;
        private byte animationPhase = TRANSPORT_PHASE_LIFT;
        // Animation time is monotonic once this visual has been materialized. The raw
        // level gameTime + partialTick clock can briefly move backwards when the
        // integrated server stalls; clamping here prevents airborne transports from
        // visibly rewinding.
        private double lastAnimationRenderTime = Double.NEGATIVE_INFINITY;
        private long framePositionRenderTimeBits = Long.MIN_VALUE;
        private float frameX;
        private float frameY;
        private float frameZ;

        // Intrusive timing-wheel membership for the next predicted 16-block boundary.
        // No scheduler node is allocated: CachedTransport itself is the wheel node.
        private long scheduledRegionCheckTick = Long.MAX_VALUE;
        private int regionCheckWheelIndex = -1;
        private CachedTransport regionCheckPrev;
        private CachedTransport regionCheckNext;

        private TransportRegion region;
        private int regionMemberIndex = -1;
        private boolean active;
        private boolean batchRepresentative;

        // Intrusive cache indexes let lifecycle removal unlink the transport from both
        // insertion and expiry orderings without allocating auxiliary nodes.
        private CachedTransport insertionPrev;
        private CachedTransport insertionNext;
        private ExpirationBucket expirationBucket;
        private CachedTransport expiryPrev;
        private CachedTransport expiryNext;

        private CachedTransport(ExcavatorClientVisuals.TransportVisual transport, long endGameTime) {
            this.sequenceId = transport.sequenceId();
            this.blockItem = transport.blockItem();
            this.visualStack = transport.visualStack();
            this.startGameTime = transport.startGameTime();
            this.liftEndGameTime = startGameTime + transport.liftEndTicks();
            this.fieldEndGameTime = startGameTime + transport.fieldEndTicks();
            this.endGameTime = endGameTime;

            this.sourceX = transport.sourceX();
            this.sourceY = transport.sourceY();
            this.sourceZ = transport.sourceZ();
            this.fieldY = transport.fieldY();

            float liftInv = transport.invLiftTicks();
            float liftInv2 = liftInv * liftInv;
            float liftBase2 = 3.0F * liftInv2;
            float liftBase3 = -2.0F * liftInv2 * liftInv;
            this.liftCurve2 = transport.liftDeltaY() * liftBase2;
            this.liftCurve3 = transport.liftDeltaY() * liftBase3;

            float fieldInv = transport.invFieldTicks();
            float fieldInv2 = fieldInv * fieldInv;
            float fieldBase2 = 3.0F * fieldInv2;
            float fieldBase3 = -2.0F * fieldInv2 * fieldInv;
            this.fieldXCurve2 = transport.fieldDeltaX() * fieldBase2;
            this.fieldXCurve3 = transport.fieldDeltaX() * fieldBase3;
            this.fieldZCurve2 = transport.fieldDeltaZ() * fieldBase2;
            this.fieldZCurve3 = transport.fieldDeltaZ() * fieldBase3;

            float descentInv = transport.invDescentTicks();
            float descentInv2 = descentInv * descentInv;
            float descentBase2 = 3.0F * descentInv2;
            float descentBase3 = -2.0F * descentInv2 * descentInv;
            this.descentCurve2 = transport.descentDeltaY() * descentBase2;
            this.descentCurve3 = transport.descentDeltaY() * descentBase3;

        }
    }

    private static final class ExpirationBucket {
        private final long endGameTime;
        private CachedTransport first;
        private CachedTransport last;
        private int size;

        private ExpirationBucket(long endGameTime) {
            this.endGameTime = endGameTime;
        }
    }

    private static final class TransportRegion {
        private final long key;
        private final List<CachedTransport> members = new ArrayList<>();
        private byte tier = DISTANCE_TIER_INDIVIDUAL;
        private int stateIndex = -1;
        private int batchVisibilityStamp;
        private final AABB worldLodBounds;
        private final AABB worldFrustumBounds;
        private int cachedPackedLight = Integer.MIN_VALUE;
        private int cachedMarkerLightPercent = -1;
        private final float lightSampleLocalX;
        private final float lightSampleLocalY;
        private final float lightSampleLocalZ;

        private TransportRegion(
                long key,
                int cellX,
                int cellY,
                int cellZ,
                BlockPos origin
        ) {
            this.key = key;
            double minLocalX = cellX * (double) SPATIAL_REGION_SIZE;
            double minLocalY = cellY * (double) SPATIAL_REGION_SIZE;
            double minLocalZ = cellZ * (double) SPATIAL_REGION_SIZE;
            double maxLocalX = minLocalX + SPATIAL_REGION_SIZE;
            double maxLocalY = minLocalY + SPATIAL_REGION_SIZE;
            double maxLocalZ = minLocalZ + SPATIAL_REGION_SIZE;

            AABB exactRegionBounds = new AABB(
                    origin.getX() + minLocalX,
                    origin.getY() + minLocalY,
                    origin.getZ() + minLocalZ,
                    origin.getX() + maxLocalX,
                    origin.getY() + maxLocalY,
                    origin.getZ() + maxLocalZ
            );
            // Expand region bounds to cover the maximum sub-tick motion between
            // membership updates and keep frustum culling conservative.
            worldLodBounds = exactRegionBounds.inflate(REGION_TICK_MOTION_PADDING);
            worldFrustumBounds = exactRegionBounds.inflate(TRANSPORT_REGION_FRUSTUM_PADDING);
            lightSampleLocalX = (float) (minLocalX + SPATIAL_REGION_SIZE * 0.5D);
            lightSampleLocalY = (float) (minLocalY + SPATIAL_REGION_SIZE * 0.5D);
            lightSampleLocalZ = (float) (minLocalZ + SPATIAL_REGION_SIZE * 0.5D);
        }
    }

    private static final class TransportRenderState {
        private final Long2ObjectOpenHashMap<TransportRegion> regionsByKey = new Long2ObjectOpenHashMap<>();
        private final List<TransportRegion> regions = new ArrayList<>();
        private final List<TransportRegion> individualRegions = new ArrayList<>();
        private final List<TransportRegion> batchedRegions = new ArrayList<>();
        private final List<TransportRegion> hiddenRegions = new ArrayList<>();

        private final List<TransportRegion> visibleRegionScratch = new ArrayList<>();
        private final List<CachedTransport> batchedRepresentatives = new ArrayList<>();

        // Each transport is linked directly into insertion order and an expiry bucket,
        // allowing removal from both lifecycle indexes without auxiliary nodes.
        private CachedTransport insertionHead;
        private CachedTransport insertionTail;
        private final Long2ObjectAVLTreeMap<ExpirationBucket> expirationBuckets =
                new Long2ObjectAVLTreeMap<>();
        private long lastSeenSequence = Long.MIN_VALUE;
        private int activeTransportCount;
        private int individualTransportCount;
        // Fixed-size intrusive timing wheel. Only bucket-head references are stored here;
        // transports provide the linked-list nodes themselves.
        private final CachedTransport[] regionCheckWheel = new CachedTransport[REGION_TIMING_WHEEL_SIZE];
        private long lastRegionMembershipTick = Long.MIN_VALUE;
    }

    /**
     * Resolves a marker color at most once per transport. The underlying block/item
     * average is itself cached globally, so the steady-state marker path only reads
     * the packed RGB already stored on CachedTransport.
     */
    private int resolveTransportMarkerColor(CachedTransport cached, ClientLevel level) {
        int rgb = cached.markerRgb;
        if (rgb >= 0) return rgb;

        int seed = (int) (cached.sequenceId ^ (cached.sequenceId >>> 32));
        if (cached.blockItem && cached.visualStack.getItem() instanceof BlockItem blockItem) {
            rgb = ExcavatorClientVisuals.resolveBlockMarkerColor(blockItem);
        } else {
            rgb = ExcavatorClientVisuals.resolveItemMarkerColor(cached.visualStack, level, seed);
        }
        cached.markerRgb = rgb;
        return rgb;
    }

    /**
     * Applies a cheap approximation of Minecraft world lighting to a marker color.
     * Lighting is sampled once per occupied 16^3 region and the final RGB is cached
     * on the transport until it crosses into another region. Steady-state marker
     * rendering is therefore still just a packed-int read.
     */
    private int resolveLitTransportMarkerColor(
            CachedTransport cached,
            ClientLevel level,
            BlockPos origin,
            boolean profiling
    ) {
        TransportRegion region = cached.region;
        if (region == null) return resolveTransportMarkerColor(cached, level);

        if (cached.litMarkerRgb >= 0 && cached.litMarkerRegionKey == region.key) {
            return cached.litMarkerRgb;
        }

        int baseRgb = resolveTransportMarkerColor(cached, level);
        int lightPercent = resolveRegionMarkerLightPercent(region, level, origin, profiling);
        int litRgb = multiplyMarkerRgb(baseRgb, lightPercent);
        cached.litMarkerRgb = litRgb;
        cached.litMarkerRegionKey = region.key;
        return litRgb;
    }

    private int resolveRegionMarkerLightPercent(
            TransportRegion region,
            ClientLevel level,
            BlockPos origin,
            boolean profiling
    ) {
        int cached = region.cachedMarkerLightPercent;
        if (cached >= 0) return cached;

        int packedLight = resolveRegionPackedLight(region, level, origin, profiling);
        int lightLevel = Math.max(LightTexture.block(packedLight), LightTexture.sky(packedLight));

        // Intentionally keep markers darker than the represented block/item. Bright
        // markers stand out too strongly at dot LOD, while slightly dark markers blend
        // into the scene better: level 0 -> 35%, level 15 -> 80%.
        int lightPercent = 35 + lightLevel * 3;
        region.cachedMarkerLightPercent = lightPercent;
        return lightPercent;
    }

    private static int multiplyMarkerRgb(int rgb, int percent) {
        if (percent >= 100) return rgb;
        int r = ((rgb >>> 16) & 0xFF) * percent / 100;
        int g = ((rgb >>> 8) & 0xFF) * percent / 100;
        int b = (rgb & 0xFF) * percent / 100;
        return (r << 16) | (g << 8) | b;
    }

    private boolean ensureTransportAppearance(CachedTransport cached, ClientLevel level) {
        if (cached.appearanceResolved) {
            return cached.blockItem ? cached.blockU1 > cached.blockU0 : cached.itemTexture != null;
        }
        cached.appearanceResolved = true;
        if (cached.visualStack.isEmpty()) return false;

        if (cached.blockItem) {
            if (!(cached.visualStack.getItem() instanceof BlockItem blockItem)) return false;
            ExcavatorClientVisuals.BlockCubeTexture texture =
                    ExcavatorClientVisuals.resolveBlockTexture(blockItem);
            if (texture == null) return false;
            cached.blockU0 = texture.u0();
            cached.blockU1 = texture.u1();
            cached.blockV0 = texture.v0();
            cached.blockV1 = texture.v1();
            cached.markerRgb = texture.markerRgb();
            return true;
        }

        int seed = (int) (cached.sequenceId ^ (cached.sequenceId >>> 32));
        cached.itemTexture = ExcavatorClientVisuals.resolveItemTexture(
                cached.visualStack, level, seed
        );
        return cached.itemTexture != null;
    }

    private int resolveRegionPackedLight(
            TransportRegion region,
            ClientLevel level,
            BlockPos origin,
            boolean profiling
    ) {
        if (region == null) {
            return LightTexture.pack(LaserExcavatorClientConfig.MIN_TRANSPORT_BLOCK_LIGHT.get(), 15);
        }

        int packed = region.cachedPackedLight;
        if (packed != Integer.MIN_VALUE) {
            if (profiling) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_REGION_LIGHT_CACHE_HITS);
            }
            return packed;
        }

        regionLightSamplePos.set(
                origin.getX() + Mth.floor(region.lightSampleLocalX),
                origin.getY() + Mth.floor(region.lightSampleLocalY),
                origin.getZ() + Mth.floor(region.lightSampleLocalZ)
        );
        int sampledLight = LevelRenderer.getLightColor(level, regionLightSamplePos);
        packed = LightTexture.pack(
                Math.max(
                        LaserExcavatorClientConfig.MIN_TRANSPORT_BLOCK_LIGHT.get(),
                        LightTexture.block(sampledLight)
                ),
                LightTexture.sky(sampledLight)
        );
        region.cachedPackedLight = packed;

        if (profiling) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_REGION_LIGHT_CACHE_MISSES);
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_VISUAL_LIGHT_LOOKUPS);
        }
        return packed;
    }

    private byte renderIndividualTransport(
            CachedTransport cached,
            ClientLevel level,
            BlockPos origin,
            double renderGameTime,
            float cameraX,
            float cameraY,
            float cameraZ,
            PoseStack.Pose basePose,
            VertexConsumer consumer,
            boolean profiling,
            boolean emitGeometry
    ) {
        calculateCachedTransportPosition(cached, renderGameTime);

        float cameraDeltaX = cameraX - transportPosition.x;
        float cameraDeltaY = cameraY - transportPosition.y;
        float cameraDeltaZ = cameraZ - transportPosition.z;
        float distanceSq = cameraDeltaX * cameraDeltaX
                + cameraDeltaY * cameraDeltaY
                + cameraDeltaZ * cameraDeltaZ;

        if (cached.blockItem) {
            if (distanceSq >= largeMarkerDistanceSq) {
                if (emitGeometry) {
                    float relX = -cameraDeltaX;
                    float relY = -cameraDeltaY;
                    float relZ = -cameraDeltaZ;
                    if (distanceSq < smallMarkerDistanceSq) {
                        ExcavatorTransportMarkerRenderer.queueLargeMarkerReserved(
                                relX, relY, relZ, resolveLitTransportMarkerColor(cached, level, origin, profiling)
                        );
                    } else {
                        ExcavatorTransportMarkerRenderer.queueIndividualSmallMarkerReserved(
                                relX, relY, relZ, resolveLitTransportMarkerColor(cached, level, origin, profiling)
                        );
                    }
                }
                return VISUAL_BLOCK_MARKER;
            }

            if (distanceSq >= billboardDistanceSq) {
                int billboardBand = blockBillboardBand(distanceSq);
                boolean sampleBillboard = profiling && (billboardProfileSampleCursor++ & 15) == 0;
                long billboardProfile = ExcavatorProfiler.begin(
                        sampleBillboard, ExcavatorProfiler.TransportSubsection.BLOCK_BILLBOARD_RENDERING
                );
                if (emitGeometry && ensureTransportAppearance(cached, level)) {
                    renderTexturedBlockBillboard(
                            basePose,
                            consumer,
                            cached.blockU0, cached.blockU1, cached.blockV0, cached.blockV1,
                            transportPosition.x,
                            transportPosition.y,
                            transportPosition.z,
                            resolveRegionPackedLight(cached.region, level, origin, profiling),
                            billboardBand
                    );
                }
                ExcavatorProfiler.end(
                        ExcavatorProfiler.TransportSubsection.BLOCK_BILLBOARD_RENDERING, billboardProfile
                );
                return VISUAL_BLOCK_BILLBOARD;
            }

            boolean sampleCube = profiling && (cubeProfileSampleCursor++ & 15) == 0;
            long cubeProfile = ExcavatorProfiler.begin(
                    sampleCube, ExcavatorProfiler.TransportSubsection.BLOCK_CUBE_RENDERING
            );
            if (emitGeometry && ensureTransportAppearance(cached, level)) {
                renderTexturedCube(
                        basePose,
                        consumer,
                        cached.blockU0, cached.blockU1, cached.blockV0, cached.blockV1,
                        transportPosition.x,
                        transportPosition.y,
                        transportPosition.z,
                        cameraDeltaX,
                        cameraDeltaY,
                        cameraDeltaZ,
                        resolveRegionPackedLight(cached.region, level, origin, profiling)
                );
            }
            ExcavatorProfiler.end(ExcavatorProfiler.TransportSubsection.BLOCK_CUBE_RENDERING, cubeProfile);
            return VISUAL_BLOCK_CUBE;
        }

        if (distanceSq >= smallMarkerDistanceSq) {
            if (emitGeometry) {
                ExcavatorTransportMarkerRenderer.queueIndividualSmallMarkerReserved(
                        -cameraDeltaX, -cameraDeltaY, -cameraDeltaZ,
                        resolveLitTransportMarkerColor(cached, level, origin, profiling)
                );
            }
            return VISUAL_ITEM_MARKER;
        }

        if (emitGeometry && ensureTransportAppearance(cached, level)) {
            renderTexturedItemBillboard(
                    basePose,
                    consumer,
                    cached.itemTexture,
                    transportPosition.x,
                    transportPosition.y,
                    transportPosition.z,
                    resolveRegionPackedLight(cached.region, level, origin, profiling)
            );
        }
        return VISUAL_ITEM_BILLBOARD;
    }

    private void renderTexturedBlockBillboard(
            PoseStack.Pose basePose,
            VertexConsumer consumer,
            float u0,
            float u1,
            float v0,
            float v1,
            float centerX,
            float centerY,
            float centerZ,
            int packedLight,
            int band
    ) {
        ensureTransformedBlockNormals(basePose);
        float[] cornerX = blockBillboardBandCornerX[band];
        float[] cornerY = blockBillboardBandCornerY[band];
        float[] cornerZ = blockBillboardBandCornerZ[band];
        blockBillboardVertex(consumer, basePose, centerX, centerY, centerZ, cornerX, cornerY, cornerZ, 0, u0, v1, packedLight);
        blockBillboardVertex(consumer, basePose, centerX, centerY, centerZ, cornerX, cornerY, cornerZ, 1, u1, v1, packedLight);
        blockBillboardVertex(consumer, basePose, centerX, centerY, centerZ, cornerX, cornerY, cornerZ, 2, u1, v0, packedLight);
        blockBillboardVertex(consumer, basePose, centerX, centerY, centerZ, cornerX, cornerY, cornerZ, 3, u0, v0, packedLight);
    }

    private int blockBillboardBand(float distanceSq) {
        if (distanceSq <= blockBillboardShrinkStartSq) return 0;
        float t = (distanceSq - blockBillboardShrinkStartSq) * blockBillboardShrinkInvRangeSq;
        if (t >= 1.0F) return BLOCK_BILLBOARD_TAPER_LAST_BAND;
        return (int) (t * BLOCK_BILLBOARD_TAPER_LAST_BAND + 0.5F);
    }

    private void blockBillboardVertex(
            VertexConsumer consumer,
            PoseStack.Pose basePose,
            float centerX,
            float centerY,
            float centerZ,
            float[] cornerX,
            float[] cornerY,
            float[] cornerZ,
            int corner,
            float u,
            float v,
            int packedLight
    ) {
        consumer.addVertex(
                        basePose,
                        centerX + cornerX[corner],
                        centerY + cornerY[corner],
                        centerZ + cornerZ[corner]
                )
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(blockBillboardNormalX, blockBillboardNormalY, blockBillboardNormalZ);
    }

    private static float[] createBlockBillboardTaperScales() {
        float[] values = new float[BLOCK_BILLBOARD_TAPER_BAND_COUNT];
        for (int band = 0; band < BLOCK_BILLBOARD_TAPER_BAND_COUNT; band++) {
            float t = band / (float) BLOCK_BILLBOARD_TAPER_LAST_BAND;
            values[band] = 1.0F - (1.0F - BLOCK_BILLBOARD_MIN_SCALE) * t;
        }
        return values;
    }

    /**
     * Emits one camera-facing, rotating item sprite directly into the shared
     * block/item atlas buffer. The camera basis and rotation are calculated once
     * per block-entity render pass in RotatingBillboardGeometry, leaving only four vertex writes
     * per non-block transport.
     */
    private void renderTexturedItemBillboard(
            PoseStack.Pose basePose,
            VertexConsumer consumer,
            ExcavatorClientVisuals.ItemBillboardTexture texture,
            float centerX,
            float centerY,
            float centerZ,
            int packedLight
    ) {
        billboardVertex(consumer, basePose, centerX, centerY, centerZ, 0, texture.u0(), texture.v1(), packedLight);
        billboardVertex(consumer, basePose, centerX, centerY, centerZ, 1, texture.u1(), texture.v1(), packedLight);
        billboardVertex(consumer, basePose, centerX, centerY, centerZ, 2, texture.u1(), texture.v0(), packedLight);
        billboardVertex(consumer, basePose, centerX, centerY, centerZ, 3, texture.u0(), texture.v0(), packedLight);
    }

    private void billboardVertex(
            VertexConsumer consumer,
            PoseStack.Pose basePose,
            float centerX,
            float centerY,
            float centerZ,
            int corner,
            float u,
            float v,
            int packedLight
    ) {
        consumer.addVertex(
                        basePose,
                        centerX + itemBillboardGeometry.cornerX[corner],
                        centerY + itemBillboardGeometry.cornerY[corner],
                        centerZ + itemBillboardGeometry.cornerZ[corner]
                )
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(
                        basePose,
                        itemBillboardGeometry.normalX,
                        itemBillboardGeometry.normalY,
                        itemBillboardGeometry.normalZ
                );
    }

    private void renderTexturedCube(
            PoseStack.Pose basePose,
            VertexConsumer consumer,
            float u0,
            float u1,
            float v0,
            float v1,
            float centerX,
            float centerY,
            float centerZ,
            float cameraDeltaX,
            float cameraDeltaY,
            float cameraDeltaZ,
            int packedLight
    ) {
        ensureTransformedBlockNormals(basePose);
        float h = rotatingCubeGeometry.halfSize;

        // Pick exactly one face from the rotated South/North pair. Dotting the
        // camera direction against the already-rotated face normal avoids any
        // inverse-rotation or temporary vector allocation.
        float southDot = cameraDeltaX * rotatingCubeGeometry.southNormalX
                + cameraDeltaZ * rotatingCubeGeometry.southNormalZ;
        if (southDot >= 0.0) {
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 3, -h, u0, v1, cubeSouthNormalX, cubeSouthNormalY, cubeSouthNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 2, -h, u1, v1, cubeSouthNormalX, cubeSouthNormalY, cubeSouthNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 2,  h, u1, v0, cubeSouthNormalX, cubeSouthNormalY, cubeSouthNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 3,  h, u0, v0, cubeSouthNormalX, cubeSouthNormalY, cubeSouthNormalZ, packedLight);
        } else {
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 1, -h, u0, v1, cubeNorthNormalX, cubeNorthNormalY, cubeNorthNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 0, -h, u1, v1, cubeNorthNormalX, cubeNorthNormalY, cubeNorthNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 0,  h, u1, v0, cubeNorthNormalX, cubeNorthNormalY, cubeNorthNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 1,  h, u0, v0, cubeNorthNormalX, cubeNorthNormalY, cubeNorthNormalZ, packedLight);
        }

        // Pick one face from the rotated East/West pair.
        float eastDot = cameraDeltaX * rotatingCubeGeometry.eastNormalX
                + cameraDeltaZ * rotatingCubeGeometry.eastNormalZ;
        if (eastDot >= 0.0) {
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 2, -h, u0, v1, cubeEastNormalX, cubeEastNormalY, cubeEastNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 1, -h, u1, v1, cubeEastNormalX, cubeEastNormalY, cubeEastNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 1,  h, u1, v0, cubeEastNormalX, cubeEastNormalY, cubeEastNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 2,  h, u0, v0, cubeEastNormalX, cubeEastNormalY, cubeEastNormalZ, packedLight);
        } else {
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 0, -h, u0, v1, cubeWestNormalX, cubeWestNormalY, cubeWestNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 3, -h, u1, v1, cubeWestNormalX, cubeWestNormalY, cubeWestNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 3,  h, u1, v0, cubeWestNormalX, cubeWestNormalY, cubeWestNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 0,  h, u0, v0, cubeWestNormalX, cubeWestNormalY, cubeWestNormalZ, packedLight);
        }

        // Y rotation does not affect the top/bottom normals, so this pair only
        // needs a single comparison against the cube's current world Y.
        if (cameraDeltaY >= 0.0) {
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 3, h, u0, v1, cubeUpNormalX, cubeUpNormalY, cubeUpNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 2, h, u1, v1, cubeUpNormalX, cubeUpNormalY, cubeUpNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 1, h, u1, v0, cubeUpNormalX, cubeUpNormalY, cubeUpNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 0, h, u0, v0, cubeUpNormalX, cubeUpNormalY, cubeUpNormalZ, packedLight);
        } else {
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 0, -h, u0, v1, cubeDownNormalX, cubeDownNormalY, cubeDownNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 1, -h, u1, v1, cubeDownNormalX, cubeDownNormalY, cubeDownNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 2, -h, u1, v0, cubeDownNormalX, cubeDownNormalY, cubeDownNormalZ, packedLight);
            cubeVertex(consumer, basePose, centerX, centerY, centerZ, 3, -h, u0, v0, cubeDownNormalX, cubeDownNormalY, cubeDownNormalZ, packedLight);
        }
    }

    private void cubeVertex(
            VertexConsumer consumer,
            PoseStack.Pose basePose,
            float centerX,
            float centerY,
            float centerZ,
            int horizontalCorner,
            float yOffset,
            float u,
            float v,
            float normalX,
            float normalY,
            float normalZ,
            int packedLight
    ) {
        consumer.addVertex(
                        basePose,
                        centerX + rotatingCubeGeometry.cornerX[horizontalCorner],
                        centerY + yOffset,
                        centerZ + rotatingCubeGeometry.cornerZ[horizontalCorner]
                )
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(normalX, normalY, normalZ);
    }


    private void ensureTransformedBlockNormals(PoseStack.Pose basePose) {
        if (blockNormalsPrepared) return;
        blockNormalsPrepared = true;
        transformBlockNormal(basePose, blockBillboardGeometry.normalX, blockBillboardGeometry.normalY, blockBillboardGeometry.normalZ, 0);
        transformBlockNormal(basePose, rotatingCubeGeometry.southNormalX, 0.0F, rotatingCubeGeometry.southNormalZ, 1);
        transformBlockNormal(basePose, rotatingCubeGeometry.northNormalX, 0.0F, rotatingCubeGeometry.northNormalZ, 2);
        transformBlockNormal(basePose, rotatingCubeGeometry.eastNormalX, 0.0F, rotatingCubeGeometry.eastNormalZ, 3);
        transformBlockNormal(basePose, rotatingCubeGeometry.westNormalX, 0.0F, rotatingCubeGeometry.westNormalZ, 4);
        transformBlockNormal(basePose, 0.0F, 1.0F, 0.0F, 5);
        transformBlockNormal(basePose, 0.0F, -1.0F, 0.0F, 6);
    }

    private void transformBlockNormal(PoseStack.Pose basePose, float x, float y, float z, int normalIndex) {
        basePose.transformNormal(x, y, z, normalScratch);
        float nx = normalScratch.x;
        float ny = normalScratch.y;
        float nz = normalScratch.z;
        switch (normalIndex) {
            case 0 -> { blockBillboardNormalX = nx; blockBillboardNormalY = ny; blockBillboardNormalZ = nz; }
            case 1 -> { cubeSouthNormalX = nx; cubeSouthNormalY = ny; cubeSouthNormalZ = nz; }
            case 2 -> { cubeNorthNormalX = nx; cubeNorthNormalY = ny; cubeNorthNormalZ = nz; }
            case 3 -> { cubeEastNormalX = nx; cubeEastNormalY = ny; cubeEastNormalZ = nz; }
            case 4 -> { cubeWestNormalX = nx; cubeWestNormalY = ny; cubeWestNormalZ = nz; }
            case 5 -> { cubeUpNormalX = nx; cubeUpNormalY = ny; cubeUpNormalZ = nz; }
            default -> { cubeDownNormalX = nx; cubeDownNormalY = ny; cubeDownNormalZ = nz; }
        }
    }

    private void prepareSharedFrameState(long gameTime, double renderGameTime) {
        if (lastVisualConfigGameTime != gameTime) {
            refreshVisualConfig();
            lastVisualConfigGameTime = gameTime;
        }

        long renderTimeBits = Double.doubleToLongBits(renderGameTime);
        if (lastPreparedRenderTimeBits == renderTimeBits) return;
        lastPreparedRenderTimeBits = renderTimeBits;

        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var cameraPosition = camera.getPosition();
        frameCameraWorldX = cameraPosition.x;
        frameCameraWorldY = cameraPosition.y;
        frameCameraWorldZ = cameraPosition.z;

        Minecraft minecraft = Minecraft.getInstance();
        frameFrustum = minecraft.levelRenderer.getFrustum();

        var cameraLeft = camera.getLeftVector();
        var cameraUp = camera.getUpVector();
        var cameraLook = camera.getLookVector();
        float blockAngle = (float) (renderGameTime * BLOCK_ROTATION_RADIANS_PER_TICK);
        float blockCos = Mth.cos(blockAngle);
        float blockSin = Mth.sin(blockAngle);
        rotatingCubeGeometry.update(blockCos, blockSin);
        blockBillboardGeometry.update(cameraLeft, cameraUp, cameraLook, blockCos, blockSin);
        updateBlockBillboardTaperBands();

        float itemAngle = (float) (renderGameTime * ITEM_ROTATION_RADIANS_PER_TICK);
        itemBillboardGeometry.update(
                cameraLeft, cameraUp, cameraLook, Mth.cos(itemAngle), Mth.sin(itemAngle)
        );
    }

    private void updateBlockBillboardTaperBands() {
        for (int band = 0; band < BLOCK_BILLBOARD_TAPER_BAND_COUNT; band++) {
            float scale = BLOCK_BILLBOARD_TAPER_SCALES[band];
            float[] targetX = blockBillboardBandCornerX[band];
            float[] targetY = blockBillboardBandCornerY[band];
            float[] targetZ = blockBillboardBandCornerZ[band];
            for (int corner = 0; corner < 4; corner++) {
                targetX[corner] = blockBillboardGeometry.cornerX[corner] * scale;
                targetY[corner] = blockBillboardGeometry.cornerY[corner] * scale;
                targetZ[corner] = blockBillboardGeometry.cornerZ[corner] * scale;
            }
        }
    }

    private void refreshVisualConfig() {
        float blockHalfSize = LaserExcavatorClientConfig.BLOCK_VISUAL_HALF_SIZE.get().floatValue();
        float itemHalfSize = LaserExcavatorClientConfig.ITEM_VISUAL_HALF_SIZE.get().floatValue();

        if (blockHalfSize != cachedBlockHalfSize) {
            cachedBlockHalfSize = blockHalfSize;
            rotatingCubeGeometry = new ExcavatorTransportGeometry.RotatingCube(blockHalfSize);
            blockBillboardGeometry = new ExcavatorTransportGeometry.RotatingBillboard(blockHalfSize);
        }
        if (itemHalfSize != cachedItemHalfSize) {
            cachedItemHalfSize = itemHalfSize;
            itemBillboardGeometry = new ExcavatorTransportGeometry.RotatingBillboard(itemHalfSize);
        }
        float billboardDistance = LaserExcavatorClientConfig.BILLBOARD_DISTANCE.get().floatValue();

        // Marker thresholds select the individual representation; region LOD controls
        // the switch to batching and hidden transport visuals.
        float largeMarkerDistance = Math.max(
                billboardDistance,
                LaserExcavatorClientConfig.TRANSPORT_LARGE_MARKER_DISTANCE.get().floatValue()
        );
        float smallMarkerDistance = Math.max(
                largeMarkerDistance,
                LaserExcavatorClientConfig.TRANSPORT_SMALL_MARKER_DISTANCE.get().floatValue()
        );
        float configuredBatchDistance = Math.max(
                smallMarkerDistance,
                LaserExcavatorClientConfig.TRANSPORT_BATCH_DISTANCE.get().floatValue()
        );
        float configuredTransportMaxRenderDistance = Math.max(
                configuredBatchDistance,
                LaserExcavatorClientConfig.TRANSPORT_MAX_RENDER_DISTANCE.get().floatValue()
        );

        billboardDistanceSq = billboardDistance * billboardDistance;

        // Start shrinking halfway through the block-billboard distance range.
        // The interpolation itself uses squared distance, avoiding sqrt in the hot path.
        float blockBillboardShrinkStart = billboardDistance + (largeMarkerDistance - billboardDistance) * 0.50F;
        blockBillboardShrinkStartSq = blockBillboardShrinkStart * blockBillboardShrinkStart;
        float blockBillboardShrinkRangeSq = Math.max(0.0001F, largeMarkerDistance * largeMarkerDistance - blockBillboardShrinkStartSq);
        blockBillboardShrinkInvRangeSq = 1.0F / blockBillboardShrinkRangeSq;

        largeMarkerDistanceSq = largeMarkerDistance * largeMarkerDistance;
        smallMarkerDistanceSq = smallMarkerDistance * smallMarkerDistance;
        batchDistance = configuredBatchDistance;
        batchDistanceSq = batchDistance * batchDistance;
        maxBatchedMarkers = Math.max(1, LaserExcavatorClientConfig.TRANSPORT_BATCH_MAX_MARKERS.get());
        minBatchedMarkers = Mth.clamp(
                LaserExcavatorClientConfig.TRANSPORT_BATCH_MIN_MARKERS.get(), 1, maxBatchedMarkers
        );
        batchFalloffExponent = Math.max(
                0.01F,
                LaserExcavatorClientConfig.TRANSPORT_BATCH_FALLOFF_EXPONENT.get().floatValue()
        );
        transportMaxRenderDistance = configuredTransportMaxRenderDistance;
        transportMaxRenderDistanceSq = transportMaxRenderDistance * transportMaxRenderDistance;
    }

    /** Cached trajectory evaluator shared by spatial membership and render paths. */
    private void calculateCachedTransportPosition(CachedTransport cached, double renderGameTime) {
        long renderTimeBits = Double.doubleToLongBits(renderGameTime);
        if (cached.framePositionRenderTimeBits == renderTimeBits) {
            transportPosition.x = cached.frameX;
            transportPosition.y = cached.frameY;
            transportPosition.z = cached.frameZ;
            return;
        }

        double animationRenderTime = clampCachedAnimationRenderTime(cached, renderGameTime);

        float x;
        float y;
        float z;
        if (animationRenderTime <= cached.startGameTime) {
            x = cached.sourceX;
            y = cached.sourceY;
            z = cached.sourceZ;
        } else {
            byte phase = animationPhaseForRenderTime(cached, animationRenderTime);
            if (phase == TRANSPORT_PHASE_LIFT) {
                float e = (float) (animationRenderTime - cached.startGameTime);
                float e2 = e * e;
                x = cached.sourceX;
                y = cached.sourceY + e2 * (cached.liftCurve2 + e * cached.liftCurve3);
                z = cached.sourceZ;
            } else if (phase == TRANSPORT_PHASE_FIELD) {
                float e = (float) (animationRenderTime - cached.liftEndGameTime);
                float e2 = e * e;
                x = cached.sourceX + e2 * (cached.fieldXCurve2 + e * cached.fieldXCurve3);
                y = cached.fieldY;
                z = cached.sourceZ + e2 * (cached.fieldZCurve2 + e * cached.fieldZCurve3);
            } else if (animationRenderTime >= cached.endGameTime) {
                x = 0.5F;
                y = 0.85F;
                z = 0.5F;
            } else {
                float e = (float) (animationRenderTime - cached.fieldEndGameTime);
                float e2 = e * e;
                x = 0.5F;
                y = cached.fieldY + e2 * (cached.descentCurve2 + e * cached.descentCurve3);
                z = 0.5F;
            }
        }

        cached.framePositionRenderTimeBits = renderTimeBits;
        cached.frameX = x;
        cached.frameY = y;
        cached.frameZ = z;
        transportPosition.x = x;
        transportPosition.y = y;
        transportPosition.z = z;
    }

    private double clampCachedAnimationRenderTime(CachedTransport cached, double rawRenderGameTime) {
        double animationTime = rawRenderGameTime;
        if (animationTime < cached.lastAnimationRenderTime) {
            animationTime = cached.lastAnimationRenderTime;
            if (ExcavatorProfiler.isEnabled()) {
                ExcavatorProfiler.increment(
                        ExcavatorProfiler.Counter.TRANSPORT_ANIMATION_BACKWARD_TIME_CLAMPS
                );
            }
        }
        cached.lastAnimationRenderTime = animationTime;
        return animationTime;
    }

    private static byte animationPhaseForRenderTime(CachedTransport cached, double renderGameTime) {
        byte phase = cached.animationPhase;
        if (phase == TRANSPORT_PHASE_LIFT && renderGameTime >= cached.liftEndGameTime) {
            phase = renderGameTime < cached.fieldEndGameTime
                    ? TRANSPORT_PHASE_FIELD
                    : TRANSPORT_PHASE_DESCENT;
            cached.animationPhase = phase;
        } else if (phase == TRANSPORT_PHASE_FIELD && renderGameTime >= cached.fieldEndGameTime) {
            phase = TRANSPORT_PHASE_DESCENT;
            cached.animationPhase = phase;
        }
        return phase;
    }




}
