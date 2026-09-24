package de.balto.laserexcavator.block.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Arrays;

/**
 * Collects and renders frame-wide camera-facing transport markers.
 *
 * All marker sizes share one opaque quad render type and are flushed once after
 * block-entity rendering.
 */
public final class ExcavatorTransportMarkerRenderer {
    private static final RenderType MARKER_RENDER_TYPE = RenderType.create(
            "laser_excavator_transport_marker",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            16_384,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderType.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderType.NO_TRANSPARENCY)
                    .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                    .setCullState(RenderType.NO_CULL)
                    .setWriteMaskState(RenderType.COLOR_DEPTH_WRITE)
                    .createCompositeState(false)
    );

    private static final int ALPHA = 255;

    // Normalizes the small-marker area relative to the configured large-marker size.
    private static final float QUAD_AREA_COMPENSATION = 0.70710677F;
    private static final float INDIVIDUAL_SMALL_HALF_SIZE_FACTOR = 0.5625F * 1.45F * QUAD_AREA_COMPENSATION;
    private static final float BATCHED_SMALL_HALF_SIZE_FACTOR = 0.5625F * 1.60F * QUAD_AREA_COMPENSATION;

    private static float[] largeMarkerPositions = new float[8_192 * 3];
    private static int[] largeMarkerColors = new int[8_192];
    private static int largeMarkerCount;

    private static float[] individualSmallMarkerPositions = new float[16_384 * 3];
    private static int[] individualSmallMarkerColors = new int[16_384];
    private static int individualSmallMarkerCount;

    private static float[] batchedSmallMarkerPositions = new float[16_384 * 3];
    private static int[] batchedSmallMarkerColors = new int[16_384];
    private static int batchedSmallMarkerCount;

    private ExcavatorTransportMarkerRenderer() {}

    static void reserveMarkers(int additionalMarkers) {
        if (additionalMarkers <= 0) return;
        ensureLargeMarkerCapacity(largeMarkerCount + additionalMarkers);
        ensureIndividualSmallMarkerCapacity(individualSmallMarkerCount + additionalMarkers);
        ensureBatchedSmallMarkerCapacity(batchedSmallMarkerCount + additionalMarkers);
    }

    static void queueLargeMarkerReserved(
            float cameraRelativeX, float cameraRelativeY, float cameraRelativeZ, int rgb
    ) {
        int index = largeMarkerCount;
        int base = index * 3;
        largeMarkerPositions[base] = cameraRelativeX;
        largeMarkerPositions[base + 1] = cameraRelativeY;
        largeMarkerPositions[base + 2] = cameraRelativeZ;
        largeMarkerColors[index] = rgb;
        largeMarkerCount = index + 1;
    }

    static void queueIndividualSmallMarkerReserved(
            float cameraRelativeX, float cameraRelativeY, float cameraRelativeZ, int rgb
    ) {
        int index = individualSmallMarkerCount;
        int base = index * 3;
        individualSmallMarkerPositions[base] = cameraRelativeX;
        individualSmallMarkerPositions[base + 1] = cameraRelativeY;
        individualSmallMarkerPositions[base + 2] = cameraRelativeZ;
        individualSmallMarkerColors[index] = rgb;
        individualSmallMarkerCount = index + 1;
    }

    static void queueBatchedSmallMarkerReserved(
            float cameraRelativeX, float cameraRelativeY, float cameraRelativeZ, int rgb
    ) {
        int index = batchedSmallMarkerCount;
        int base = index * 3;
        batchedSmallMarkerPositions[base] = cameraRelativeX;
        batchedSmallMarkerPositions[base + 1] = cameraRelativeY;
        batchedSmallMarkerPositions[base + 2] = cameraRelativeZ;
        batchedSmallMarkerColors[index] = rgb;
        batchedSmallMarkerCount = index + 1;
    }

    public static void flush(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        // A disabled renderer discards any markers queued earlier in the frame.
        if (LaserExcavatorClientConfig.isAllRenderingDisabled()) {
            clear();
            return;
        }

        int largeCount = largeMarkerCount;
        int individualSmallCount = individualSmallMarkerCount;
        int batchedSmallCount = batchedSmallMarkerCount;
        int smallCount = individualSmallCount + batchedSmallCount;
        if (largeCount == 0 && smallCount == 0) return;

        boolean profiling = ExcavatorProfiler.isEnabled();
        long totalProfile = ExcavatorProfiler.begin(profiling, ExcavatorProfiler.Section.TRANSPORT_MARKER_FRAME_RENDER);
        try {
            PoseStack poseStack = event.getPoseStack();
            var camera = event.getCamera();
            var left = camera.getLeftVector();
            var up = camera.getUpVector();

            float largeHalfSize = LaserExcavatorClientConfig.TRANSPORT_MARKER_HALF_SIZE.get().floatValue();
            float individualHalfSize = largeHalfSize * INDIVIDUAL_SMALL_HALF_SIZE_FACTOR;
            float batchedHalfSize = largeHalfSize * BATCHED_SMALL_HALF_SIZE_FACTOR;

            float largeLx = left.x() * largeHalfSize;
            float largeLy = left.y() * largeHalfSize;
            float largeLz = left.z() * largeHalfSize;
            float largeUx = up.x() * largeHalfSize;
            float largeUy = up.y() * largeHalfSize;
            float largeUz = up.z() * largeHalfSize;

            float individualLx = left.x() * individualHalfSize;
            float individualLy = left.y() * individualHalfSize;
            float individualLz = left.z() * individualHalfSize;
            float individualUx = up.x() * individualHalfSize;
            float individualUy = up.y() * individualHalfSize;
            float individualUz = up.z() * individualHalfSize;

            float batchedLx = left.x() * batchedHalfSize;
            float batchedLy = left.y() * batchedHalfSize;
            float batchedLz = left.z() * batchedHalfSize;
            float batchedUx = up.x() * batchedHalfSize;
            float batchedUy = up.y() * batchedHalfSize;
            float batchedUz = up.z() * batchedHalfSize;

            MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
            PoseStack.Pose pose = poseStack.last();
            VertexConsumer consumer = buffers.getBuffer(MARKER_RENDER_TYPE);

            if (largeCount > 0) {
                long largeProfile = ExcavatorProfiler.begin(profiling, ExcavatorProfiler.Section.LARGE_TRANSPORT_MARKER_RENDER);
                try {
                    for (int i = 0, p = 0; i < largeCount; i++, p += 3) {
                        emitQuad(consumer, pose, largeMarkerPositions[p], largeMarkerPositions[p + 1], largeMarkerPositions[p + 2],
                                largeLx, largeLy, largeLz, largeUx, largeUy, largeUz, largeMarkerColors[i]);
                    }
                } finally {
                    ExcavatorProfiler.end(ExcavatorProfiler.Section.LARGE_TRANSPORT_MARKER_RENDER, largeProfile);
                }
            }

            if (smallCount > 0) {
                long smallProfile = ExcavatorProfiler.begin(profiling, ExcavatorProfiler.Section.SMALL_TRANSPORT_MARKER_RENDER);
                try {
                    for (int i = 0, p = 0; i < individualSmallCount; i++, p += 3) {
                        emitQuad(consumer, pose, individualSmallMarkerPositions[p], individualSmallMarkerPositions[p + 1], individualSmallMarkerPositions[p + 2],
                                individualLx, individualLy, individualLz, individualUx, individualUy, individualUz, individualSmallMarkerColors[i]);
                    }
                    for (int i = 0, p = 0; i < batchedSmallCount; i++, p += 3) {
                        emitQuad(consumer, pose, batchedSmallMarkerPositions[p], batchedSmallMarkerPositions[p + 1], batchedSmallMarkerPositions[p + 2],
                                batchedLx, batchedLy, batchedLz, batchedUx, batchedUy, batchedUz, batchedSmallMarkerColors[i]);
                    }
                } finally {
                    ExcavatorProfiler.end(ExcavatorProfiler.Section.SMALL_TRANSPORT_MARKER_RENDER, smallProfile);
                }
            }

            buffers.endBatch(MARKER_RENDER_TYPE);

            if (profiling) {
                int total = largeCount + smallCount;
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.TRANSPORT_MARKER_PRIMITIVES_EMITTED, total);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_MARKER_BATCH_FLUSHES);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.LARGE_TRANSPORT_MARKERS_EMITTED, largeCount);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.SMALL_TRANSPORT_MARKERS_EMITTED, smallCount);
            }
        } finally {
            clear();
            ExcavatorProfiler.end(ExcavatorProfiler.Section.TRANSPORT_MARKER_FRAME_RENDER, totalProfile);
        }
    }

    public static void clear() {
        largeMarkerCount = 0;
        individualSmallMarkerCount = 0;
        batchedSmallMarkerCount = 0;
    }

    private static void emitQuad(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float px,
            float py,
            float pz,
            float lx,
            float ly,
            float lz,
            float ux,
            float uy,
            float uz,
            int rgb
    ) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        primitiveVertex(consumer, pose, px - lx - ux, py - ly - uy, pz - lz - uz, red, green, blue);
        primitiveVertex(consumer, pose, px + lx - ux, py + ly - uy, pz + lz - uz, red, green, blue);
        primitiveVertex(consumer, pose, px + lx + ux, py + ly + uy, pz + lz + uz, red, green, blue);
        primitiveVertex(consumer, pose, px - lx + ux, py - ly + uy, pz - lz + uz, red, green, blue);
    }

    private static void primitiveVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float vx,
            float vy,
            float vz,
            int red,
            int green,
            int blue
    ) {
        consumer.addVertex(pose, vx, vy, vz).setColor(red, green, blue, ALPHA);
    }

    private static void ensureLargeMarkerCapacity(int requiredMarkers) {
        int required = requiredMarkers * 3;
        if (required <= largeMarkerPositions.length) return;
        int next = Math.max(required, largeMarkerPositions.length << 1);
        largeMarkerPositions = Arrays.copyOf(largeMarkerPositions, next);
        largeMarkerColors = Arrays.copyOf(largeMarkerColors, next / 3);
    }

    private static void ensureIndividualSmallMarkerCapacity(int requiredMarkers) {
        int required = requiredMarkers * 3;
        if (required <= individualSmallMarkerPositions.length) return;
        int next = Math.max(required, individualSmallMarkerPositions.length << 1);
        individualSmallMarkerPositions = Arrays.copyOf(individualSmallMarkerPositions, next);
        individualSmallMarkerColors = Arrays.copyOf(individualSmallMarkerColors, next / 3);
    }

    private static void ensureBatchedSmallMarkerCapacity(int requiredMarkers) {
        int required = requiredMarkers * 3;
        if (required <= batchedSmallMarkerPositions.length) return;
        int next = Math.max(required, batchedSmallMarkerPositions.length << 1);
        batchedSmallMarkerPositions = Arrays.copyOf(batchedSmallMarkerPositions, next);
        batchedSmallMarkerColors = Arrays.copyOf(batchedSmallMarkerColors, next / 3);
    }
}
