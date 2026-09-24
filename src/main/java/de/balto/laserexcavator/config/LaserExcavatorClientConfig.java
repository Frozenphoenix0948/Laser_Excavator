package de.balto.laserexcavator.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class LaserExcavatorClientConfig {
    public enum TransportDebugMode {
        NORMAL,
        DISABLED,
        PROCESS_ONLY,
        MARKERS_ONLY
    }

    public enum BlockUpdateDebugMode {
        NORMAL,
        NO_REMESH,
        NO_BLOCK_SYNC
    }

    public enum RenderingDebugMode {
        NORMAL,
        ALL_DISABLED
    }

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue LASER_VISUAL_DURATION_TICKS;
    public static final ModConfigSpec.BooleanValue EMIT_VANILLA_BREAK_EFFECTS;
    public static final ModConfigSpec.IntValue MAX_LASERS_PER_EXCAVATOR;
    public static final ModConfigSpec.IntValue MAX_TRANSPORTS_PER_EXCAVATOR;
    public static final ModConfigSpec.IntValue MIN_TRANSPORT_BLOCK_LIGHT;
    public static final ModConfigSpec.DoubleValue BLOCK_VISUAL_HALF_SIZE;
    public static final ModConfigSpec.DoubleValue ITEM_VISUAL_HALF_SIZE;
    public static final ModConfigSpec.DoubleValue TRANSPORT_MARKER_HALF_SIZE;
    public static final ModConfigSpec.DoubleValue BILLBOARD_DISTANCE;
    public static final ModConfigSpec.DoubleValue LASER_MID_LOD_DISTANCE;
    public static final ModConfigSpec.DoubleValue LASER_FAR_LOD_DISTANCE;
    public static final ModConfigSpec.DoubleValue TRANSPORT_LARGE_MARKER_DISTANCE;
    public static final ModConfigSpec.DoubleValue TRANSPORT_SMALL_MARKER_DISTANCE;
    public static final ModConfigSpec.DoubleValue TRANSPORT_BATCH_DISTANCE;
    public static final ModConfigSpec.IntValue TRANSPORT_BATCH_MAX_MARKERS;
    public static final ModConfigSpec.IntValue TRANSPORT_BATCH_MIN_MARKERS;
    public static final ModConfigSpec.DoubleValue TRANSPORT_BATCH_FALLOFF_EXPONENT;
    public static final ModConfigSpec.DoubleValue TRANSPORT_MAX_RENDER_DISTANCE;
    public static final ModConfigSpec.IntValue FORCE_FIELD_GRID_SPACING;
    public static final ModConfigSpec.DoubleValue PILLAR_OUTER_RADIUS;
    public static final ModConfigSpec.DoubleValue PILLAR_CORE_RADIUS;
    public static final ModConfigSpec.IntValue RENDER_DISTANCE;

    public static final ModConfigSpec.EnumValue<TransportDebugMode> TRANSPORT_DEBUG_MODE;
    public static final ModConfigSpec.EnumValue<BlockUpdateDebugMode> BLOCK_UPDATE_DEBUG_MODE;
    public static final ModConfigSpec.EnumValue<RenderingDebugMode> RENDERING_DEBUG_MODE;
    public static final ModConfigSpec.IntValue VISUAL_INGESTION_BUDGET_MICROS;

    /**
     * Session-only command override. Null means the value from laserexcavator-client.toml is used.
     * Volatile because config/UI changes and render reads can happen through different client callbacks.
     */
    private static volatile TransportDebugMode transportDebugModeOverride;
    private static volatile BlockUpdateDebugMode blockUpdateDebugModeOverride;
    private static volatile RenderingDebugMode renderingDebugModeOverride;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                " Client-local excavator rendering limits and visual tuning.",
                " These values are chosen independently by each client and are never synchronized from a server."
        ).push("rendering");
        LASER_VISUAL_DURATION_TICKS = builder.comment(
                        " Cosmetic excavation-laser lifetime in ticks. This does not change mining speed or FE use."
                )
                .defineInRange("laserVisualDurationTicks", 20, 1, 200);
        EMIT_VANILLA_BREAK_EFFECTS = builder.comment(
                        " Play vanilla block-break sounds and particles locally when excavator block removals reach this client."
                )
                .define("emitVanillaBreakEffects", false);
        MAX_LASERS_PER_EXCAVATOR = builder.comment(
                        " Maximum number of laser visuals kept per excavator on this client."
                )
                .defineInRange("maxLasersPerExcavator", 32, 1, 4096);
        MAX_TRANSPORTS_PER_EXCAVATOR = builder.comment(
                        " Maximum number of active transport visual records kept per excavator on this client.",
                        " New transport visuals are skipped while the limit is full; active transports are never evicted early."
                )
                .defineInRange("maxTransportsPerExcavator", 1024, 16, 65536);
        MIN_TRANSPORT_BLOCK_LIGHT = builder.comment(
                        " Minimum block-light level used for transport lighting; sampled sky light is preserved."
                )
                .defineInRange("minimumTransportBlockLight", 8, 0, 15);
        BLOCK_VISUAL_HALF_SIZE = builder.comment(
                        " Half-size of close transported block visuals; larger values make textured cubes and billboards appear bigger."
                )
                .defineInRange("blockVisualHalfSize", 0.16D, 0.01D, 2.0D);
        ITEM_VISUAL_HALF_SIZE = builder.comment(
                        " Half-size of close non-block item billboards; larger values make transported items appear bigger."
                )
                .defineInRange("itemVisualHalfSize", 0.22D, 0.01D, 2.0D);
        TRANSPORT_MARKER_HALF_SIZE = builder.comment(
                        " Half-size of the large untextured transport marker used after the textured billboard range.",
                        " Smaller transport markers derive their size from this value as well."
                )
                .defineInRange("transportDotHalfSize", 0.10D, 0.01D, 1.0D);
        BILLBOARD_DISTANCE = builder.comment(
                        " Distance where transported blocks switch from full textured cubes to cheaper textured billboards."
                )
                .defineInRange("billboardDistance", 20.0D, 1.0D, 512.0D);
        LASER_MID_LOD_DISTANCE = builder.comment(
                        " Camera distance where excavation lasers switch from full 3D geometry to a camera-facing three-line beam.",
                        " The medium-distance beam keeps the animated cyan corona and core colors of the full laser."
                )
                .defineInRange("laser2dLodDistance", 48.0D, 16.0D, 2048.0D);
        LASER_FAR_LOD_DISTANCE = builder.comment(
                        " Camera distance where excavation lasers simplify further to one bright core line.",
                        " The far beam avoids camera-facing calculations and keeps the full-laser core color."
                )
                .defineInRange("laserSimpleLodDistance", 92.0D, 16.0D, 4096.0D);
        TRANSPORT_LARGE_MARKER_DISTANCE = builder.comment(
                        " Distance where transported blocks leave the textured billboard path and become large untextured markers.",
                        " With the default, blocks are textured below 40 blocks. Non-block items remain textured until transportSmallMarkerDistance."
                )
                .defineInRange("transportDotDistance", 40.0D, 16.0D, 2048.0D);
        TRANSPORT_SMALL_MARKER_DISTANCE = builder.comment(
                        " Distance where large block markers and non-block item billboards switch to smaller markers.",
                        " This is only a visual-size transition; it does not split the current-position spatial-region cache."
                )
                .defineInRange("transportSmallMarkerDistance", 64.0D, 16.0D, 2048.0D);
        TRANSPORT_BATCH_DISTANCE = builder.comment(
                        " Distance where individual transport visuals enter continuous representative batching.",
                        " Below this distance every transport remains individually represented; cube, billboard, and marker changes are visual-only."
                )
                .defineInRange("transportBatchDistance", 100.0D, 16.0D, 2048.0D);
        TRANSPORT_BATCH_MAX_MARKERS = builder.comment(
                        " Maximum representative transport markers per excavator at the start of the continuous batching range."
                )
                .defineInRange("transportBatchMaxDots", 192, 1, 2048);
        TRANSPORT_BATCH_MIN_MARKERS = builder.comment(
                        " Minimum representative transport markers kept just before transportMaxRenderDistance.",
                        " The representative budget falls continuously from transportBatchMaxDots toward this value."
                )
                .defineInRange("transportBatchMinDots", 8, 1, 1024);
        TRANSPORT_BATCH_FALLOFF_EXPONENT = builder.comment(
                        " Shape of the continuous transport representative falloff.",
                        " Higher values reduce the representative marker count more aggressively with distance."
                )
                .defineInRange("transportBatchFalloffExponent", 2.5D, 0.25D, 8.0D);
        TRANSPORT_MAX_RENDER_DISTANCE = builder.comment(
                        " Maximum camera distance for transport visuals. Fixed current-position regions fully beyond this distance enter a hidden LOD and emit no transport geometry.",
                        " The renderer classifies fixed 16 x 16 x 16 current-position regions as individual, batched, or hidden.",
                        " This affects transports only; force fields and lasers keep their normal LOD behavior."
                )
                .defineInRange("transportMaxRenderDistance", 156.0D, 16.0D, 4096.0D);
        FORCE_FIELD_GRID_SPACING = builder.comment(
                        " Base spacing in blocks between force-field grid lines; medium and far LODs increase this spacing automatically."
                )
                .defineInRange("forceFieldGridSpacing", 4, 1, 64);
        PILLAR_OUTER_RADIUS = builder.comment(
                        " Base offset of the four outer lines that form each force-field corner pillar; the renderer adds a small pulse animation."
                )
                .defineInRange("pillarOuterRadius", 0.105D, 0.005D, 2.0D);
        PILLAR_CORE_RADIUS = builder.comment(
                        " Base offset of the bright inner lines of full-detail force-field corner pillars; the renderer adds a small pulse animation."
                )
                .defineInRange("pillarCoreRadius", 0.035D, 0.001D, 1.0D);
        RENDER_DISTANCE = builder.comment(" Block-entity renderer view distance in blocks.")
                .defineInRange("renderDistance", 512, 16, 2048);
        builder.pop();

        builder.comment(
                " Client-only transport rendering diagnostics.",
                " These options never change server-side excavation behavior or transport networking."
        ).push("debugRendering");
        TRANSPORT_DEBUG_MODE = builder.comment(
                        " Transport rendering diagnostic mode.",
                        " NORMAL: normal renderer.",
                        " DISABLED: keep transport cache/network state, but skip LOD/representation rendering work.",
                        " PROCESS_ONLY: run normal transport cache, LOD, traversal and representative selection, but submit no geometry.",
                        " MARKERS_ONLY: render every visible in-range transport as the cheapest small opaque marker."
                )
                .defineEnum("transportDebugMode", TransportDebugMode.NORMAL);
        BLOCK_UPDATE_DEBUG_MODE = builder.comment(
                        " Excavation client block-update diagnostic mode.",
                        " NORMAL: apply excavated AIR states and request section remeshes normally.",
                        " NO_REMESH: apply AIR states, but suppress LevelRenderer section-dirty/remesh requests.",
                        " NO_BLOCK_SYNC: ignore excavator block-removal payloads on the client entirely (diagnostic only; reload chunks after testing)."
                )
                .defineEnum("blockUpdateDebugMode", BlockUpdateDebugMode.NORMAL);
        RENDERING_DEBUG_MODE = builder.comment(
                        " Whole excavator rendering diagnostic mode.",
                        " NORMAL: render force fields, pillars, lasers and transports normally.",
                        " ALL_DISABLED: skip the complete excavator block-entity renderer and global transport marker flush.",
                        " This does not change excavation, networking, client block sync or chunk remeshing."
                )
                .defineEnum("renderingDebugMode", RenderingDebugMode.NORMAL);
        builder.pop();

        builder.comment(
                " Client-side excavator visual ingestion limits.",
                " These only control how much decoded visual-network work may be materialized in one client tick."
        ).push("visualIngestion");
        VISUAL_INGESTION_BUDGET_MICROS = builder.comment(
                        " Maximum wall-clock microseconds spent draining queued excavator visual payloads in one client tick.",
                        " 0 disables the time budget and drains the full queue.",
                        " Events keep their original server start time, so deferred events resume at the correct animation position."
                )
                .defineInRange("budgetMicros", 3000, 0, 50_000);
        builder.pop();
        SPEC = builder.build();
    }

    private LaserExcavatorClientConfig() {}

    public static TransportDebugMode transportDebugMode() {
        TransportDebugMode override = transportDebugModeOverride;
        return override != null ? override : TRANSPORT_DEBUG_MODE.get();
    }

    public static void setTransportDebugModeOverride(TransportDebugMode mode) {
        transportDebugModeOverride = mode;
    }

    public static void clearTransportDebugModeOverride() {
        transportDebugModeOverride = null;
    }

    public static boolean hasTransportDebugModeOverride() {
        return transportDebugModeOverride != null;
    }

    public static TransportDebugMode configuredTransportDebugMode() {
        return TRANSPORT_DEBUG_MODE.get();
    }

    public static BlockUpdateDebugMode blockUpdateDebugMode() {
        BlockUpdateDebugMode override = blockUpdateDebugModeOverride;
        return override != null ? override : BLOCK_UPDATE_DEBUG_MODE.get();
    }

    public static void setBlockUpdateDebugModeOverride(BlockUpdateDebugMode mode) {
        blockUpdateDebugModeOverride = mode;
    }

    public static void clearBlockUpdateDebugModeOverride() {
        blockUpdateDebugModeOverride = null;
    }

    public static boolean hasBlockUpdateDebugModeOverride() {
        return blockUpdateDebugModeOverride != null;
    }

    public static BlockUpdateDebugMode configuredBlockUpdateDebugMode() {
        return BLOCK_UPDATE_DEBUG_MODE.get();
    }

    public static RenderingDebugMode renderingDebugMode() {
        RenderingDebugMode override = renderingDebugModeOverride;
        return override != null ? override : RENDERING_DEBUG_MODE.get();
    }

    public static boolean isAllRenderingDisabled() {
        return renderingDebugMode() == RenderingDebugMode.ALL_DISABLED;
    }

    public static void setRenderingDebugModeOverride(RenderingDebugMode mode) {
        renderingDebugModeOverride = mode;
    }

    public static void clearRenderingDebugModeOverride() {
        renderingDebugModeOverride = null;
    }

    public static boolean hasRenderingDebugModeOverride() {
        return renderingDebugModeOverride != null;
    }

    public static RenderingDebugMode configuredRenderingDebugMode() {
        return RENDERING_DEBUG_MODE.get();
    }
}
