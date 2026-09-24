package de.balto.laserexcavator.block.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorArea;
import de.balto.laserexcavator.block.excavator.ExcavatorBlock;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import de.balto.laserexcavator.client.renderer.ExcavatorClientVisuals;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.screen.ExcavatorMenu;
import de.balto.laserexcavator.screen.ExcavatorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;

public class ExcavatorForceFieldRenderer implements BlockEntityRenderer<ExcavatorBlockEntity> {
    private static WeakReference<ExcavatorForceFieldRenderer> activeRenderer = new WeakReference<>(null);

    /**
     * Shared GPU-oriented line path for every force-field and laser segment.
     * All geometry is opaque, unblended and depth-writing so shader packs can
     * reject hidden fragments early without needing a separate glow pass.
     */
    private static final RenderType OPAQUE_DEPTH_LINES = RenderType.create(
            "laser_excavator_opaque_depth_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            8_192,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderType.RENDERTYPE_LINES_SHADER)
                    .setTransparencyState(RenderType.NO_TRANSPARENCY)
                    .setCullState(RenderType.NO_CULL)
                    .setWriteMaskState(RenderType.COLOR_DEPTH_WRITE)
                    .setLightmapState(RenderType.NO_LIGHTMAP)
                    .createCompositeState(false)
    );

    private static final float MEDIUM_LASER_SIDE_OFFSET = 0.056F;
    private static final float LASER_PULSE_ANGULAR_SCALE = Mth.PI * 4.0F;
    private static final double FORCE_FIELD_FULL_GEOMETRY_DISTANCE_SQR = 80.0D * 80.0D;
    private static final double FORCE_FIELD_MID_GEOMETRY_DISTANCE_SQR = 160.0D * 160.0D;

    // The outer cull uses the exact axis-aligned hull of every possible
    // excavator visual: excavation region + force-field height + excavator block.
    // Because every laser/transport segment lies between points already inside this
    // convex AABB, only a small safety margin is needed for beam/pillar/item size.
    private static final double WHOLE_EFFECT_HULL_PADDING = 4.0D;
    // Off-screen transport caches are derived data. Discard them on a staggered
    // ~32-tick cadence so long-hidden excavators release cached ItemStacks/SoA state
    // without paying a WeakHashMap removal for every culled excavator every frame.
    private static final long OFFSCREEN_TRANSPORT_CACHE_CLEANUP_MASK = 31L;

    private enum ForceFieldLod {
        FULL,
        MID,
        FAR
    }

    private final ExcavatorTransportRenderer transportRenderer = new ExcavatorTransportRenderer();

    // Shared by every excavator rendered during the same frame. The force-field
    // animation is global, so recalculating sin/config/camera state for every
    // block entity only wastes CPU when many excavators are visible.
    private long preparedForceFieldGameTime = Long.MIN_VALUE;
    private int preparedForceFieldPartialBits = Integer.MIN_VALUE;
    private Vec3 preparedCameraPos = Vec3.ZERO;
    private float preparedSweepAnimation;
    private float preparedPillarOuterRadius;
    private float preparedPillarCoreRadius;
    private float preparedPillarNodeRadius;
    private int preparedPillarOuterGreen;
    private int preparedGridSpacing = 1;
    private double preparedMidLaserDistanceSqr;
    private double preparedFarLaserDistanceSqr;

    public ExcavatorForceFieldRenderer(BlockEntityRendererProvider.Context context) {
        activeRenderer = new WeakReference<>(this);
    }

    public static void clearCachedTransportStates() {
        ExcavatorForceFieldRenderer renderer = activeRenderer.get();
        if (renderer != null) renderer.transportRenderer.clearAllCachedStates();
    }

    @Override
    public void render(
            ExcavatorBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        // Diagnostic hard stop: skip the complete excavator block-entity render path before frustum tests,
        // visual cache lookups, force-field/laser work or transport processing.
        if (LaserExcavatorClientConfig.isAllRenderingDisabled()) return;

        Level level = blockEntity.getLevel();
        if (!(level instanceof ClientLevel clientLevel)) return;

        boolean profiling = ExcavatorProfiler.isEnabled();
        if (profiling) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.EXCAVATOR_FRUSTUM_TESTS);
        }
        if (isWholeEffectOutsideFrustum(blockEntity)) {
            // VisualSet remains authoritative while the derived transport render state may be
            // discarded off-screen. Cache removals are staggered to avoid bursts on camera turns.
            if (shouldClearOffscreenTransportCache(blockEntity.getBlockPos(), clientLevel.getGameTime())) {
                transportRenderer.clearCachedState(blockEntity);
            }
            if (profiling) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.EXCAVATOR_FRUSTUM_CULLED);
            }
            return;
        }

        if (profiling) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.RENDER_CALLS);
        }

        BlockPos origin = blockEntity.getBlockPos();
        ExcavatorMenu previewMenu = findOpenExcavatorMenu(blockEntity);
        ExcavatorScanState state = previewMenu != null ? previewMenu.getScanState() : blockEntity.getScanState();
        ExcavatorArea displayArea = resolveRenderArea(blockEntity, previewMenu);
        if (state == ExcavatorScanState.IDLE || state == ExcavatorScanState.SCANNING) {
            ExcavatorClientVisuals.clear(clientLevel, origin);
        }

        ExcavatorClientVisuals.VisualSet visuals = ExcavatorClientVisuals.get(clientLevel, origin);

        boolean energizedField = state == ExcavatorScanState.READY
                || state == ExcavatorScanState.EXCAVATING
                || state == ExcavatorScanState.STORAGE_FULL
                || blockEntity.hasPendingDeliveries()
                || visuals.hasAny();

        long fieldProfile = ExcavatorProfiler.begin(profiling, ExcavatorProfiler.Section.FORCE_FIELD_RENDER);
        renderForceField(
                blockEntity,
                level,
                partialTick,
                poseStack,
                bufferSource,
                displayArea,
                state,
                energizedField,
                profiling
        );
        ExcavatorProfiler.end(ExcavatorProfiler.Section.FORCE_FIELD_RENDER, fieldProfile);

        if (!visuals.lasers().isEmpty()) {
            long laserProfile = ExcavatorProfiler.begin(profiling, ExcavatorProfiler.Section.LASER_RENDER);

            // Shared laser state is calculated once per block-entity render pass instead of once
            // per active laser. All laser geometry uses the same line buffer and
            // base pose, and target coordinates are already excavator-local.
            double laserRenderGameTime = level.getGameTime() + partialTick;
            float laserTopY = blockEntity.getForceFieldY() - origin.getY();
            PoseStack.Pose laserPose = poseStack.last();
            VertexConsumer opaqueLaserLines = bufferSource.getBuffer(OPAQUE_DEPTH_LINES);
            Vec3 cameraPos = preparedCameraPos;
            int fullLaserCount = 0;
            int midLaserCount = 0;
            int farLaserCount = 0;

            // The excavation midpoint provides a shared distant-LOD decision before
            // individual laser distance calculations are needed.
            double laserCenterX = (displayArea.min().getX() + displayArea.max().getX() + 1.0D) * 0.5D;
            double laserCenterZ = (displayArea.min().getZ() + displayArea.max().getZ() + 1.0D) * 0.5D;
            double centerDx = laserCenterX - cameraPos.x;
            double centerDz = laserCenterZ - cameraPos.z;
            boolean wholeExcavatorFar = centerDx * centerDx + centerDz * centerDz
                    >= preparedFarLaserDistanceSqr;

            int midLaserSkippedCount = 0;
            int farLaserSkippedCount = 0;

            if (profiling) {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LASER_CENTER_FAR_FAST_PATH_TESTS);
                if (wholeExcavatorFar) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LASER_CENTER_FAR_FAST_PATH_HITS);
                }
            }

            if (wholeExcavatorFar) {
                for (ExcavatorClientVisuals.LaserVisual laser : visuals.lasers()) {
                    // FAR only keeps half of the source lasers. Selection is based
                    // on stable laser data, so survivors do not flicker as other
                    // visuals are added or expire.
                    if (!shouldRenderFarLaser(laser)) {
                        farLaserSkippedCount++;
                        continue;
                    }
                    renderFarDistanceLaser(laser, laserTopY, laserPose, opaqueLaserLines);
                    farLaserCount++;
                }
            } else {
                for (ExcavatorClientVisuals.LaserVisual laser : visuals.lasers()) {
                    double worldX = origin.getX() + laser.localX();
                    double worldZ = origin.getZ() + laser.localZ();
                    double worldMidY = origin.getY() + (laser.localBottomY() + laserTopY) * 0.5D;
                    double dx = worldX - cameraPos.x;
                    double dy = worldMidY - cameraPos.y;
                    double dz = worldZ - cameraPos.z;
                    double distanceSqr = dx * dx + dy * dy + dz * dz;

                    if (distanceSqr >= preparedFarLaserDistanceSqr) {
                        if (!shouldRenderFarLaser(laser)) {
                            farLaserSkippedCount++;
                            continue;
                        }
                        renderFarDistanceLaser(laser, laserTopY, laserPose, opaqueLaserLines);
                        farLaserCount++;
                    } else if (distanceSqr >= preparedMidLaserDistanceSqr) {
                        // MID keeps two thirds of source lasers.
                        if (!shouldRenderMidLaser(laser)) {
                            midLaserSkippedCount++;
                            continue;
                        }
                        renderMidDistanceLaser(
                                laser, laserRenderGameTime, laserTopY, origin, cameraPos, laserPose,
                                opaqueLaserLines
                        );
                        midLaserCount++;
                    } else {
                        renderLaser(
                                laser, laserRenderGameTime, laserTopY, laserPose, opaqueLaserLines
                        );
                        fullLaserCount++;
                    }
                }
            }
            if (profiling) {
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.LASERS_FULL_RENDERED, fullLaserCount);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.LASERS_MID_RENDERED, midLaserCount);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.LASERS_FAR_RENDERED, farLaserCount);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.LASERS_MID_DENSITY_SKIPPED, midLaserSkippedCount);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.LASERS_FAR_DENSITY_SKIPPED, farLaserSkippedCount);
            }
            ExcavatorProfiler.end(ExcavatorProfiler.Section.LASER_RENDER, laserProfile);
        }

        transportRenderer.render(
                visuals, clientLevel, blockEntity, partialTick, poseStack, bufferSource
        );
    }

    private void renderForceField(
            ExcavatorBlockEntity blockEntity,
            Level level,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ExcavatorArea area,
            ExcavatorScanState state,
            boolean energized,
            boolean profiling
    ) {
        BlockPos origin = blockEntity.getBlockPos();

        float minX = area.min().getX() - origin.getX();
        float maxX = area.max().getX() + 1.0F - origin.getX();
        float minZ = area.min().getZ() - origin.getZ();
        float maxZ = area.max().getZ() + 1.0F - origin.getZ();
        float fieldY = blockEntity.getForceFieldY() - origin.getY();
        float bottomY = area.min().getY() - origin.getY();

        VertexConsumer opaqueLines = bufferSource.getBuffer(OPAQUE_DEPTH_LINES);
        PoseStack.Pose pose = poseStack.last();

        int forceFieldColor = forceFieldColor(state);
        int outlineRed = (forceFieldColor >>> 16) & 0xFF;
        int outlineGreen = (forceFieldColor >>> 8) & 0xFF;
        int outlineBlue = forceFieldColor & 0xFF;

        // The force-field silhouette also shows the selected excavation bounds and
        // changes color with the machine state.
        lineX(pose, opaqueLines, minX, maxX, fieldY, minZ, outlineRed, outlineGreen, outlineBlue, 255);
        lineX(pose, opaqueLines, minX, maxX, fieldY, maxZ, outlineRed, outlineGreen, outlineBlue, 255);
        lineZ(pose, opaqueLines, minX, fieldY, minZ, maxZ, outlineRed, outlineGreen, outlineBlue, 255);
        lineZ(pose, opaqueLines, maxX, fieldY, minZ, maxZ, outlineRed, outlineGreen, outlineBlue, 255);

        // Before activation the four corner edges make the selected excavation
        // depth readable without drawing a second, separate selection box.
        if (!energized) {
            lineY(pose, opaqueLines, minX, bottomY, fieldY, minZ, outlineRed, outlineGreen, outlineBlue, 255);
            lineY(pose, opaqueLines, maxX, bottomY, fieldY, minZ, outlineRed, outlineGreen, outlineBlue, 255);
            lineY(pose, opaqueLines, minX, bottomY, fieldY, maxZ, outlineRed, outlineGreen, outlineBlue, 255);
            lineY(pose, opaqueLines, maxX, bottomY, fieldY, maxZ, outlineRed, outlineGreen, outlineBlue, 255);
            return;
        }

        prepareForceFieldFrame(level, partialTick);

        double fieldDistanceSqr = distanceToForceFieldSqr(area, blockEntity.getForceFieldY());
        ForceFieldLod lod = selectForceFieldLod(fieldDistanceSqr);
        if (profiling) {
            switch (lod) {
                case FULL -> ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FORCE_FIELDS_FULL_GEOMETRY);
                case MID -> ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FORCE_FIELDS_MID_GEOMETRY);
                case FAR -> ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FORCE_FIELDS_FAR_GEOMETRY);
            }
        }

        // Grid lines remain visible at every LOD; only their density changes with distance.
        int gridSpacing = switch (lod) {
            case FULL -> preparedGridSpacing;
            case MID -> Math.max(1, preparedGridSpacing * 2);
            case FAR -> Math.max(1, preparedGridSpacing * 4);
        };
        int minWorldX = area.min().getX();
        int maxWorldXExclusive = area.max().getX() + 1;
        int minWorldZ = area.min().getZ();
        int maxWorldZExclusive = area.max().getZ() + 1;

        int gridRed = switch (lod) {
            case FULL -> 68;
            case MID -> 52;
            case FAR -> 42;
        };
        int gridGreen = switch (lod) {
            case FULL -> 188;
            case MID -> 154;
            case FAR -> 132;
        };
        int gridBlue = switch (lod) {
            case FULL -> 232;
            case MID -> 201;
            case FAR -> 176;
        };

        for (int worldX = minWorldX + gridSpacing; worldX < maxWorldXExclusive; worldX += gridSpacing) {
            float x = worldX - origin.getX();
            lineZ(pose, opaqueLines, x, fieldY, minZ, maxZ, gridRed, gridGreen, gridBlue, 255);
        }
        for (int worldZ = minWorldZ + gridSpacing; worldZ < maxWorldZExclusive; worldZ += gridSpacing) {
            float z = worldZ - origin.getZ();
            lineX(pose, opaqueLines, minX, maxX, fieldY, z, gridRed, gridGreen, gridBlue, 255);
        }

        renderSweep(blockEntity, pose, opaqueLines, minX, maxX, minZ, maxZ, fieldY, lod);
        renderCornerPillars(pose, opaqueLines, minX, maxX, minZ, maxZ, bottomY, fieldY, lod);
        renderEmitterBeam(pose, opaqueLines, fieldY, lod);
    }

    private static ExcavatorMenu findOpenExcavatorMenu(ExcavatorBlockEntity blockEntity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof ExcavatorScreen screen)) return null;

        ExcavatorMenu menu = screen.getExcavatorMenu();
        return menu.getExcavatorPos().equals(blockEntity.getBlockPos()) ? menu : null;
    }

    private static ExcavatorArea resolveRenderArea(ExcavatorBlockEntity blockEntity, ExcavatorMenu previewMenu) {
        if (previewMenu == null) return blockEntity.getExcavatorArea();

        int width = previewMenu.getSelectionWidth();
        int height = previewMenu.getSelectionHeight();
        int length = previewMenu.getSelectionLength();
        if (width == blockEntity.getSelectionWidth()
                && height == blockEntity.getSelectionHeight()
                && length == blockEntity.getSelectionLength()) {
            return blockEntity.getExcavatorArea();
        }

        BlockState blockState = blockEntity.getBlockState();
        Direction facing = blockState.hasProperty(ExcavatorBlock.FACING)
                ? blockState.getValue(ExcavatorBlock.FACING)
                : Direction.NORTH;
        return ExcavatorArea.create(blockEntity.getBlockPos(), facing, width, height, length);
    }

    private static int forceFieldColor(ExcavatorScanState state) {
        return switch (state) {
            case IDLE -> 0x40F2FF;
            case SCANNING -> 0xFFB840;
            case READY, COMPLETE -> 0x59FF80;
            case EXCAVATING -> 0x33D9FF;
            case STORAGE_FULL -> 0xFF4D40;
        };
    }

    private void prepareForceFieldFrame(Level level, float partialTick) {
        long gameTime = level.getGameTime();
        int partialBits = Float.floatToRawIntBits(partialTick);
        if (preparedForceFieldGameTime == gameTime && preparedForceFieldPartialBits == partialBits) {
            return;
        }

        preparedForceFieldGameTime = gameTime;
        preparedForceFieldPartialBits = partialBits;
        preparedCameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        float renderTime = gameTime + partialTick;
        preparedSweepAnimation = (renderTime % 80.0F) / 80.0F;
        float pulse = 0.5F + 0.5F * Mth.sin(renderTime * 0.12F);
        preparedPillarOuterRadius = LaserExcavatorClientConfig.PILLAR_OUTER_RADIUS.get().floatValue() + 0.025F * pulse;
        preparedPillarCoreRadius = LaserExcavatorClientConfig.PILLAR_CORE_RADIUS.get().floatValue() + 0.010F * pulse;
        preparedPillarNodeRadius = 0.16F + 0.035F * pulse;
        preparedPillarOuterGreen = Mth.clamp((int) ((0.65F + 0.20F * pulse) * 255.0F), 0, 255);
        preparedGridSpacing = Math.max(1, LaserExcavatorClientConfig.FORCE_FIELD_GRID_SPACING.get());

        double midLaserDistance = LaserExcavatorClientConfig.LASER_MID_LOD_DISTANCE.get();
        double farLaserDistance = Math.max(
                midLaserDistance,
                LaserExcavatorClientConfig.LASER_FAR_LOD_DISTANCE.get()
        );
        preparedMidLaserDistanceSqr = midLaserDistance * midLaserDistance;
        preparedFarLaserDistanceSqr = farLaserDistance * farLaserDistance;
    }

    private static ForceFieldLod selectForceFieldLod(double distanceSqr) {
        if (distanceSqr < FORCE_FIELD_FULL_GEOMETRY_DISTANCE_SQR) return ForceFieldLod.FULL;
        if (distanceSqr < FORCE_FIELD_MID_GEOMETRY_DISTANCE_SQR) return ForceFieldLod.MID;
        return ForceFieldLod.FAR;
    }

    private double distanceToForceFieldSqr(ExcavatorArea area, int forceFieldY) {
        // Distance to the closest point of the excavation volume rather than
        // to its center. A player standing inside/next to a large field always
        // receives full geometry even when its center is far away.
        double nearestX = Mth.clamp(preparedCameraPos.x, area.min().getX(), area.max().getX() + 1.0D);
        double nearestY = Mth.clamp(
                preparedCameraPos.y,
                Math.min(area.min().getY(), forceFieldY),
                Math.max(area.min().getY(), forceFieldY)
        );
        double nearestZ = Mth.clamp(preparedCameraPos.z, area.min().getZ(), area.max().getZ() + 1.0D);
        double dx = preparedCameraPos.x - nearestX;
        double dy = preparedCameraPos.y - nearestY;
        double dz = preparedCameraPos.z - nearestZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void renderSweep(
            ExcavatorBlockEntity blockEntity,
            PoseStack.Pose pose,
            VertexConsumer lines,
            float minX,
            float maxX,
            float minZ,
            float maxZ,
            float fieldY,
            ForceFieldLod lod
    ) {
        BlockState state = blockEntity.getBlockState();
        Direction facing = state.hasProperty(ExcavatorBlock.FACING)
                ? state.getValue(ExcavatorBlock.FACING)
                : Direction.NORTH;

        final float sweepHalfWidth = 0.035F;
        if (facing.getAxis() == Direction.Axis.Z) {
            float z = minZ + (maxZ - minZ) * preparedSweepAnimation;
            lineX(pose, lines, minX, maxX, fieldY, z, 194, 242, 248, 255);
            if (lod == ForceFieldLod.FULL) {
                lineX(pose, lines, minX, maxX, fieldY, z - sweepHalfWidth, 112, 224, 250, 255);
                lineX(pose, lines, minX, maxX, fieldY, z + sweepHalfWidth, 112, 224, 250, 255);
                return;
            }
        } else {
            float x = minX + (maxX - minX) * preparedSweepAnimation;
            lineZ(pose, lines, x, fieldY, minZ, maxZ, 194, 242, 248, 255);
            if (lod == ForceFieldLod.FULL) {
                lineZ(pose, lines, x - sweepHalfWidth, fieldY, minZ, maxZ, 112, 224, 250, 255);
                lineZ(pose, lines, x + sweepHalfWidth, fieldY, minZ, maxZ, 112, 224, 250, 255);
                return;
            }
        }
    }

    private void renderCornerPillars(
            PoseStack.Pose pose,
            VertexConsumer lines,
            float minX,
            float maxX,
            float minZ,
            float maxZ,
            float bottomY,
            float fieldY,
            ForceFieldLod lod
    ) {
        renderCornerPillar(pose, lines, minX, minZ, bottomY, fieldY, lod);
        renderCornerPillar(pose, lines, maxX, minZ, bottomY, fieldY, lod);
        renderCornerPillar(pose, lines, minX, maxZ, bottomY, fieldY, lod);
        renderCornerPillar(pose, lines, maxX, maxZ, bottomY, fieldY, lod);
    }

    private void renderCornerPillar(
            PoseStack.Pose pose,
            VertexConsumer lines,
            float x,
            float z,
            float bottomY,
            float fieldY,
            ForceFieldLod lod
    ) {
        if (lod == ForceFieldLod.FAR) {
            lineY(pose, lines, x, bottomY, fieldY, z, 220, 255, 255, 255);
            lineY(
                    pose, lines,
                    x + preparedPillarOuterRadius,
                    bottomY, fieldY,
                    z + preparedPillarOuterRadius,
                    64, 181, 204, 255
            );
            return;
        }

        float outerRadius = preparedPillarOuterRadius;
        int outerGreen = Math.max(188, preparedPillarOuterGreen);
        lineY(pose, lines, x - outerRadius, bottomY, fieldY, z - outerRadius, 72, outerGreen, 255, 255);
        lineY(pose, lines, x + outerRadius, bottomY, fieldY, z - outerRadius, 72, outerGreen, 255, 255);
        lineY(pose, lines, x + outerRadius, bottomY, fieldY, z + outerRadius, 72, outerGreen, 255, 255);
        lineY(pose, lines, x - outerRadius, bottomY, fieldY, z + outerRadius, 72, outerGreen, 255, 255);

        if (lod == ForceFieldLod.MID) {
            lineY(pose, lines, x, bottomY, fieldY, z, 220, 255, 255, 255);
            renderPillarNode(pose, lines, x, fieldY, z, preparedPillarNodeRadius, 198, 246, 250, 255);
            return;
        }

        float coreRadius = preparedPillarCoreRadius;
        lineY(pose, lines, x - coreRadius, bottomY, fieldY, z - coreRadius, 220, 255, 255, 255);
        lineY(pose, lines, x + coreRadius, bottomY, fieldY, z - coreRadius, 220, 255, 255, 255);
        lineY(pose, lines, x + coreRadius, bottomY, fieldY, z + coreRadius, 220, 255, 255, 255);
        lineY(pose, lines, x - coreRadius, bottomY, fieldY, z + coreRadius, 220, 255, 255, 255);

        renderPillarNode(pose, lines, x, bottomY, z, preparedPillarNodeRadius, 198, 248, 255, 255);
        renderPillarNode(pose, lines, x, fieldY, z, preparedPillarNodeRadius, 198, 248, 255, 255);
    }

    private static void renderPillarNode(
            PoseStack.Pose pose,
            VertexConsumer lines,
            float x,
            float y,
            float z,
            float radius,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        lineX(pose, lines, x - radius, x + radius, y, z, red, green, blue, alpha);
        lineY(pose, lines, x, y - radius, y + radius, z, red, green, blue, alpha);
        lineZ(pose, lines, x, y, z - radius, z + radius, red, green, blue, alpha);
    }

    private static void renderEmitterBeam(
            PoseStack.Pose pose,
            VertexConsumer lines,
            float fieldY,
            ForceFieldLod lod
    ) {
        if (lod != ForceFieldLod.FULL) {
            lineY(pose, lines, 0.5F, 1.0F, fieldY, 0.5F, 166, 230, 238, 255);
            return;
        }

        final float min = 0.47F;
        final float max = 0.53F;
        lineY(pose, lines, min, 1.0F, fieldY, min, 134, 240, 255, 255);
        lineY(pose, lines, max, 1.0F, fieldY, min, 134, 240, 255, 255);
        lineY(pose, lines, max, 1.0F, fieldY, max, 134, 240, 255, 255);
        lineY(pose, lines, min, 1.0F, fieldY, max, 134, 240, 255, 255);
    }

    /**
     * Stable per-laser hash used only for distance LOD density thinning. Using
     * source coordinates + start time keeps the decision fixed for the complete
     * lifetime of a laser and avoids frame-to-frame popping as list indices move.
     */
    private static int laserDensityHash(ExcavatorClientVisuals.LaserVisual laser) {
        long h = laser.startGameTime();
        h ^= (long) Float.floatToRawIntBits(laser.localX()) * 0x9E3779B97F4A7C15L;
        h ^= (long) Float.floatToRawIntBits(laser.localZ()) * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) Float.floatToRawIntBits(laser.localBottomY()) * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) h;
    }

    private static boolean shouldRenderMidLaser(ExcavatorClientVisuals.LaserVisual laser) {
        return Math.floorMod(laserDensityHash(laser), 3) != 0;
    }

    private static boolean shouldRenderFarLaser(ExcavatorClientVisuals.LaserVisual laser) {
        return (laserDensityHash(laser) & 1) == 0;
    }

    private void renderLaser(
            ExcavatorClientVisuals.LaserVisual laser,
            double renderGameTime,
            float topY,
            PoseStack.Pose pose,
            VertexConsumer lines
    ) {
        float x = laser.localX();
        float z = laser.localZ();
        float bottomY = laser.localBottomY();

        float progress = Mth.clamp(
                (float) (renderGameTime - laser.startGameTime()) * laser.invDurationTicks(),
                0.0F,
                1.0F
        );

        float pulse = 0.5F + 0.5F * Mth.sin(progress * LASER_PULSE_ANGULAR_SCALE);
        float outerRadius = 0.038F + 0.010F * pulse;

        int outerGreen = Mth.clamp((int) ((0.82F + 0.12F * pulse) * 255.0F), 0, 255);

        // All beam geometry is opaque. Keep the outer cyan lines bright enough
        // to remain readable against the field.
        lineY(pose, lines, x - outerRadius, bottomY, topY, z - outerRadius, 94, outerGreen, 255, 255);
        lineY(pose, lines, x + outerRadius, bottomY, topY, z - outerRadius, 94, outerGreen, 255, 255);
        lineY(pose, lines, x + outerRadius, bottomY, topY, z + outerRadius, 94, outerGreen, 255, 255);
        lineY(pose, lines, x - outerRadius, bottomY, topY, z + outerRadius, 94, outerGreen, 255, 255);

        // A single centered white line is enough for the close core. Besides
        // looking cleaner, this removes three otherwise redundant full-laser lines.
        lineY(pose, lines, x, bottomY - 0.02F, topY + 0.02F, z, 224, 255, 255, 255);


    }

    /**
     * Medium-distance laser LOD: three vertical lines in one camera-facing
     * plane (cyan / core / cyan). The colors intentionally use the exact
     * corona/core palette of the full laser for the current animation frame.
     */
    private static void renderMidDistanceLaser(
            ExcavatorClientVisuals.LaserVisual laser,
            double renderGameTime,
            float topY,
            BlockPos origin,
            Vec3 cameraPos,
            PoseStack.Pose pose,
            VertexConsumer opaqueLines
    ) {
        float x = laser.localX();
        float z = laser.localZ();
        float bottomY = laser.localBottomY();

        double worldX = origin.getX() + x;
        double worldZ = origin.getZ() + z;
        double toLaserX = worldX - cameraPos.x;
        double toLaserZ = worldZ - cameraPos.z;
        double horizontalLength = Math.sqrt(toLaserX * toLaserX + toLaserZ * toLaserZ);

        float rightX;
        float rightZ;
        if (horizontalLength > 1.0E-5D) {
            rightX = (float) (-toLaserZ / horizontalLength);
            rightZ = (float) (toLaserX / horizontalLength);
        } else {
            rightX = 1.0F;
            rightZ = 0.0F;
        }

        float progress = Mth.clamp(
                (float) (renderGameTime - laser.startGameTime()) * laser.invDurationTicks(),
                0.0F,
                1.0F
        );
        float pulse = 0.5F + 0.5F * Mth.sin(progress * LASER_PULSE_ANGULAR_SCALE);
        int coronaGreen = Mth.clamp((int) ((0.75F + 0.15F * pulse) * 255.0F), 0, 255);

        float offsetX = rightX * MEDIUM_LASER_SIDE_OFFSET;
        float offsetZ = rightZ * MEDIUM_LASER_SIDE_OFFSET;
        int opaqueSideGreen = Math.max(178, coronaGreen * 4 / 5);

        lineY(pose, opaqueLines, x - offsetX, bottomY, topY, z - offsetZ, 78, opaqueSideGreen, 228, 255);
        lineY(pose, opaqueLines, x + offsetX, bottomY, topY, z + offsetZ, 78, opaqueSideGreen, 228, 255);
        lineY(pose, opaqueLines, x, bottomY - 0.02F, topY + 0.02F, z, 224, 255, 255, 255);
    }

    /**
     * Ultra-distant laser LOD: a single vertical core line. It uses the exact
     * full-laser core color and avoids camera-facing calculations entirely.
     */
    private static void renderFarDistanceLaser(
            ExcavatorClientVisuals.LaserVisual laser,
            float topY,
            PoseStack.Pose pose,
            VertexConsumer lines
    ) {
        lineY(
                pose, lines,
                laser.localX(),
                laser.localBottomY() - 0.02F,
                topY + 0.02F,
                laser.localZ(),
                224, 255, 255, 255
        );
    }

    private static void lineX(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float minX,
            float maxX,
            float y,
            float z,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        lineVertex(pose, consumer, minX, y, z, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
        lineVertex(pose, consumer, maxX, y, z, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
    }

    private static void lineY(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x,
            float minY,
            float maxY,
            float z,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        lineVertex(pose, consumer, x, minY, z, red, green, blue, alpha, 0.0F, 1.0F, 0.0F);
        lineVertex(pose, consumer, x, maxY, z, red, green, blue, alpha, 0.0F, 1.0F, 0.0F);
    }

    private static void lineZ(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x,
            float y,
            float minZ,
            float maxZ,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        lineVertex(pose, consumer, x, y, minZ, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);
        lineVertex(pose, consumer, x, y, maxZ, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);
    }

    private static void lineVertex(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x,
            float y,
            float z,
            int red,
            int green,
            int blue,
            int alpha,
            float normalX,
            float normalY,
            float normalZ
    ) {
        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, normalX, normalY, normalZ);
    }

    @Override
    public AABB getRenderBoundingBox(ExcavatorBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        ExcavatorArea area = resolveRenderArea(blockEntity, findOpenExcavatorMenu(blockEntity));

        // Exact complete-effect hull. The persistent field outline always reaches the
        // force-field plane, while active laser/transport segments remain inside the
        // same excavation-region + excavator-block hull.
        double minX = Math.min(area.min().getX(), pos.getX());
        double minY = Math.min(area.min().getY(), pos.getY());
        double minZ = Math.min(area.min().getZ(), pos.getZ());
        double maxX = Math.max(area.max().getX() + 1.0D, pos.getX() + 1.0D);
        double maxZ = Math.max(area.max().getZ() + 1.0D, pos.getZ() + 1.0D);

        double maxY = Math.max(pos.getY() + 1.0D, blockEntity.getForceFieldY() + 1.0D);

        return new AABB(
                minX - WHOLE_EFFECT_HULL_PADDING,
                minY - WHOLE_EFFECT_HULL_PADDING,
                minZ - WHOLE_EFFECT_HULL_PADDING,
                maxX + WHOLE_EFFECT_HULL_PADDING,
                maxY + WHOLE_EFFECT_HULL_PADDING,
                maxZ + WHOLE_EFFECT_HULL_PADDING
        );
    }

    /**
     * Custom whole-effect culling keeps vanilla block-entity culling disabled because the
     * dispatcher bounds can produce edge/below-field false negatives for this effect.
     * Instead, reject the renderer only when the exact complete-effect hull (plus a
     * small 4-block safety margin) is wholly outside the current world frustum.
     *
     * No near-camera exemption is needed: when the camera is inside/intersecting this
     * hull the frustum test remains visible, while distant fully off-screen effects
     * can be culled aggressively again.
     */
    private static boolean shouldClearOffscreenTransportCache(BlockPos pos, long gameTime) {
        long key = pos.asLong();
        long mixed = key ^ (key >>> 17) ^ (key >>> 37);
        return ((gameTime + mixed) & OFFSCREEN_TRANSPORT_CACHE_CLEANUP_MASK) == 0L;
    }

    private boolean isWholeEffectOutsideFrustum(ExcavatorBlockEntity blockEntity) {
        Frustum frustum = Minecraft.getInstance().levelRenderer.getFrustum();
        return !frustum.isVisible(getRenderBoundingBox(blockEntity));
    }

    @Override
    public boolean shouldRenderOffScreen(ExcavatorBlockEntity blockEntity) {
        // Keep the dispatcher permissive; render() performs the exact complete-effect
        // hull test itself so vanilla block-entity culling cannot create edge/below-field false negatives.
        return true;
    }

    @Override
    public int getViewDistance() {
        return LaserExcavatorClientConfig.RENDER_DISTANCE.get();
    }
}
