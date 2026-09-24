package de.balto.laserexcavator.block.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.client.renderer.ExcavatorClientVisuals;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-only synthetic transport benchmark.
 *
 * No server blocks, block entities, excavation, networking or chunk updates are
 * involved. Synthetic visuals are inserted through
 * ExcavatorClientVisuals.addSyntheticTransport(), then rendered by the exact
 * production ExcavatorTransportRenderer. The benchmark may optionally override the production spatial-region LOD tier.
 */
public final class ExcavatorTransportStressHarness {
    public enum LodTier {
        AUTO,
        INDIVIDUAL,
        BATCHED,
        HIDDEN
    }

    public enum ComponentKind {
        BLOCK,
        ITEM
    }

    private static final int LANE_SPACING = 4;
    private static final int DEFAULT_LANE_COUNT = 64;
    private static final int DEFAULT_DESTINATION_DISTANCE = 24;
    private static final int DEFAULT_PLANE_SEPARATION = 32;
    private static final int FORCE_FIELD_Y_OFFSET = 12;
    private static final int TICKS_PER_BLOCK = 2;
    private static final ItemStack BLOCK_VISUAL_STACK = new ItemStack(Blocks.STONE.asItem());
    private static final ItemStack ITEM_VISUAL_STACK = new ItemStack(Items.DIAMOND);

    private static final ExcavatorTransportRenderer RENDERER = new ExcavatorTransportRenderer();
    private static final List<Lane> LANES = new ArrayList<>(DEFAULT_LANE_COUNT);

    private static boolean running;
    private static LodTier lodTier = LodTier.AUTO;
    private static ComponentKind componentKind = ComponentKind.BLOCK;
    private static int transportsPerTick = 64;
    private static int laneCountTarget = DEFAULT_LANE_COUNT;
    private static int destinationDistance = DEFAULT_DESTINATION_DISTANCE;
    private static int planeSeparation = DEFAULT_PLANE_SEPARATION;
    private static int laneCursor;
    private static long totalInjected;
    private static ClientLevel activeLevel;
    private static Direction streamDirection = Direction.NORTH;
    private static BlockPos destinationPlaneCenter = BlockPos.ZERO;
    private static BlockPos sourcePlaneCenter = BlockPos.ZERO;

    private ExcavatorTransportStressHarness() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!running) return;
        if (!LaserExcavatorConfig.stressTestCommandsEnabled()) {
            stop();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            stop();
            return;
        }
        if (activeLevel != level) {
            clearCurrentPlanes();
            activeLevel = null;
            rebuildPlanes(level);
        } else if (LANES.isEmpty()) {
            rebuildPlanes(level);
        }

        ItemStack visualStack = componentKind == ComponentKind.BLOCK
                ? BLOCK_VISUAL_STACK
                : ITEM_VISUAL_STACK;
        long gameTime = level.getGameTime();
        for (Lane lane : LANES) {
            ExcavatorClientVisuals.pruneSyntheticVisualSet(lane.visuals, gameTime);
        }
        int sourceDx = streamDirection.getStepX() * planeSeparation;
        int sourceDz = streamDirection.getStepZ() * planeSeparation;

        for (int i = 0; i < transportsPerTick; i++) {
            Lane lane = LANES.get(laneCursor);
            laneCursor++;
            if (laneCursor >= LANES.size()) laneCursor = 0;

            if (ExcavatorClientVisuals.addSyntheticTransport(
                    lane.visuals,
                    level,
                    sourceDx,
                    0,
                    sourceDz,
                    visualStack,
                    TICKS_PER_BLOCK,
                    FORCE_FIELD_Y_OFFSET,
                    gameTime
            )) {
                totalInjected++;
            }
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (!running || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        if (!LaserExcavatorConfig.stressTestCommandsEnabled()) return;
        if (LaserExcavatorClientConfig.isAllRenderingDisabled()) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || level != activeLevel || LANES.isEmpty()) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        byte forcedTier = forcedTierId();

        for (Lane lane : LANES) {
            ExcavatorClientVisuals.VisualSet visuals = lane.visuals;
            if (visuals.transports().isEmpty()) continue;

            poseStack.pushPose();
            poseStack.translate(
                    lane.anchor.getX() - camera.x,
                    lane.anchor.getY() - camera.y,
                    lane.anchor.getZ() - camera.z
            );
            RENDERER.renderForStressTest(
                    visuals,
                    level,
                    lane.fakeBlockEntity,
                    partialTick,
                    poseStack,
                    buffers,
                    forcedTier
            );
            poseStack.popPose();
        }
    }

    public static void start() {
        if (!LaserExcavatorConfig.stressTestCommandsEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        clearCurrentPlanes();
        running = true;
        totalInjected = 0L;
        laneCursor = 0;
        rebuildPlanes(minecraft.level);
    }

    public static void stop() {
        clearCurrentPlanes();
        running = false;
        activeLevel = null;
        laneCursor = 0;
    }

    public static void reanchor() {
        if (!running) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        clearCurrentPlanes();
        laneCursor = 0;
        rebuildPlanes(minecraft.level);
    }

    public static void setLodTier(LodTier tier) {
        lodTier = tier == null ? LodTier.AUTO : tier;
    }

    public static void setComponentKind(ComponentKind kind) {
        ComponentKind next = kind == null ? ComponentKind.BLOCK : kind;
        if (componentKind == next) return;
        componentKind = next;
        // Component-specific tests clear transports from the other component.
        if (running) resetVisualsOnly();
    }

    public static void setTransportsPerTick(int value) {
        transportsPerTick = Math.max(1, Math.min(4096, value));
    }

    public static void setLaneCount(int value) {
        laneCountTarget = Math.max(1, Math.min(1024, value));
        reanchor();
    }

    public static void setDestinationDistance(int value) {
        destinationDistance = Math.max(4, Math.min(512, value));
        reanchor();
    }

    public static void setPlaneSeparation(int value) {
        planeSeparation = Math.max(4, Math.min(256, value));
        reanchor();
    }

    public static boolean isRunning() { return running; }
    public static LodTier lodTier() { return lodTier; }
    public static ComponentKind componentKind() { return componentKind; }
    public static int transportsPerTick() { return transportsPerTick; }
    public static int destinationDistance() { return destinationDistance; }
    public static int planeSeparation() { return planeSeparation; }
    public static int laneCount() { return laneCountTarget; }
    public static long totalInjected() { return totalInjected; }
    public static BlockPos sourcePlaneCenter() { return sourcePlaneCenter; }
    public static BlockPos destinationPlaneCenter() { return destinationPlaneCenter; }

    public static int activeTransportCount() {
        if (activeLevel == null) return 0;
        int total = 0;
        for (Lane lane : LANES) {
            total += lane.visuals.transports().size();
        }
        return total;
    }

    private static void rebuildPlanes(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        activeLevel = level;
        streamDirection = Direction.fromYRot(minecraft.player.getYRot());

        BlockPos player = minecraft.player.blockPosition();
        int dirX = streamDirection.getStepX();
        int dirZ = streamDirection.getStepZ();
        int lateralX = -dirZ;
        int lateralZ = dirX;

        destinationPlaneCenter = player.offset(dirX * destinationDistance, 0, dirZ * destinationDistance);
        sourcePlaneCenter = destinationPlaneCenter.offset(
                dirX * planeSeparation,
                0,
                dirZ * planeSeparation
        );

        LANES.clear();
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(laneCountTarget)));
        int rows = Math.max(1, (laneCountTarget + columns - 1) / columns);
        int lateralSpan = (columns - 1) * LANE_SPACING;
        int verticalSpan = (rows - 1) * LANE_SPACING;
        for (int index = 0; index < laneCountTarget; index++) {
            int row = index / columns;
            int column = index % columns;
            int lateral = column * LANE_SPACING - lateralSpan / 2;
            int vertical = row * LANE_SPACING - verticalSpan / 2;
            BlockPos anchor = destinationPlaneCenter.offset(
                    lateralX * lateral,
                    vertical,
                    lateralZ * lateral
            ).immutable();
            ExcavatorBlockEntity fake = new ExcavatorBlockEntity(
                    anchor,
                    ModBlocks.EXCAVATOR.get().defaultBlockState()
            );
            LANES.add(new Lane(
                    anchor,
                    fake,
                    ExcavatorClientVisuals.createSyntheticVisualSet()
            ));
        }
    }

    private static void resetVisualsOnly() {
        if (activeLevel == null) return;
        for (Lane lane : LANES) {
            ExcavatorClientVisuals.clearSyntheticVisualSet(lane.visuals);
            RENDERER.clearStressTestState(lane.fakeBlockEntity);
        }
        laneCursor = 0;
    }

    private static void clearCurrentPlanes() {
        if (activeLevel != null) {
            for (Lane lane : LANES) {
                ExcavatorClientVisuals.clearSyntheticVisualSet(lane.visuals);
                RENDERER.clearStressTestState(lane.fakeBlockEntity);
            }
        }
        LANES.clear();
    }

    private static byte forcedTierId() {
        return switch (lodTier) {
            case AUTO -> ExcavatorTransportRenderer.stressTierAuto();
            case INDIVIDUAL -> ExcavatorTransportRenderer.stressTierIndividual();
            case BATCHED -> ExcavatorTransportRenderer.stressTierBatched();
            case HIDDEN -> ExcavatorTransportRenderer.stressTierHidden();
        };
    }

    private record Lane(
            BlockPos anchor,
            ExcavatorBlockEntity fakeBlockEntity,
            ExcavatorClientVisuals.VisualSet visuals
    ) {}
}
