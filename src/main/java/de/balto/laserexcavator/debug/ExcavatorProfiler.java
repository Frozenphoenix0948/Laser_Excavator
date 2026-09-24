package de.balto.laserexcavator.debug;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Profiler for the excavator.
 *
 * The profiler is disabled by default.
 * Timings intentionally overlap. EXCAVATION_TICK, for example, contains
 * target selection, loot, storage checks and block removal. Never sum the
 * top-level sections.
 */
public final class ExcavatorProfiler {
    public static final String IMPLEMENTATION_VERSION = "V1.0";
    private static final double SERVER_TICKS_PER_SECOND = 20.0;

    public enum Section {
        SCANNER("01 Scanner total", Side.SERVER),
        EXCAVATION_TICK("02 Excavation tick total", Side.SERVER),
        TARGET_SELECTION("03 Target selection", Side.SERVER),
        LOOT_GENERATION("04 Loot generation", Side.SERVER),
        STORAGE_RESERVATION("05 Storage capacity check", Side.SERVER),
        BLOCK_REMOVAL("06 setBlock / block removal", Side.SERVER),
        BLOCK_REMOVAL_LEVEL_SETBLOCK("06a Normal Level#setBlock", Side.SERVER),
        BLOCK_REMOVAL_DIRECT_CHUNK("06b Direct LevelChunk removal", Side.SERVER),
        NEXT_TARGET_LOOKUP("07 Next-target lookup", Side.SERVER),
        DELIVERY_PROCESSING("08 Delivery queue processing", Side.SERVER),
        VISUAL_PACKET_SEND("09a Visual batch packet sending", Side.SERVER),
        BLOCK_UPDATE_PACKET_SEND("09b Block section-payload batching", Side.SERVER),
        CLIENT_BLOCK_UPDATE_APPLY("09c Client silent block apply", Side.CLIENT),
        CLIENT_SECTION_DIRTY_FLUSH("09d Client distance-scheduled section refresh", Side.CLIENT),
        CLIENT_VISUAL_INGESTION("09e Client visual ingestion", Side.CLIENT),
        CLIENT_VISUAL_TRANSPORT_PREPARATION("09f Client transport preparation", Side.CLIENT),
        FORCE_FIELD_RENDER("10 Force-field + pillars render", Side.CLIENT),
        LASER_RENDER("11 Laser rendering", Side.CLIENT),
        TRANSPORT_RENDER("12 Transport rendering", Side.CLIENT),
        TRANSPORT_MARKER_FRAME_RENDER("13 Global transport primitive frame flush", Side.CLIENT),
        LARGE_TRANSPORT_MARKER_RENDER("13a Large transport marker emission", Side.CLIENT),
        SMALL_TRANSPORT_MARKER_RENDER("13b Small transport marker emission", Side.CLIENT);

        private final String label;
        private final Side side;

        Section(String label, Side side) {
            this.label = label;
            this.side = side;
        }

        public String label() {
            return label;
        }

        public Side side() {
            return side;
        }
    }

    public enum TransportSubsection {
        INDIVIDUAL_TRANSPORTS("12a Individual transport representations"),
        BLOCK_CUBE_RENDERING("12a1 Textured cube emission (1/16 sampled)"),
        BLOCK_BILLBOARD_RENDERING("12a2 Textured block billboard emission (1/16 sampled)"),
        BATCHED_MARKERS("12b Batched transport markers"),
        CACHE_CLEANUP("12g Bounded transport-cache cleanup"),
        FRAME_SETUP("12h Shared frame/camera setup"),
        CACHE_SYNC("12i New-transport cache synchronization"),
        REGION_MEMBERSHIP("12j Timing-wheel region membership"),
        REGION_POSITION_SAMPLE("12j1 Current-position evaluation (1/256 sampled)"),
        REGION_KEY_SAMPLE("12j2 16^3 region key + compare (1/256 sampled)"),
        REGION_MOVE_SAMPLE("12j3 O(1) region migration (sampled movers)"),
        SPATIAL_REGION_LOD("12k Spatial-region LOD classification"),
        HIDDEN_WHOLE_EFFECT_TEST("12l Whole-effect HIDDEN bound test");

        private final String label;

        TransportSubsection(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Counter {
        GLOBAL_SERVER_TICKS("Global server ticks observed"),
        EXCAVATOR_SERVER_TICKS("Excavator server ticks observed"),
        SCAN_COLUMNS("Scan columns processed"),
        SCAN_BLOCK_STATE_LOOKUPS("Scan block-state lookups"),
        NEXT_TARGET_STATE_LOOKUPS("Next-target block-state lookups"),
        SHARED_HEIGHT_COLUMNS_CREATED("Shared-height columns created"),
        SHARED_HEIGHT_REGISTRATIONS("Shared-height column registrations"),
        SHARED_HEIGHT_OWNER_AREAS_REGISTERED("Shared-height owner areas registered"),
        SHARED_HEIGHT_DUPLICATE_AREA_REGISTRATIONS_SKIPPED("Duplicate shared-height area registrations skipped"),
        SHARED_HEIGHT_OWNER_AREAS_UNREGISTERED("Shared-height owner areas unregistered"),
        SHARED_HEIGHT_REFERENCE_RELEASES("Shared-height column-reference releases"),
        SHARED_HEIGHT_COLUMNS_RELEASED("Shared-height columns released after last owner"),
        SHARED_HEIGHT_LOOKUPS("Shared-height cursor lookups"),
        SHARED_HEIGHT_ADVANCES("Shared-height cursor advances"),
        SHARED_HEIGHT_REPAIRS("Shared-height downward repairs"),
        SHARED_HEIGHT_STALE_LOCAL_AVOIDED("Stale local target cursors avoided by shared height"),
        SHARED_HEIGHT_INFLIGHT_REDIRECTS("In-flight lasers redirected to newer shared height"),
        SHARED_HEIGHT_OUT_OF_RANGE_SKIPS("Shared-height selections skipped above local range"),
        SHARED_HEIGHT_PRIVATE_PATH_SELECTIONS("Private target selections due to target-changing upgrades"),
        BLOCKS_EXCAVATED("Blocks excavated"),
        LASER_COMPLETIONS_SKIPPED_UNLOADED("Laser completions skipped - target chunk unloaded"),
        BLOCK_REMOVALS_FAILED_COLUMN_RETAINED("Block removals failed - column retained"),
        FAST_REMOVAL_ELIGIBILITY_CACHE_HITS("Fast-removal eligibility cache hits"),
        FAST_REMOVAL_ELIGIBILITY_CACHE_MISSES("Fast-removal eligibility cache misses"),
        DIRECT_CHUNK_REMOVAL_ATTEMPTS("Direct-chunk removal attempts"),
        DIRECT_CHUNK_REMOVAL_SUCCESSES("Direct-chunk removal successes"),
        NORMAL_LEVEL_SETBLOCK_FALLBACKS("Normal Level#setBlock removal fallbacks"),
        STORAGE_RETRIES_SKIPPED_UNLOADED("Storage retries skipped - target chunk unloaded"),
        DETERMINISTIC_LOOT_CACHE_HITS("Deterministic loot cache hits"),
        DETERMINISTIC_LOOT_CACHE_MISSES("Deterministic loot cache misses"),
        DETERMINISTIC_LOOT_LEARNING_OBSERVATIONS("Deterministic loot learning observations"),
        DETERMINISTIC_LOOT_CACHE_PROMOTIONS("Deterministic loot cache promotions"),
        DETERMINISTIC_LOOT_CACHE_REJECTIONS("Deterministic loot cache rejections"),
        DETERMINISTIC_LOOT_CACHE_AUDITS("Deterministic loot cache audits"),
        DETERMINISTIC_LOOT_CACHE_AUDIT_FAILURES("Deterministic loot cache audit failures"),
        NORMAL_LOOT_TABLE_CALLS("Normal loot-table calls"),
        SINGLE_STACK_RESERVATION_CHECKS("Single-stack capacity checks"),
        DELIVERY_BATCHES_PROCESSED("Delivery batches processed"),
        DELIVERY_STACKS_INSERTED("Delivery stacks inserted"),
        DELIVERY_INDEX_REBUILDS("Delivery inventory index rebuilds"),
        DELIVERY_INDEX_SLOT_UPDATES("Delivery index structural slot updates"),
        DELIVERY_INDEX_ITEM_SLOT_CHECKS("Delivery indexed matching-slot checks"),
        DELIVERY_INDEX_EMPTY_SLOT_USES("Delivery direct empty-slot uses"),
        LASER_VISUAL_EVENTS_QUEUED("Laser visual events queued"),
        TRANSPORT_VISUAL_EVENTS_QUEUED("Transport visual events queued"),
        NETWORK_DEBUG_LASER_EVENTS_SUPPRESSED("Laser visual events suppressed by server network debug"),
        NETWORK_DEBUG_TRANSPORT_EVENTS_SUPPRESSED("Transport visual events suppressed by server network debug"),
        NETWORK_DEBUG_PENDING_VISUAL_EVENTS_DROPPED("Already-queued visual events dropped by server network debug"),
        VISUAL_BATCH_PACKETS_SENT("Visual batch payloads sent"),
        VISUAL_EVENTS_SENT("Visual events sent in batches"),
        VISUAL_BATCH_EXCAVATOR_GROUPS_SENT("Excavator visual groups sent in batches"),
        VISUAL_BATCH_INTERVAL_FLUSHES("Visual chunk batches flushed by interval"),
        VISUAL_BATCH_CAP_FLUSHES("Visual chunk batches flushed by event cap"),
        VISUAL_EVENT_AGE_TICKS_SENT("Accumulated visual-event queue age ticks sent"),
        BLOCK_CLIENT_UPDATES_BATCHED("Block client updates batched"),
        BLOCK_UPDATE_SECTION_PACKETS("Block section packets built"),
        BLOCK_UPDATE_PACKET_TRANSMISSIONS("Block section payload transmissions"),
        BLOCK_UPDATE_NEAR_SECTION_FLUSHES("Near section payload flushes"),
        BLOCK_UPDATE_MID_SECTION_FLUSHES("Mid section payload flushes"),
        BLOCK_UPDATE_FAR_SECTION_FLUSHES("Far section payload flushes"),
        BLOCK_UPDATE_VERY_FAR_SECTION_FLUSHES("Very-far section payload flushes"),
        BLOCK_UPDATE_QUIET_SECTION_FLUSHES("Section payloads flushed early after becoming quiet"),
        BLOCK_UPDATE_NO_TRACKER_DROPS("Pending section updates dropped with no tracking player"),
        CLIENT_FRAMES("Client render frames observed"),
        CLIENT_BLOCK_UPDATES_APPLIED("Client silent block removals applied"),
        CLIENT_BLOCK_SYNC_POSITIONS_SKIPPED("Client block-sync positions skipped by debug mode"),
        CLIENT_REMESH_SECTIONS_SUPPRESSED("Client section remesh requests suppressed by debug mode"),
        CLIENT_REMESH_CATCHUP_SECTIONS("Client suppressed sections queued for catch-up remesh"),
        CLIENT_PRIMARY_SECTION_DIRTY_REQUESTS("Client primary section dirty requests"),
        CLIENT_NEIGHBOR_SECTION_DIRTY_REQUESTS("Client neighbor section dirty requests"),
        CLIENT_NEIGHBOR_SECTION_DIRTY_AVOIDED_AIR("Client neighbor section dirties avoided - adjacent air/unloaded"),
        CLIENT_UNIQUE_SECTION_DIRTY_CALLS("Client unique section dirty calls"),
        CLIENT_SECTION_DIRTY_SKIPPED_UNLOADED("Client section dirty calls skipped - chunk unloaded"),
        CLIENT_SECTION_DIRTY_ABSORBED_PENDING("Client dirty requests absorbed into persistent pending section"),
        CLIENT_SECTION_DIRTY_SCHEDULER_TICKS("Client section rebuild scheduler ticks"),
        CLIENT_SECTION_DIRTY_PENDING_SAMPLES("Client pending dirty-section count samples"),
        CLIENT_SECTION_DIRTY_PENDING_PEAK("Client pending dirty-section peak"),
        CLIENT_SECTION_DIRTY_AGE_TICKS_TOTAL("Client submitted section dirty-age ticks total"),
        CLIENT_SECTION_DIRTY_AGE_TICKS_MAX("Client submitted section dirty-age max"),
        CLIENT_SECTION_REFRESH_WHEEL_ENTRIES("Client section-refresh wheel entries visited"),
        CLIENT_SECTION_REFRESH_STALE_WHEEL_ENTRIES("Client stale section-refresh wheel entries skipped"),
        CLIENT_SECTION_REFRESH_DUE_ENTRIES("Client due section-refresh wheel entries"),
        CLIENT_SECTION_REFRESH_PROMOTIONS("Client pending section refreshes promoted after distance change"),
        CLIENT_SECTION_REFRESH_INTERVAL_TICKS_TOTAL("Client submitted section scheduled interval ticks total"),
        CLIENT_VISUAL_PAYLOADS_RECEIVED("Client visual payloads received"),
        CLIENT_VISUAL_GROUPS_RECEIVED("Client visual excavator groups received"),
        CLIENT_VISUAL_EVENTS_RECEIVED("Client visual events received"),
        CLIENT_VISUAL_QUEUE_DRAINS("Client visual queue drains"),
        CLIENT_VISUAL_PAYLOADS_DRAINED("Client visual payloads drained"),
        CLIENT_VISUAL_GROUPS_DRAINED("Client visual excavator groups drained"),
        CLIENT_VISUAL_EVENTS_DRAINED("Client visual events drained"),
        CLIENT_VISUAL_LASERS_MATERIALIZED("Client laser visuals materialized"),
        CLIENT_VISUAL_TRANSPORTS_MATERIALIZED("Client transport visuals materialized"),
        CLIENT_VISUAL_EVENTS_EXPIRED("Client visual events expired before materialization"),
        CLIENT_VISUAL_RING_EVICTIONS("Client laser ring-buffer evictions"),
        CLIENT_VISUAL_TRANSPORT_LIMIT_REJECTIONS("Client transport visuals rejected at render limit"),
        CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_HITS("Client transport block-texture cache hits"),
        CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_MISSES("Client transport block-texture cache misses"),
        CLIENT_VISUAL_ITEM_TEXTURE_RESOLVES("Client non-block item texture resolves"),
        CLIENT_VISUAL_LIGHT_LOOKUPS("Client spatial-region light lookups"),
        CLIENT_VISUAL_REGION_LIGHT_CACHE_HITS("Client spatial-region light cache hits"),
        CLIENT_VISUAL_REGION_LIGHT_CACHE_MISSES("Client spatial-region light cache misses"),
        CLIENT_VISUAL_INGESTION_BUDGET_HITS("Client visual ingestion budget hits"),
        CLIENT_VISUAL_PENDING_PAYLOADS_AFTER_DRAIN("Client visual pending payloads after drain samples"),
        CLIENT_VISUAL_LASER_POOL_REUSES("Client laser visual slot reuses"),
        CLIENT_VISUAL_TRANSPORT_POOL_REUSES("Client transport visual slot reuses"),
        CLIENT_VISUAL_LASER_ALLOCATIONS("Client laser visual slot allocations"),
        CLIENT_VISUAL_TRANSPORT_ALLOCATIONS("Client transport visual slot allocations"),
        EXCAVATOR_FRUSTUM_TESTS("Excavator whole-effect frustum tests"),
        EXCAVATOR_FRUSTUM_CULLED("Excavators culled by whole-effect frustum"),
        RENDER_CALLS("Excavator block-entity render calls"),
        FORCE_FIELDS_FULL_GEOMETRY("Force fields rendered with full geometry"),
        FORCE_FIELDS_MID_GEOMETRY("Force fields rendered with medium geometry"),
        FORCE_FIELDS_FAR_GEOMETRY("Force fields rendered with far geometry"),
        LASERS_FULL_RENDERED("Full-geometry lasers rendered"),
        LASERS_MID_RENDERED("Medium-distance 3-line lasers rendered"),
        LASERS_FAR_RENDERED("Far-distance 1-line lasers rendered"),
        LASERS_MID_DENSITY_SKIPPED("MID lasers skipped by density thinning"),
        LASERS_FAR_DENSITY_SKIPPED("FAR lasers skipped by density thinning"),
        LASER_CENTER_FAR_FAST_PATH_TESTS("Laser center-FAR fast-path tests"),
        LASER_CENTER_FAR_FAST_PATH_HITS("Laser center-FAR fast-path hits"),
        TRANSPORTS_VISITED("Transport visuals represented/visited"),
        TRANSPORT_REGION_TICK_PASSES("Client ticks with spatial-region timing-wheel maintenance"),
        TRANSPORT_REGION_POTENTIAL_SCAN_VISITS("Active transports that a full membership scan would visit"),
        TRANSPORT_REGION_WHEEL_BUCKETS_VISITED("Spatial-region timing-wheel buckets visited"),
        TRANSPORT_REGION_WHEEL_ENTRIES_VISITED("Spatial-region timing-wheel entries visited"),
        TRANSPORT_REGION_WHEEL_FUTURE_SKIPS("Timing-wheel entries deferred to a future rotation"),
        TRANSPORT_REGION_MEMBERSHIP_CHECKS("Due exact spatial-region membership checks"),
        TRANSPORT_REGION_MIGRATIONS("Transport moves between 16^3 spatial regions"),
        TRANSPORT_REGION_LOD_PASSES("Spatial-region LOD classification passes"),
        TRANSPORT_REGION_LOD_TESTS("Occupied spatial regions LOD-classified"),
        TRANSPORT_CLEANUP_BUDGET_HITS("Transport cleanup budget hits"),
        TRANSPORT_ANIMATION_BACKWARD_TIME_CLAMPS("Transport animation backward-time clamps"),
        TRANSPORT_CACHE_ADDITIONS("Transport cache additions"),
        TRANSPORT_CACHE_REMOVALS("Transport cache removals"),
        TRANSPORT_REGION_ADDITIONS("Current-position spatial regions created"),
        TRANSPORT_REGION_REMOVALS("Current-position spatial regions removed"),
        TRANSPORT_ACTIVE_TRANSPORT_SAMPLES("Active spatial-region transports sampled"),
        TRANSPORT_ACTIVE_REGION_SAMPLES("Active current-position regions sampled"),
        TRANSPORT_INDIVIDUAL_REGIONS_VISITED("INDIVIDUAL regions sampled"),
        TRANSPORT_BATCHED_REGIONS_VISITED("BATCHED regions sampled"),
        TRANSPORT_HIDDEN_REGIONS("HIDDEN regions sampled"),
        TRANSPORT_HIDDEN_SOURCE_VISUALS("Transport visuals skipped by region max-distance hiding"),
        TRANSPORT_HIDDEN_WHOLE_EFFECT_REJECTS("Whole transport effects rejected before cache sync"),
        TRANSPORT_HIDDEN_WHOLE_EFFECT_VISUALS_SKIPPED("Raw transport visuals skipped by whole-effect HIDDEN reject"),
        TRANSPORT_HIDDEN_ALL_REGIONS_EARLY_RETURNS("All-spatial-regions-HIDDEN renderer early returns"),
        TRANSPORT_HIDDEN_ALL_REGIONS_VISUALS_SKIPPED("Cached transport visuals skipped by all-regions-HIDDEN early return"),
        TRANSPORT_FRUSTUM_REGION_TESTS("Transport spatial-region frustum tests"),
        TRANSPORT_FRUSTUM_REGIONS_CULLED("Transport spatial regions frustum-culled"),
        TRANSPORT_FRUSTUM_MEMBERS_CULLED("Transport members skipped by group frustum culling"),
        BLOCK_CUBES_RENDERED("Individual textured block cubes rendered"),
        BLOCK_BILLBOARDS_RENDERED("Individual textured block billboards rendered"),
        INDIVIDUAL_MARKERS_RENDERED("Individual transport markers queued"),
        NON_BLOCK_SPRITES_RENDERED("Individual non-block item billboards rendered"),
        BATCH_SOURCE_VISUALS("Continuous-batch source visuals represented"),
        BATCH_MARKERS_RENDERED("Batched transport markers rendered"),
        BATCH_TARGET_MARKERS_TOTAL("Continuous-batch representative targets summed"),
        BATCH_TARGET_MARKER_SAMPLES("Continuous-batch representative target samples"),
        TRANSPORT_MARKER_PRIMITIVES_EMITTED("Frame-wide transport primitives emitted"),
        TRANSPORT_MARKER_BATCH_FLUSHES("Frame-wide transport primitive flushes"),
        LARGE_TRANSPORT_MARKERS_EMITTED("Frame-wide large transport markers emitted"),
        SMALL_TRANSPORT_MARKERS_EMITTED("Frame-wide small transport markers emitted");

        private final String label;

        Counter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Side {
        SERVER("S"),
        CLIENT("C");

        private final String shortName;

        Side(String shortName) {
            this.shortName = shortName;
        }

        public String shortName() {
            return shortName;
        }
    }

    private static final Section[] SECTIONS = Section.values();
    private static final TransportSubsection[] TRANSPORT_SUBSECTIONS = TransportSubsection.values();
    private static final Counter[] COUNTERS = Counter.values();

    private static final LongAdder[] TOTAL_NANOS = createAdders(SECTIONS.length);
    private static final LongAdder[] CALLS = createAdders(SECTIONS.length);
    private static final AtomicLong[] MAX_NANOS = createAtomicLongs(SECTIONS.length);


    private static final LongAdder[] TRANSPORT_SUB_TOTAL_NANOS = createAdders(TRANSPORT_SUBSECTIONS.length);
    private static final LongAdder[] TRANSPORT_SUB_CALLS = createAdders(TRANSPORT_SUBSECTIONS.length);
    private static final AtomicLong[] TRANSPORT_SUB_MAX_NANOS = createAtomicLongs(TRANSPORT_SUBSECTIONS.length);

    private static final LongAdder[] COUNTER_VALUES = createAdders(COUNTERS.length);
    private static final AtomicLong[] COUNTER_MAX_VALUES = createAtomicLongs(COUNTERS.length);

    private static volatile boolean enabled;
    private static volatile long startedAtNanos;
    private static volatile long stoppedAtNanos;

    private ExcavatorProfiler() {}

    /** Returns zero without reading the clock while profiling is disabled. */
    public static long begin(Section section) {
        return enabled ? System.nanoTime() : 0L;
    }

    /**
     * Nested hot paths can reuse one outer isEnabled() result and avoid
     * repeatedly reading the profiler's volatile enabled flag.
     */
    public static long begin(boolean profiling, Section section) {
        return profiling ? System.nanoTime() : 0L;
    }

    public static void end(Section section, long startNanos) {
        if (startNanos == 0L) return;
        long sessionStart = startedAtNanos;
        if (sessionStart == 0L || startNanos < sessionStart) return;

        long stop = stoppedAtNanos;
        if (!enabled && stop != 0L && startNanos > stop) return;

        long endNanos = (!enabled && stop != 0L) ? stop : System.nanoTime();
        long nanos = endNanos - startNanos;
        if (nanos >= 0L) recordDuration(section, nanos);
    }

    public static void addDuration(Section section, long nanos) {
        if (enabled && nanos >= 0L) recordDuration(section, nanos);
    }

    private static void recordDuration(Section section, long nanos) {
        int index = section.ordinal();
        TOTAL_NANOS[index].add(nanos);
        CALLS[index].increment();
        updateMax(MAX_NANOS[index], nanos);
    }


    public static long begin(TransportSubsection subsection) {
        return enabled ? System.nanoTime() : 0L;
    }

    public static long begin(boolean profiling, TransportSubsection subsection) {
        return profiling ? System.nanoTime() : 0L;
    }

    public static void end(TransportSubsection subsection, long startNanos) {
        if (startNanos == 0L) return;
        long sessionStart = startedAtNanos;
        if (sessionStart == 0L || startNanos < sessionStart) return;

        long stop = stoppedAtNanos;
        if (!enabled && stop != 0L && startNanos > stop) return;

        long endNanos = (!enabled && stop != 0L) ? stop : System.nanoTime();
        long nanos = endNanos - startNanos;
        if (nanos >= 0L) recordDuration(subsection, nanos);
    }

    public static void addDuration(TransportSubsection subsection, long nanos) {
        if (enabled && nanos >= 0L) recordDuration(subsection, nanos);
    }

    private static void recordDuration(TransportSubsection subsection, long nanos) {
        int index = subsection.ordinal();
        TRANSPORT_SUB_TOTAL_NANOS[index].add(nanos);
        TRANSPORT_SUB_CALLS[index].increment();
        updateMax(TRANSPORT_SUB_MAX_NANOS[index], nanos);
    }

    public static void increment(Counter counter) {
        if (enabled) COUNTER_VALUES[counter.ordinal()].increment();
    }

    public static void add(Counter counter, long amount) {
        if (enabled && amount != 0L) COUNTER_VALUES[counter.ordinal()].add(amount);
    }

    public static void recordMax(Counter counter, long value) {
        if (enabled && value >= 0L) updateMax(COUNTER_MAX_VALUES[counter.ordinal()], value);
    }

    public static long getMaxCounter(Counter counter) {
        return COUNTER_MAX_VALUES[counter.ordinal()].get();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean hasStarted() {
        return startedAtNanos != 0L;
    }

    public static boolean hasSamples() {
        for (LongAdder calls : CALLS) if (calls.sum() != 0L) return true;
        for (LongAdder calls : TRANSPORT_SUB_CALLS) if (calls.sum() != 0L) return true;
        for (LongAdder counter : COUNTER_VALUES) if (counter.sum() != 0L) return true;
        return false;
    }

    public static synchronized void start() {
        enabled = false;
        clearStats();
        startedAtNanos = System.nanoTime();
        stoppedAtNanos = 0L;
        enabled = true;
    }

    public static synchronized void stop() {
        if (!enabled) return;
        stoppedAtNanos = System.nanoTime();
        enabled = false;
    }

    public static synchronized void reset() {
        boolean wasEnabled = enabled;
        enabled = false;
        clearStats();

        long now = System.nanoTime();
        startedAtNanos = now;
        stoppedAtNanos = wasEnabled ? 0L : now;
        enabled = wasEnabled;
    }

    public static long getCounter(Counter counter) {
        return COUNTER_VALUES[counter.ordinal()].sum();
    }

    public static List<String> buildReportLines() {
        ReportContext context = reportContext();
        List<String> lines = new ArrayList<>(32);
        addHeader(lines, context, false);
        if (!hasStarted()) return finish(lines);

        long blocks = getCounter(Counter.BLOCKS_EXCAVATED);
        long globalTicks = getCounter(Counter.GLOBAL_SERVER_TICKS);
        long excavatorTicks = getCounter(Counter.EXCAVATOR_SERVER_TICKS);
        long renderCalls = getCounter(Counter.RENDER_CALLS);

        lines.add("Server:");
        lines.add("  Average loaded/ticking excavators: " + formatRatio(excavatorTicks, globalTicks));
        lines.add("  Active server time: " + formatSeconds(context.serverActiveSeconds)
                + " (" + formatCount(globalTicks) + " ticks)");
        lines.add("  Excavation rate while ticking: " + formatRate(blocks, context.serverActiveSeconds) + " blocks/s");
        addSummaryTiming(lines, "Excavation tick", Section.EXCAVATION_TICK);
        addSummaryTiming(lines, "Target selection", Section.TARGET_SELECTION);
        addSummaryTiming(lines, "Block removal", Section.BLOCK_REMOVAL);
        addSummaryTiming(lines, "Delivery processing", Section.DELIVERY_PROCESSING);
        if (CALLS[Section.SCANNER.ordinal()].sum() > 0L) {
            addSummaryTiming(lines, "Scanner", Section.SCANNER);
        }

        lines.add("");
        lines.add("Networking:");
        lines.add("  Visual events/batch payload: " + formatRatio(
                getCounter(Counter.VISUAL_EVENTS_SENT), getCounter(Counter.VISUAL_BATCH_PACKETS_SENT)));
        lines.add("  Visual groups/batch payload: " + formatRatio(
                getCounter(Counter.VISUAL_BATCH_EXCAVATOR_GROUPS_SENT), getCounter(Counter.VISUAL_BATCH_PACKETS_SENT)));
        lines.add("  Average visual event queue age: " + formatRatio(
                getCounter(Counter.VISUAL_EVENT_AGE_TICKS_SENT), getCounter(Counter.VISUAL_EVENTS_SENT)) + " ticks");
        lines.add("  Block changes/section payload: " + formatRatio(
                getCounter(Counter.BLOCK_CLIENT_UPDATES_BATCHED), getCounter(Counter.BLOCK_UPDATE_SECTION_PACKETS)));
        long clientDirtyRequests = getCounter(Counter.CLIENT_PRIMARY_SECTION_DIRTY_REQUESTS)
                + getCounter(Counter.CLIENT_NEIGHBOR_SECTION_DIRTY_REQUESTS);
        long clientUniqueDirtyCalls = getCounter(Counter.CLIENT_UNIQUE_SECTION_DIRTY_CALLS);
        lines.add("  Client logical dirty requests / rebuild submissions: "
                + formatCount(clientDirtyRequests) + " / " + formatCount(clientUniqueDirtyCalls));
        lines.add("  Client block changes/section rebuild submission: " + formatRatio(
                getCounter(Counter.CLIENT_BLOCK_UPDATES_APPLIED), clientUniqueDirtyCalls));
        addSummaryTiming(lines, "Block update batching", Section.BLOCK_UPDATE_PACKET_SEND);
        addSummaryTiming(lines, "Client silent apply", Section.CLIENT_BLOCK_UPDATE_APPLY);
        addSummaryTiming(lines, "Client section-dirty flush", Section.CLIENT_SECTION_DIRTY_FLUSH);
        addSummaryTiming(lines, "Client visual ingestion", Section.CLIENT_VISUAL_INGESTION);
        addSummaryTiming(lines, "Transport preparation", Section.CLIENT_VISUAL_TRANSPORT_PREPARATION);

        lines.add("");
        lines.add("Rendering:");
        addSummaryTiming(lines, "Force field", Section.FORCE_FIELD_RENDER);
        addSummaryTiming(lines, "Lasers", Section.LASER_RENDER);
        addSummaryTiming(lines, "Transports", Section.TRANSPORT_RENDER);
        addSummaryTiming(lines, "Primitive frame flush", Section.TRANSPORT_MARKER_FRAME_RENDER);
        lines.add("  Transport visuals/render: " + formatRatio(getCounter(Counter.TRANSPORTS_VISITED), renderCalls));
        lines.add("  Whole-effect excavator cull: " + formatPercent(
                getCounter(Counter.EXCAVATOR_FRUSTUM_CULLED), getCounter(Counter.EXCAVATOR_FRUSTUM_TESTS)));
        lines.add("  Transport-region frustum cull: " + formatPercent(
                getCounter(Counter.TRANSPORT_FRUSTUM_REGIONS_CULLED), getCounter(Counter.TRANSPORT_FRUSTUM_REGION_TESTS)));
        lines.add("  BATCHED transport visual reduction: " + formatReduction(
                getCounter(Counter.BATCH_SOURCE_VISUALS), getCounter(Counter.BATCH_MARKERS_RENDERED)));

        lines.add("");
        lines.add("Correctness:");
        lines.add("  Unloaded laser targets skipped: " + formatCount(getCounter(Counter.LASER_COMPLETIONS_SKIPPED_UNLOADED)));
        lines.add("  Failed block removals retained: " + formatCount(getCounter(Counter.BLOCK_REMOVALS_FAILED_COLUMN_RETAINED)));
        lines.add("  Unloaded storage retries skipped: " + formatCount(getCounter(Counter.STORAGE_RETRIES_SKIPPED_UNLOADED)));
        lines.add("  Detailed developer report: /excavatorprofiler detailed");
        return finish(lines);
    }

    public static List<String> buildDetailedReportLines() {
        ReportContext context = reportContext();
        List<String> lines = new ArrayList<>(96);
        addHeader(lines, context, true);
        if (!hasStarted()) return finish(lines);

        for (Section section : SECTIONS) addDetailedTiming(lines, section);

        lines.add("    Transport breakdown (inside section 12):");
        for (TransportSubsection subsection : TRANSPORT_SUBSECTIONS) {
            int index = subsection.ordinal();
            long calls = TRANSPORT_SUB_CALLS[index].sum();
            long total = TRANSPORT_SUB_TOTAL_NANOS[index].sum();
            long max = TRANSPORT_SUB_MAX_NANOS[index].get();
            long avg = calls == 0L ? 0L : total / calls;
            lines.add(String.format(
                    Locale.ROOT,
                    "       %-31s total %9s | avg %9s | max %9s | calls %,d",
                    subsection.label(), formatNanos(total), formatNanos(avg), formatNanos(max), calls
            ));
        }

        long blocks = getCounter(Counter.BLOCKS_EXCAVATED);
        long globalTicks = getCounter(Counter.GLOBAL_SERVER_TICKS);
        long excavatorTicks = getCounter(Counter.EXCAVATOR_SERVER_TICKS);
        long renderCalls = getCounter(Counter.RENDER_CALLS);
        long clientFrames = getCounter(Counter.CLIENT_FRAMES);
        long scanLookups = getCounter(Counter.SCAN_BLOCK_STATE_LOOKUPS);
        long nextLookups = getCounter(Counter.NEXT_TARGET_STATE_LOOKUPS);

        lines.add("");
        lines.add("Server / networking:");
        lines.add("  Server network debug mode: " + LaserExcavatorConfig.networkDebugMode().name()
                + (LaserExcavatorConfig.hasNetworkDebugModeOverride() ? " (runtime override)" : " (server config)"));
        lines.add("  Average loaded/ticking excavators: " + formatRatio(excavatorTicks, globalTicks));
        lines.add("  Wall-clock interval: " + formatSeconds(context.wallSeconds));
        lines.add("  Active server time: " + formatSeconds(context.serverActiveSeconds)
                + " (" + formatCount(globalTicks) + " ticks)");
        lines.add("  Blocks/sec while ticking: " + formatRate(blocks, context.serverActiveSeconds));
        lines.add("  Scan columns: " + formatCount(getCounter(Counter.SCAN_COLUMNS)));
        lines.add("  Scan lookups/sec while ticking: " + formatRate(scanLookups, context.serverActiveSeconds));
        lines.add("  Next-target lookups/block: " + formatRatio(nextLookups, blocks));
        lines.add("  Shared-height columns created / unique column registrations: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_COLUMNS_CREATED)) + " / "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_REGISTRATIONS)));
        lines.add("  Shared-height owner areas registered / duplicate registrations skipped: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_OWNER_AREAS_REGISTERED)) + " / "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_DUPLICATE_AREA_REGISTRATIONS_SKIPPED)));
        lines.add("  Shared-height owner areas unregistered / reference releases / columns released: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_OWNER_AREAS_UNREGISTERED)) + " / "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_REFERENCE_RELEASES)) + " / "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_COLUMNS_RELEASED)));
        lines.add("  Shared-height net column references registered during interval: "
                + formatCount(Math.max(0L,
                        getCounter(Counter.SHARED_HEIGHT_REGISTRATIONS)
                                - getCounter(Counter.SHARED_HEIGHT_REFERENCE_RELEASES))));
        lines.add("  Shared-height lookups / advances / repairs: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_LOOKUPS)) + " / "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_ADVANCES)) + " / "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_REPAIRS)));
        lines.add("  Shared-height stale local cursors avoided: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_STALE_LOCAL_AVOIDED)));
        lines.add("  Shared-height in-flight redirects: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_INFLIGHT_REDIRECTS)));
        lines.add("  Shared-height selections skipped above local range: "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_OUT_OF_RANGE_SKIPS)));
        lines.add("  Private target selections (filter/fluid-ignore path): "
                + formatCount(getCounter(Counter.SHARED_HEIGHT_PRIVATE_PATH_SELECTIONS)));
        long directRemovalAttempts = getCounter(Counter.DIRECT_CHUNK_REMOVAL_ATTEMPTS);
        long normalRemovalFallbacks = getCounter(Counter.NORMAL_LEVEL_SETBLOCK_FALLBACKS);
        long fastEligibilityLookups = getCounter(Counter.FAST_REMOVAL_ELIGIBILITY_CACHE_HITS)
                + getCounter(Counter.FAST_REMOVAL_ELIGIBILITY_CACHE_MISSES);
        lines.add("  Direct-chunk terrain removal share: "
                + formatPercent(directRemovalAttempts, directRemovalAttempts + normalRemovalFallbacks));
        lines.add("  Direct-chunk attempts / successes / normal fallbacks: "
                + formatCount(directRemovalAttempts) + " / "
                + formatCount(getCounter(Counter.DIRECT_CHUNK_REMOVAL_SUCCESSES)) + " / "
                + formatCount(normalRemovalFallbacks));
        lines.add("  Fast-removal eligibility cache hit share: " + formatPercent(
                getCounter(Counter.FAST_REMOVAL_ELIGIBILITY_CACHE_HITS), fastEligibilityLookups));
        long cachedLoot = getCounter(Counter.DETERMINISTIC_LOOT_CACHE_HITS);
        long normalLoot = getCounter(Counter.NORMAL_LOOT_TABLE_CALLS);
        lines.add("  Deterministic loot cache hit share: " + formatPercent(cachedLoot, cachedLoot + normalLoot));
        lines.add("  Deterministic loot cache hits / misses: "
                + formatCount(cachedLoot) + " / "
                + formatCount(getCounter(Counter.DETERMINISTIC_LOOT_CACHE_MISSES)));
        lines.add("  Deterministic loot learning observations / promotions / rejections: "
                + formatCount(getCounter(Counter.DETERMINISTIC_LOOT_LEARNING_OBSERVATIONS)) + " / "
                + formatCount(getCounter(Counter.DETERMINISTIC_LOOT_CACHE_PROMOTIONS)) + " / "
                + formatCount(getCounter(Counter.DETERMINISTIC_LOOT_CACHE_REJECTIONS)));
        lines.add("  Deterministic loot periodic audits / failures: "
                + formatCount(getCounter(Counter.DETERMINISTIC_LOOT_CACHE_AUDITS)) + " / "
                + formatCount(getCounter(Counter.DETERMINISTIC_LOOT_CACHE_AUDIT_FAILURES)));
        lines.add("  Storage capacity checks: " + formatCount(getCounter(Counter.SINGLE_STACK_RESERVATION_CHECKS)));
        lines.add("  Delivery batches/stacks inserted: "
                + formatCount(getCounter(Counter.DELIVERY_BATCHES_PROCESSED)) + " / "
                + formatCount(getCounter(Counter.DELIVERY_STACKS_INSERTED)));
        lines.add("  Delivery index rebuilds: "
                + formatCount(getCounter(Counter.DELIVERY_INDEX_REBUILDS)));
        lines.add("  Delivery index structural slot updates: "
                + formatCount(getCounter(Counter.DELIVERY_INDEX_SLOT_UPDATES)));
        lines.add("  Delivery indexed matching-slot checks/inserted stack: " + formatRatio(
                getCounter(Counter.DELIVERY_INDEX_ITEM_SLOT_CHECKS),
                getCounter(Counter.DELIVERY_STACKS_INSERTED)));
        lines.add("  Delivery direct empty-slot uses: "
                + formatCount(getCounter(Counter.DELIVERY_INDEX_EMPTY_SLOT_USES)));
        lines.add("  Visual batching ticks / max events: "
                + LaserExcavatorConfig.VISUAL_BATCH_TICKS.get() + " / "
                + LaserExcavatorConfig.VISUAL_BATCH_MAX_EVENTS.get());
        lines.add("  Visual events/batch payload: " + formatRatio(
                getCounter(Counter.VISUAL_EVENTS_SENT), getCounter(Counter.VISUAL_BATCH_PACKETS_SENT)));
        lines.add("  Visual excavator groups/batch payload: " + formatRatio(
                getCounter(Counter.VISUAL_BATCH_EXCAVATOR_GROUPS_SENT), getCounter(Counter.VISUAL_BATCH_PACKETS_SENT)));
        lines.add("  Average visual event queue age: " + formatRatio(
                getCounter(Counter.VISUAL_EVENT_AGE_TICKS_SENT), getCounter(Counter.VISUAL_EVENTS_SENT)) + " ticks");
        lines.add("  Visual chunk flushes interval / event-cap: "
                + formatCount(getCounter(Counter.VISUAL_BATCH_INTERVAL_FLUSHES)) + " / "
                + formatCount(getCounter(Counter.VISUAL_BATCH_CAP_FLUSHES)));
        lines.add("  Laser / transport visual events queued: "
                + formatCount(getCounter(Counter.LASER_VISUAL_EVENTS_QUEUED)) + " / "
                + formatCount(getCounter(Counter.TRANSPORT_VISUAL_EVENTS_QUEUED)));
        lines.add("  Server network-debug laser / transport events suppressed: "
                + formatCount(getCounter(Counter.NETWORK_DEBUG_LASER_EVENTS_SUPPRESSED)) + " / "
                + formatCount(getCounter(Counter.NETWORK_DEBUG_TRANSPORT_EVENTS_SUPPRESSED)));
        lines.add("  Server network-debug already-queued visual events dropped: "
                + formatCount(getCounter(Counter.NETWORK_DEBUG_PENDING_VISUAL_EVENTS_DROPPED)));
        lines.add("  Block changes/section payload: " + formatRatio(
                getCounter(Counter.BLOCK_CLIENT_UPDATES_BATCHED), getCounter(Counter.BLOCK_UPDATE_SECTION_PACKETS)));
        lines.add("  Block section payload transmissions: " + formatCount(
                getCounter(Counter.BLOCK_UPDATE_PACKET_TRANSMISSIONS)));
        lines.add("  Section payload flushes NEAR / MID / FAR / VERY-FAR: "
                + formatCount(getCounter(Counter.BLOCK_UPDATE_NEAR_SECTION_FLUSHES)) + " / "
                + formatCount(getCounter(Counter.BLOCK_UPDATE_MID_SECTION_FLUSHES)) + " / "
                + formatCount(getCounter(Counter.BLOCK_UPDATE_FAR_SECTION_FLUSHES)) + " / "
                + formatCount(getCounter(Counter.BLOCK_UPDATE_VERY_FAR_SECTION_FLUSHES)));
        lines.add("  Section payloads flushed early after becoming quiet: " + formatCount(
                getCounter(Counter.BLOCK_UPDATE_QUIET_SECTION_FLUSHES)));
        lines.add("  Pending section updates dropped - no tracker: " + formatCount(
                getCounter(Counter.BLOCK_UPDATE_NO_TRACKER_DROPS)));
        long clientPrimaryDirty = getCounter(Counter.CLIENT_PRIMARY_SECTION_DIRTY_REQUESTS);
        long clientNeighborDirty = getCounter(Counter.CLIENT_NEIGHBOR_SECTION_DIRTY_REQUESTS);
        long clientDirtyTotal = clientPrimaryDirty + clientNeighborDirty;
        long clientUniqueDirty = getCounter(Counter.CLIENT_UNIQUE_SECTION_DIRTY_CALLS);
        lines.add("  Client silent block removals applied: " + formatCount(
                getCounter(Counter.CLIENT_BLOCK_UPDATES_APPLIED)));
        lines.add("  Client block-sync positions skipped by debug mode: " + formatCount(
                getCounter(Counter.CLIENT_BLOCK_SYNC_POSITIONS_SKIPPED)));
        lines.add("  Client remesh sections suppressed / catch-up queued by debug mode: "
                + formatCount(getCounter(Counter.CLIENT_REMESH_SECTIONS_SUPPRESSED)) + " / "
                + formatCount(getCounter(Counter.CLIENT_REMESH_CATCHUP_SECTIONS)));
        long schedulerTicks = getCounter(Counter.CLIENT_SECTION_DIRTY_SCHEDULER_TICKS);
        long pendingSamples = getCounter(Counter.CLIENT_SECTION_DIRTY_PENDING_SAMPLES);
        lines.add("  Client primary / neighbor logical dirty requests: "
                + formatCount(clientPrimaryDirty) + " / " + formatCount(clientNeighborDirty));
        lines.add("  Client neighbor dirties avoided - adjacent air/unloaded: " + formatCount(
                getCounter(Counter.CLIENT_NEIGHBOR_SECTION_DIRTY_AVOIDED_AIR)));
        lines.add("  Client persistent dirty updates absorbed before rebuild: " + formatCount(
                getCounter(Counter.CLIENT_SECTION_DIRTY_ABSORBED_PENDING)));
        lines.add("  Client unique section rebuild submissions after persistent coalescing: "
                + formatCount(clientUniqueDirty));
        lines.add("  Client rebuild submissions/scheduler tick: "
                + formatRatio(clientUniqueDirty, schedulerTicks));
        lines.add("  Client section refresh policy: chunk-aligned 16^3 sections, 2 ticks <=64 blocks, continuous x^3 to 480 ticks >=320 blocks, no per-tick submission cap");
        lines.add("  Client pending scheduled sections avg / peak: "
                + formatRatio(pendingSamples, schedulerTicks) + " / "
                + formatCount(getMaxCounter(Counter.CLIENT_SECTION_DIRTY_PENDING_PEAK)));
        lines.add("  Client refresh wheel entries / due / stale: "
                + formatCount(getCounter(Counter.CLIENT_SECTION_REFRESH_WHEEL_ENTRIES)) + " / "
                + formatCount(getCounter(Counter.CLIENT_SECTION_REFRESH_DUE_ENTRIES)) + " / "
                + formatCount(getCounter(Counter.CLIENT_SECTION_REFRESH_STALE_WHEEL_ENTRIES)));
        lines.add("  Client refresh promotions after distance change: "
                + formatCount(getCounter(Counter.CLIENT_SECTION_REFRESH_PROMOTIONS)));
        lines.add("  Client submitted scheduled interval avg: "
                + formatRatio(getCounter(Counter.CLIENT_SECTION_REFRESH_INTERVAL_TICKS_TOTAL), clientUniqueDirty)
                + " ticks");
        lines.add("  Client submitted section dirty age avg / max: "
                + formatRatio(getCounter(Counter.CLIENT_SECTION_DIRTY_AGE_TICKS_TOTAL), clientUniqueDirty)
                + " / " + formatCount(getMaxCounter(Counter.CLIENT_SECTION_DIRTY_AGE_TICKS_MAX)) + " ticks");
        lines.add("  Client section dirty calls skipped - chunk unloaded: " + formatCount(
                getCounter(Counter.CLIENT_SECTION_DIRTY_SKIPPED_UNLOADED)));
        lines.add("  Client scheduler dirty-request reduction: " + formatReduction(
                clientDirtyTotal, clientUniqueDirty));
        lines.add("  Client block changes/section rebuild submission: " + formatRatio(
                getCounter(Counter.CLIENT_BLOCK_UPDATES_APPLIED), clientUniqueDirty));
        lines.add("  Client total render-dirty coalescing vs per-block: " + formatReduction(
                getCounter(Counter.CLIENT_BLOCK_UPDATES_APPLIED), clientUniqueDirty));

        lines.add("");
        lines.add("Client visual ingestion:");
        long visualPayloadsReceived = getCounter(Counter.CLIENT_VISUAL_PAYLOADS_RECEIVED);
        long visualPayloadsDrained = getCounter(Counter.CLIENT_VISUAL_PAYLOADS_DRAINED);
        long visualEventsReceived = getCounter(Counter.CLIENT_VISUAL_EVENTS_RECEIVED);
        long visualEventsDrained = getCounter(Counter.CLIENT_VISUAL_EVENTS_DRAINED);
        long visualQueueDrains = getCounter(Counter.CLIENT_VISUAL_QUEUE_DRAINS);
        long blockTextureLookups = getCounter(Counter.CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_HITS)
                + getCounter(Counter.CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_MISSES);
        long groupLightLookups = getCounter(Counter.CLIENT_VISUAL_REGION_LIGHT_CACHE_HITS)
                + getCounter(Counter.CLIENT_VISUAL_REGION_LIGHT_CACHE_MISSES);
        long laserSlotAttempts = getCounter(Counter.CLIENT_VISUAL_LASER_POOL_REUSES)
                + getCounter(Counter.CLIENT_VISUAL_LASER_ALLOCATIONS);
        long transportSlotAttempts = getCounter(Counter.CLIENT_VISUAL_TRANSPORT_POOL_REUSES)
                + getCounter(Counter.CLIENT_VISUAL_TRANSPORT_ALLOCATIONS);
        lines.add("  Configured ingestion budget: "
                + LaserExcavatorClientConfig.VISUAL_INGESTION_BUDGET_MICROS.get() + " us/client tick");
        lines.add("  Payloads received / drained: "
                + formatCount(visualPayloadsReceived) + " / " + formatCount(visualPayloadsDrained));
        lines.add("  Events received / drained: "
                + formatCount(visualEventsReceived) + " / " + formatCount(visualEventsDrained));
        lines.add("  Excavator groups received / drained: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_GROUPS_RECEIVED)) + " / "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_GROUPS_DRAINED)));
        lines.add("  Queue drains / payloads per drain / events per drain: "
                + formatCount(visualQueueDrains) + " / "
                + formatRatio(visualPayloadsDrained, visualQueueDrains) + " / "
                + formatRatio(visualEventsDrained, visualQueueDrains));
        lines.add("  Ingestion budget hits / avg pending payloads after drain: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_INGESTION_BUDGET_HITS)) + " / "
                + formatRatio(getCounter(Counter.CLIENT_VISUAL_PENDING_PAYLOADS_AFTER_DRAIN), visualQueueDrains));
        lines.add("  Laser / transport visuals materialized: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_LASERS_MATERIALIZED)) + " / "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_TRANSPORTS_MATERIALIZED)));
        lines.add("  Events expired before materialization: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_EVENTS_EXPIRED)));
        lines.add("  Laser capped-ring evictions: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_RING_EVICTIONS)));
        lines.add("  Transport visuals skipped at render limit: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_TRANSPORT_LIMIT_REJECTIONS)));
        lines.add("  Laser / transport reusable-slot hit share: "
                + formatPercent(getCounter(Counter.CLIENT_VISUAL_LASER_POOL_REUSES), laserSlotAttempts) + " / "
                + formatPercent(getCounter(Counter.CLIENT_VISUAL_TRANSPORT_POOL_REUSES), transportSlotAttempts));
        lines.add("  Laser / transport new slot allocations: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_LASER_ALLOCATIONS)) + " / "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_TRANSPORT_ALLOCATIONS)));
        lines.add("  Block texture cache hit share: " + formatPercent(
                getCounter(Counter.CLIENT_VISUAL_BLOCK_TEXTURE_CACHE_HITS), blockTextureLookups));
        lines.add("  Non-block item texture resolves: "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_ITEM_TEXTURE_RESOLVES)));
        lines.add("  Spatial-region light cache hit share / actual world light lookups: "
                + formatPercent(getCounter(Counter.CLIENT_VISUAL_REGION_LIGHT_CACHE_HITS), groupLightLookups) + " / "
                + formatCount(getCounter(Counter.CLIENT_VISUAL_LIGHT_LOOKUPS)));
        lines.add("");
        lines.add("Correctness:");
        lines.add("  Laser completions skipped - target chunk unloaded: "
                + formatCount(getCounter(Counter.LASER_COMPLETIONS_SKIPPED_UNLOADED)));
        lines.add("  Block removals failed - column retained: "
                + formatCount(getCounter(Counter.BLOCK_REMOVALS_FAILED_COLUMN_RETAINED)));
        lines.add("  Storage retries skipped - target chunk unloaded: "
                + formatCount(getCounter(Counter.STORAGE_RETRIES_SKIPPED_UNLOADED)));

        long fullFields = getCounter(Counter.FORCE_FIELDS_FULL_GEOMETRY);
        long midFields = getCounter(Counter.FORCE_FIELDS_MID_GEOMETRY);
        long farFields = getCounter(Counter.FORCE_FIELDS_FAR_GEOMETRY);
        long fields = fullFields + midFields + farFields;
        long fullLasers = getCounter(Counter.LASERS_FULL_RENDERED);
        long midLasers = getCounter(Counter.LASERS_MID_RENDERED);
        long farLasers = getCounter(Counter.LASERS_FAR_RENDERED);
        long midLasersSkipped = getCounter(Counter.LASERS_MID_DENSITY_SKIPPED);
        long farLasersSkipped = getCounter(Counter.LASERS_FAR_DENSITY_SKIPPED);
        long midLaserCandidates = midLasers + midLasersSkipped;
        long farLaserCandidates = farLasers + farLasersSkipped;
        long laserCandidates = fullLasers + midLaserCandidates + farLaserCandidates;
        long renderedLasers = fullLasers + midLasers + farLasers;

        lines.add("");
        lines.add("LOD / culling:");
        lines.add("  Whole-effect excavator cull share: " + formatPercent(
                getCounter(Counter.EXCAVATOR_FRUSTUM_CULLED), getCounter(Counter.EXCAVATOR_FRUSTUM_TESTS)));
        lines.add("  Force-field FULL / MID / FAR: "
                + formatPercent(fullFields, fields) + " / "
                + formatPercent(midFields, fields) + " / "
                + formatPercent(farFields, fields));
        lines.add("  Laser FULL / MID / FAR (classified): "
                + formatPercent(fullLasers, laserCandidates) + " / "
                + formatPercent(midLaserCandidates, laserCandidates) + " / "
                + formatPercent(farLaserCandidates, laserCandidates));
        lines.add("  Laser MID / FAR density skipped: "
                + formatPercent(midLasersSkipped, midLaserCandidates) + " / "
                + formatPercent(farLasersSkipped, farLaserCandidates));
        lines.add("  Laser source / rendered visuals per render call: "
                + formatRatio(laserCandidates, renderCalls) + " / "
                + formatRatio(renderedLasers, renderCalls));
        lines.add("  Laser center-FAR fast-path share: " + formatPercent(
                getCounter(Counter.LASER_CENTER_FAR_FAST_PATH_HITS),
                getCounter(Counter.LASER_CENTER_FAR_FAST_PATH_TESTS)));
        long frustumTests = getCounter(Counter.TRANSPORT_FRUSTUM_REGION_TESTS);
        long frustumCulled = getCounter(Counter.TRANSPORT_FRUSTUM_REGIONS_CULLED);
        lines.add("  Transport-region frustum cull share: " + formatPercent(frustumCulled, frustumTests));
        lines.add("  Transport members skipped by region culling/render: " + formatRatio(
                getCounter(Counter.TRANSPORT_FRUSTUM_MEMBERS_CULLED), renderCalls));

        long regionTickPasses = getCounter(Counter.TRANSPORT_REGION_TICK_PASSES);
        long regionPotentialScans = getCounter(Counter.TRANSPORT_REGION_POTENTIAL_SCAN_VISITS);
        long regionWheelBuckets = getCounter(Counter.TRANSPORT_REGION_WHEEL_BUCKETS_VISITED);
        long regionWheelEntries = getCounter(Counter.TRANSPORT_REGION_WHEEL_ENTRIES_VISITED);
        long regionWheelFutureSkips = getCounter(Counter.TRANSPORT_REGION_WHEEL_FUTURE_SKIPS);
        long regionMembershipChecks = getCounter(Counter.TRANSPORT_REGION_MEMBERSHIP_CHECKS);
        long regionMigrations = getCounter(Counter.TRANSPORT_REGION_MIGRATIONS);
        long regionLodTests = getCounter(Counter.TRANSPORT_REGION_LOD_TESTS);
        lines.add("");
        lines.add("Transport cache / current spatial regions:");
        lines.add("  Spatial region size: 16 x 16 x 16 blocks (predicted-crossing timing wheel)");
        lines.add("  Region timing-wheel passes/wall-sec: " + formatRate(regionTickPasses, context.wallSeconds));
        lines.add("  Timing-wheel buckets visited/wall-sec: " + formatRate(regionWheelBuckets, context.wallSeconds));
        lines.add("  Timing-wheel entries visited/wall-sec: " + formatRate(regionWheelEntries, context.wallSeconds));
        lines.add("  Future-rotation wheel skips/wall-sec: " + formatRate(regionWheelFutureSkips, context.wallSeconds));
        lines.add("  Full-scan member visits avoided/wall-sec / share: "
                + formatRate(Math.max(0L, regionPotentialScans - regionMembershipChecks), context.wallSeconds) + " / "
                + formatPercent(Math.max(0L, regionPotentialScans - regionMembershipChecks), regionPotentialScans));
        lines.add("  Due exact membership checks/wall-sec: " + formatRate(regionMembershipChecks, context.wallSeconds));
        lines.add("  Region migrations/wall-sec / share of due checks: "
                + formatRate(regionMigrations, context.wallSeconds) + " / "
                + formatPercent(regionMigrations, regionMembershipChecks));
        long positionAvg = transportSubAverageNanos(TransportSubsection.REGION_POSITION_SAMPLE);
        long keyAvg = transportSubAverageNanos(TransportSubsection.REGION_KEY_SAMPLE);
        long moveAvg = transportSubAverageNanos(TransportSubsection.REGION_MOVE_SAMPLE);
        lines.add("  Sampled avg position / region key+compare / migration: "
                + formatNanos(positionAvg) + " / " + formatNanos(keyAvg) + " / "
                + formatNanos(moveAvg));
        long membershipNanos = TRANSPORT_SUB_TOTAL_NANOS[TransportSubsection.REGION_MEMBERSHIP.ordinal()].sum();
        long spatialLodNanos = TRANSPORT_SUB_TOTAL_NANOS[TransportSubsection.SPATIAL_REGION_LOD.ordinal()].sum();
        lines.add("  Region membership update CPU/frame: "
                + formatPerItemNanos(membershipNanos, clientFrames));
        lines.add("  Spatial-region LOD tests/wall-sec: " + formatRate(regionLodTests, context.wallSeconds));
        lines.add("  Spatial-region LOD passes: " + formatCount(getCounter(Counter.TRANSPORT_REGION_LOD_PASSES)));
        lines.add("  Spatial-region LOD CPU/frame: "
                + formatPerItemNanos(spatialLodNanos, clientFrames));
        lines.add("  Membership policy: 128-slot O(1) timing wheel scheduled by next predicted 16-block crossing");
        lines.add("  LOD policy: nearest point of padded current-position region; INDIVIDUAL/BATCHED/HIDDEN");
        lines.add("  Cleanup budget hits: " + formatCount(getCounter(Counter.TRANSPORT_CLEANUP_BUDGET_HITS)));
        lines.add("  Transport animation backward-time clamps: "
                + formatCount(getCounter(Counter.TRANSPORT_ANIMATION_BACKWARD_TIME_CLAMPS)));
        lines.add("  Cache additions / removals: "
                + formatCount(getCounter(Counter.TRANSPORT_CACHE_ADDITIONS)) + " / "
                + formatCount(getCounter(Counter.TRANSPORT_CACHE_REMOVALS)));
        lines.add("  Spatial regions created / removed: "
                + formatCount(getCounter(Counter.TRANSPORT_REGION_ADDITIONS)) + " / "
                + formatCount(getCounter(Counter.TRANSPORT_REGION_REMOVALS)));
        long activeTransports = getCounter(Counter.TRANSPORT_ACTIVE_TRANSPORT_SAMPLES);
        long activeGroups = getCounter(Counter.TRANSPORT_ACTIVE_REGION_SAMPLES);
        lines.add("  Average active transports/spatial region: " + formatRatio(activeTransports, activeGroups));
        lines.add("  Active spatial regions/render call: " + formatRatio(activeGroups, renderCalls));
        lines.add("  Regions/render INDIVIDUAL / BATCHED / HIDDEN: "
                + formatRatio(getCounter(Counter.TRANSPORT_INDIVIDUAL_REGIONS_VISITED), renderCalls) + " / "
                + formatRatio(getCounter(Counter.TRANSPORT_BATCHED_REGIONS_VISITED), renderCalls) + " / "
                + formatRatio(getCounter(Counter.TRANSPORT_HIDDEN_REGIONS), renderCalls));
        lines.add("  Transport visuals hidden by region max distance/render call: " + formatRatio(
                getCounter(Counter.TRANSPORT_HIDDEN_SOURCE_VISUALS), renderCalls));
        lines.add("  Whole-effect HIDDEN fast rejects / render call: " + formatRatio(
                getCounter(Counter.TRANSPORT_HIDDEN_WHOLE_EFFECT_REJECTS), renderCalls));
        lines.add("  Raw visuals skipped by whole-effect HIDDEN fast path: " + formatCount(
                getCounter(Counter.TRANSPORT_HIDDEN_WHOLE_EFFECT_VISUALS_SKIPPED)));
        lines.add("  All-regions-HIDDEN early returns / render call: " + formatRatio(
                getCounter(Counter.TRANSPORT_HIDDEN_ALL_REGIONS_EARLY_RETURNS), renderCalls));
        lines.add("  Cached visuals skipped by all-regions-HIDDEN early return: " + formatCount(
                getCounter(Counter.TRANSPORT_HIDDEN_ALL_REGIONS_VISUALS_SKIPPED)));
        long individualNanos = TRANSPORT_SUB_TOTAL_NANOS[TransportSubsection.INDIVIDUAL_TRANSPORTS.ordinal()].sum();
        long cubeNanos = TRANSPORT_SUB_TOTAL_NANOS[TransportSubsection.BLOCK_CUBE_RENDERING.ordinal()].sum();
        long billboardNanos = TRANSPORT_SUB_TOTAL_NANOS[TransportSubsection.BLOCK_BILLBOARD_RENDERING.ordinal()].sum();
        long batchNanos = TRANSPORT_SUB_TOTAL_NANOS[TransportSubsection.BATCHED_MARKERS.ordinal()].sum();
        long cubes = getCounter(Counter.BLOCK_CUBES_RENDERED);
        long billboards = getCounter(Counter.BLOCK_BILLBOARDS_RENDERED);
        long texturedBlocks = cubes + billboards;
        long individualMarkers = getCounter(Counter.INDIVIDUAL_MARKERS_RENDERED);
        long itemBillboards = getCounter(Counter.NON_BLOCK_SPRITES_RENDERED);
        long individualRepresentations = texturedBlocks + individualMarkers + itemBillboards;
        long batchSources = getCounter(Counter.BATCH_SOURCE_VISUALS);
        long batchPrimitives = getCounter(Counter.BATCH_MARKERS_RENDERED);

        lines.add("");
        lines.add("Transport representation:");
        lines.add("  Transport visuals/render call: " + formatRatio(getCounter(Counter.TRANSPORTS_VISITED), renderCalls));
        lines.add("  INDIVIDUAL CPU/represented transport: " + formatPerItemNanos(individualNanos, individualRepresentations));
        lines.add("  Textured cube sampled CPU/item: " + formatPerItemNanos(
                cubeNanos, TRANSPORT_SUB_CALLS[TransportSubsection.BLOCK_CUBE_RENDERING.ordinal()].sum()));
        lines.add("  Textured block billboard sampled CPU/item: " + formatPerItemNanos(
                billboardNanos, TRANSPORT_SUB_CALLS[TransportSubsection.BLOCK_BILLBOARD_RENDERING.ordinal()].sum()));
        lines.add("  Textured block cube / billboard share: "
                + formatPercent(cubes, texturedBlocks) + " / " + formatPercent(billboards, texturedBlocks));
        lines.add("  Individual transport markers: " + formatCount(individualMarkers));
        lines.add("  Individual non-block billboards: " + formatCount(itemBillboards));
        lines.add("  BATCHED CPU/queued primitive: " + formatPerItemNanos(batchNanos, batchPrimitives));
        lines.add("  BATCHED source visuals/primitive: " + formatRatio(batchSources, batchPrimitives)
                + " | reduction " + formatReduction(batchSources, batchPrimitives));
        lines.add("  Average continuous batch target: " + formatRatio(
                getCounter(Counter.BATCH_TARGET_MARKERS_TOTAL), getCounter(Counter.BATCH_TARGET_MARKER_SAMPLES)));

        long emitted = getCounter(Counter.TRANSPORT_MARKER_PRIMITIVES_EMITTED);
        long flushes = getCounter(Counter.TRANSPORT_MARKER_BATCH_FLUSHES);
        long largeMarkers = getCounter(Counter.LARGE_TRANSPORT_MARKERS_EMITTED);
        long smallMarkers = getCounter(Counter.SMALL_TRANSPORT_MARKERS_EMITTED);
        long totalVertices = (largeMarkers + smallMarkers) * 4L;
        lines.add("");
        lines.add("Primitive renderer:");
        lines.add("  Frame-wide primitives/flush: " + formatRatio(emitted, flushes));
        lines.add("  Whole frame primitive flush CPU/primitive: " + formatPerItemNanos(
                TOTAL_NANOS[Section.TRANSPORT_MARKER_FRAME_RENDER.ordinal()].sum(), emitted));
        lines.add("  Large marker emission CPU/primitive: " + formatPerItemNanos(
                TOTAL_NANOS[Section.LARGE_TRANSPORT_MARKER_RENDER.ordinal()].sum(), largeMarkers));
        lines.add("  Small marker emission CPU/primitive: " + formatPerItemNanos(
                TOTAL_NANOS[Section.SMALL_TRANSPORT_MARKER_RENDER.ordinal()].sum(), smallMarkers));
        lines.add("  GPU marker vertices/flush: " + formatRatio(totalVertices, flushes));

        return finish(lines);
    }

    private static ReportContext reportContext() {
        long now = System.nanoTime();
        long start = startedAtNanos;
        long end = enabled ? now : stoppedAtNanos;
        double wallSeconds = start == 0L ? 0.0 : Math.max(0L, end - start) / 1_000_000_000.0;
        double serverActiveSeconds = getCounter(Counter.GLOBAL_SERVER_TICKS) / SERVER_TICKS_PER_SECOND;
        return new ReportContext(wallSeconds, serverActiveSeconds);
    }

    private static void addHeader(List<String> lines, ReportContext context, boolean detailed) {
        lines.add("========== Excavator Profiler" + (detailed ? " - Detailed" : "") + " ==========");
        lines.add("Implementation: " + IMPLEMENTATION_VERSION);
        lines.add("Status: " + (enabled ? "RUNNING" : "STOPPED")
                + " | interval: " + String.format(Locale.ROOT, "%.2f s", context.wallSeconds));
        long clientFrames = getCounter(Counter.CLIENT_FRAMES);
        lines.add("Client frames / average FPS: " + formatCount(clientFrames) + " / "
                + formatRate(clientFrames, context.wallSeconds));
        if (!hasStarted()) {
            lines.add("No profiling session has been started. Run /excavatorprofiler start first.");
        } else if (!hasSamples()) {
            lines.add("No samples recorded yet.");
        }
        if (detailed) lines.add("Timings overlap by design; do not sum the top-level totals.");
        lines.add("");
    }

    private static List<String> finish(List<String> lines) {
        lines.add("========================================");
        return lines;
    }

    private static void addSummaryTiming(List<String> lines, String label, Section section) {
        int index = section.ordinal();
        long calls = CALLS[index].sum();
        long total = TOTAL_NANOS[index].sum();
        long avg = calls == 0L ? 0L : total / calls;
        lines.add(String.format(
                Locale.ROOT,
                "  %-22s avg %9s | max %9s",
                label + ":", formatNanos(avg), formatNanos(MAX_NANOS[index].get())
        ));
    }

    private static void addDetailedTiming(List<String> lines, Section section) {
        int index = section.ordinal();
        long calls = CALLS[index].sum();
        long total = TOTAL_NANOS[index].sum();
        long avg = calls == 0L ? 0L : total / calls;
        lines.add(String.format(
                Locale.ROOT,
                "[%s] %-34s total %9s | avg %9s | max %9s | calls %,d",
                section.side().shortName(), section.label(), formatNanos(total),
                formatNanos(avg), formatNanos(MAX_NANOS[index].get()), calls
        ));
    }

    private static long transportSubAverageNanos(TransportSubsection subsection) {
        int index = subsection.ordinal();
        long calls = TRANSPORT_SUB_CALLS[index].sum();
        return calls == 0L ? 0L : TRANSPORT_SUB_TOTAL_NANOS[index].sum() / calls;
    }

    private static String formatNanos(long nanos) {
        double ms = nanos / 1_000_000.0;
        if (ms >= 1000.0) return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
        if (ms >= 1.0) return String.format(Locale.ROOT, "%.3f ms", ms);
        if (nanos >= 1_000L) return String.format(Locale.ROOT, "%.3f us", nanos / 1_000.0);
        return nanos + " ns";
    }

    private static String formatCount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.2f s", Math.max(0.0, seconds));
    }

    private static String formatRate(long value, double seconds) {
        if (seconds <= 0.0) return "n/a";
        return String.format(Locale.ROOT, "%.2f", value / seconds);
    }

    private static String formatRatio(long numerator, long denominator) {
        if (denominator <= 0L) return "n/a";
        return String.format(Locale.ROOT, "%.2f", (double) numerator / denominator);
    }

    private static String formatPercent(long numerator, long denominator) {
        if (denominator <= 0L) return "n/a";
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * numerator / denominator);
    }

    private static String formatReduction(long sourceCount, long renderedCount) {
        if (sourceCount <= 0L) return "n/a";
        double reduction = 100.0 * Math.max(0L, sourceCount - renderedCount) / sourceCount;
        return String.format(Locale.ROOT, "%.1f%%", reduction);
    }

    private static String formatPerItemNanos(long totalNanos, long itemCount) {
        if (itemCount <= 0L) return "n/a";
        return formatNanos(totalNanos / itemCount);
    }

    private static void clearStats() {
        for (LongAdder adder : TOTAL_NANOS) adder.reset();
        for (LongAdder adder : CALLS) adder.reset();
        for (AtomicLong max : MAX_NANOS) max.set(0L);
        for (LongAdder adder : TRANSPORT_SUB_TOTAL_NANOS) adder.reset();
        for (LongAdder adder : TRANSPORT_SUB_CALLS) adder.reset();
        for (AtomicLong max : TRANSPORT_SUB_MAX_NANOS) max.set(0L);
        for (LongAdder counter : COUNTER_VALUES) counter.reset();
        for (AtomicLong max : COUNTER_MAX_VALUES) max.set(0L);
    }

    private static void updateMax(AtomicLong target, long value) {
        long current = target.get();
        while (value > current && !target.compareAndSet(current, value)) {
            current = target.get();
        }
    }

    private static LongAdder[] createAdders(int count) {
        LongAdder[] result = new LongAdder[count];
        for (int i = 0; i < count; i++) result[i] = new LongAdder();
        return result;
    }

    private static AtomicLong[] createAtomicLongs(int count) {
        AtomicLong[] result = new AtomicLong[count];
        for (int i = 0; i < count; i++) result[i] = new AtomicLong();
        return result;
    }

    private record ReportContext(double wallSeconds, double serverActiveSeconds) {}
}
