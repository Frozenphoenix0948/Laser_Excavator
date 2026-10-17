package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.network.excavator.ExcavatorMultiSectionBlockUpdatePayload;
import de.balto.laserexcavator.network.excavator.ExcavatorSectionBlockUpdatePayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Queues immediate block removals for delayed, per-player section synchronization. */
public final class ExcavatorBlockSyncBatcher {
    private static final int NEAR_DELAY_TICKS = 5;
    private static final int MID_DELAY_TICKS = 10;
    private static final int FAR_DELAY_TICKS = 20;
    private static final int VERY_FAR_DELAY_TICKS = 60;

    // Optionally flush mid-distance sections shortly after excavation becomes quiet.
    private static final int QUIET_FLUSH_MIN_BATCH = 8;
    private static final int QUIET_FLUSH_AFTER_TICKS = 3;
    private static final int QUIET_FLUSH_MIN_AGE_TICKS = 5;

    private static final double NEAR_DISTANCE_SQ = 48.0 * 48.0;
    private static final double MID_DISTANCE_SQ = 96.0 * 96.0;
    private static final double FAR_DISTANCE_SQ = 160.0 * 160.0;

    // The maximum delay is 59 ticks; intrusive entries allow O(1) rescheduling.
    private static final int WHEEL_SIZE = 64;
    private static final int WHEEL_MASK = WHEEL_SIZE - 1;

    private static final Map<ServerLevel, PendingLevel> PENDING_SECTIONS_BY_LEVEL =
            new IdentityHashMap<>();

    private ExcavatorBlockSyncBatcher() {}

    public static void queueRemoval(ServerLevel level, BlockPos pos) {
        if (LaserExcavatorConfig.suppressBlockUpdateNetworkPayloads()) {
            return;
        }

        long gameTime = level.getGameTime();
        PendingLevel pendingLevel = PENDING_SECTIONS_BY_LEVEL.computeIfAbsent(
                level, ignored -> new PendingLevel(gameTime));
        long sectionKey = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        PendingSection pending = pendingLevel.sections.get(sectionKey);
        if (pending == null) {
            pending = new PendingSection(sectionKey, gameTime);
            pendingLevel.sections.put(sectionKey, pending);
            pendingLevel.schedule(pending, gameTime + NEAR_DELAY_TICKS - 1L);
        }
        pending.add(SectionPos.sectionRelativePos(pos));
        pending.lastQueuedGameTime = gameTime;

        // Only eligible MID sections need rescheduling when additions arrive.
        if (pending.delayTicks == MID_DELAY_TICKS && pending.size >= QUIET_FLUSH_MIN_BATCH) {
            pendingLevel.schedule(pending, nextFlushGameTime(pending));
        }
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_CLIENT_UPDATES_BATCHED);
    }

    public static void clearPendingSections() {
        PENDING_SECTIONS_BY_LEVEL.clear();
    }

    public static void flushDueSections(MinecraftServer server) {
        if (LaserExcavatorConfig.suppressBlockUpdateNetworkPayloads()) {
            PENDING_SECTIONS_BY_LEVEL.clear();
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            PendingLevel pendingLevel = PENDING_SECTIONS_BY_LEVEL.get(level);
            if (pendingLevel == null) continue;

            long gameTime = level.getGameTime();
            long elapsed = gameTime - pendingLevel.lastProcessedGameTime;
            ServerChunkCache chunkSource = level.getChunkSource();
            Map<ServerPlayer, OutgoingSections> outgoing = new IdentityHashMap<>();
            if (elapsed < 0 || elapsed >= WHEEL_SIZE) {
                // Revisit all buckets after a time jump or a long tick gap.
                for (int slot = 0; slot < WHEEL_SIZE; slot++) {
                    flushWheelSlot(level, chunkSource, pendingLevel, gameTime, slot, outgoing);
                }
            } else {
                // Process only elapsed wheel slots.
                for (long tick = pendingLevel.lastProcessedGameTime + 1L; tick <= gameTime; tick++) {
                    flushWheelSlot(level, chunkSource, pendingLevel, gameTime, (int) (tick & WHEEL_MASK), outgoing);
                }
            }
            pendingLevel.lastProcessedGameTime = gameTime;
            for (Map.Entry<ServerPlayer, OutgoingSections> entry : outgoing.entrySet()) {
                entry.getValue().flush(entry.getKey());
            }
            if (pendingLevel.sections.isEmpty()) PENDING_SECTIONS_BY_LEVEL.remove(level);
        }
    }

    private static void flushWheelSlot(
            ServerLevel level,
            ServerChunkCache chunkSource,
            PendingLevel pendingLevel,
            long gameTime,
            int slot,
            Map<ServerPlayer, OutgoingSections> outgoing
    ) {
        PendingSection pending = pendingLevel.wheel[slot];
        while (pending != null) {
            PendingSection next = pending.nextScheduled;
            // Exact game-time check protects against wheel wraparound and time jumps.
            if (pending.dueGameTime <= gameTime) {
                pendingLevel.unlink(pending);
                processDueSection(level, chunkSource, pendingLevel, pending, gameTime, outgoing);
            }
            pending = next;
        }
    }

    private static void processDueSection(
            ServerLevel level,
            ServerChunkCache chunkSource,
            PendingLevel pendingLevel,
            PendingSection pending,
            long gameTime,
            Map<ServerPlayer, OutgoingSections> outgoing
    ) {
        long ageTicks = Math.max(0L, gameTime - pending.firstQueuedGameTime);
        if (ageTicks < NEAR_DELAY_TICKS - 1L) {
            pendingLevel.schedule(pending, pending.firstQueuedGameTime + NEAR_DELAY_TICKS - 1L);
            return;
        }

        ChunkPos chunkPos = new ChunkPos(SectionPos.x(pending.sectionKey), SectionPos.z(pending.sectionKey));
        List<ServerPlayer> players = chunkSource.chunkMap.getPlayers(chunkPos, false);
        if (players.isEmpty()) {
            // Newly tracking players will receive the authoritative chunk.
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_NO_TRACKER_DROPS);
            pendingLevel.sections.remove(pending.sectionKey);
            return;
        }

        if (pending.delayTicks == 0) {
            // Select distance once, at the same first eligibility time as before.
            pending.delayTicks = chooseDelayTicks(pending.sectionKey, players);
        }

        boolean ageDue = ageTicks >= pending.delayTicks - 1L;
        boolean quietDue = pending.delayTicks <= MID_DELAY_TICKS
                && pending.size >= QUIET_FLUSH_MIN_BATCH
                && ageTicks >= QUIET_FLUSH_MIN_AGE_TICKS - 1L
                && gameTime - pending.lastQueuedGameTime >= QUIET_FLUSH_AFTER_TICKS;
        if (!ageDue && !quietDue) {
            pendingLevel.schedule(pending, nextFlushGameTime(pending));
            return;
        }

        if (quietDue && !ageDue) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_QUIET_SECTION_FLUSHES);
        }
        queueSectionPayload(level, pending.sectionKey, pending, players, outgoing);
        pendingLevel.sections.remove(pending.sectionKey);
    }

    private static long nextFlushGameTime(PendingSection pending) {
        long hardDue = pending.firstQueuedGameTime + pending.delayTicks - 1L;
        // FAR/VERY-FAR deliberately keep their full accumulation window.
        if (pending.delayTicks != MID_DELAY_TICKS || pending.size < QUIET_FLUSH_MIN_BATCH) {
            return hardDue;
        }
        long quietDue = Math.max(pending.firstQueuedGameTime + QUIET_FLUSH_MIN_AGE_TICKS - 1L,
                pending.lastQueuedGameTime + QUIET_FLUSH_AFTER_TICKS);
        return Math.min(hardDue, quietDue);
    }

    private static int chooseDelayTicks(long sectionKey, List<ServerPlayer> players) {
        double centerX = (SectionPos.x(sectionKey) << 4) + 8.0;
        double centerY = (SectionPos.y(sectionKey) << 4) + 8.0;
        double centerZ = (SectionPos.z(sectionKey) << 4) + 8.0;
        double nearestSq = Double.POSITIVE_INFINITY;

        for (ServerPlayer player : players) {
            double dx = player.getX() - centerX;
            double dy = player.getY() - centerY;
            double dz = player.getZ() - centerZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < nearestSq) nearestSq = distanceSq;
        }

        if (nearestSq <= NEAR_DISTANCE_SQ) return NEAR_DELAY_TICKS;
        if (nearestSq <= MID_DISTANCE_SQ) return MID_DELAY_TICKS;
        if (nearestSq <= FAR_DISTANCE_SQ) return FAR_DELAY_TICKS;
        return VERY_FAR_DELAY_TICKS;
    }

    private static void queueSectionPayload(
            ServerLevel level,
            long sectionKey,
            PendingSection pending,
            List<ServerPlayer> players,
            Map<ServerPlayer, OutgoingSections> outgoing
    ) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_UPDATE_PACKET_SEND);
        try {
            int sectionX = SectionPos.x(sectionKey);
            int sectionY = SectionPos.y(sectionKey);
            int sectionZ = SectionPos.z(sectionKey);
            LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
            if (chunk == null) return;

            // Prevent delayed removals from overwriting newer blocks.
            int sectionIndex = chunk.getSectionIndex(sectionY << 4);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;
            LevelChunkSection section = chunk.getSection(sectionIndex);
            short[] localPositions = pending.positions;
            int count = pending.size;
            if (!section.hasOnlyAir()) {
                int validCount = 0;
                for (int i = 0; i < count; i++) {
                    short packed = localPositions[i];
                    if (section.getBlockState(
                            SectionPos.sectionRelativeX(packed),
                            SectionPos.sectionRelativeY(packed),
                            SectionPos.sectionRelativeZ(packed)
                    ).isAir()) localPositions[validCount++] = packed;
                }
                if (validCount == 0) return;
                count = validCount;
            }
            // Split oversized batches; reuse the array only when its size matches exactly.
            int offset = 0;
            while (offset < count) {
                int length = Math.min(ExcavatorSectionBlockUpdatePayload.MAX_POSITIONS, count - offset);
                short[] packetPositions = offset == 0 && length == localPositions.length
                        ? localPositions
                        : Arrays.copyOfRange(localPositions, offset, offset + length);
                offset += length;

                ExcavatorSectionBlockUpdatePayload payload = new ExcavatorSectionBlockUpdatePayload(
                        sectionX, sectionY, sectionZ, packetPositions
                );
                for (ServerPlayer player : players) {
                    outgoing.computeIfAbsent(player, ignored -> new OutgoingSections()).add(player, payload);
                }
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_SECTION_PACKETS);
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.BLOCK_UPDATE_SECTION_TRANSMISSIONS, players.size());
                switch (pending.delayTicks) {
                    case NEAR_DELAY_TICKS -> ExcavatorProfiler.increment(
                            ExcavatorProfiler.Counter.BLOCK_UPDATE_NEAR_SECTION_FLUSHES);
                    case MID_DELAY_TICKS -> ExcavatorProfiler.increment(
                            ExcavatorProfiler.Counter.BLOCK_UPDATE_MID_SECTION_FLUSHES);
                    case FAR_DELAY_TICKS -> ExcavatorProfiler.increment(
                            ExcavatorProfiler.Counter.BLOCK_UPDATE_FAR_SECTION_FLUSHES);
                    default -> ExcavatorProfiler.increment(
                            ExcavatorProfiler.Counter.BLOCK_UPDATE_VERY_FAR_SECTION_FLUSHES);
                }
            }
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_UPDATE_PACKET_SEND, profile);
        }
    }

    /** Collects only sections due in this tick; splits at 32 KiB per recipient. */
    private static final class OutgoingSections {
        private final List<ExcavatorSectionBlockUpdatePayload> sections = new ArrayList<>();
        private int bytes = 3; // Reserve the largest possible section-count VarInt.

        private void add(ServerPlayer player, ExcavatorSectionBlockUpdatePayload section) {
            int sectionBytes = ExcavatorMultiSectionBlockUpdatePayload.encodedSectionBytes(section);
            if (!sections.isEmpty() && (bytes + sectionBytes > ExcavatorMultiSectionBlockUpdatePayload.MAX_BYTES
                    || sections.size() == ExcavatorMultiSectionBlockUpdatePayload.MAX_SECTIONS)) {
                flush(player);
            }
            sections.add(section);
            bytes += sectionBytes;
        }

        private void flush(ServerPlayer player) {
            if (sections.isEmpty()) return;
            long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_MULTI_PACKET_SEND);
            try {
                PacketDistributor.sendToPlayer(player, new ExcavatorMultiSectionBlockUpdatePayload(sections));
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_PACKET_TRANSMISSIONS);
            } finally {
                ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_MULTI_PACKET_SEND, profile);
            }
            sections.clear();
            bytes = 3;
        }
    }

    /** One scheduler per dimension; only sections actually due are examined. */
    private static final class PendingLevel {
        private final Long2ObjectOpenHashMap<PendingSection> sections = new Long2ObjectOpenHashMap<>();
        private final PendingSection[] wheel = new PendingSection[WHEEL_SIZE];
        private long lastProcessedGameTime;

        private PendingLevel(long gameTime) {
            lastProcessedGameTime = gameTime;
        }

        private void schedule(PendingSection pending, long dueGameTime) {
            if (pending.scheduled && pending.dueGameTime == dueGameTime) return;
            if (pending.scheduled) unlink(pending);
            int slot = (int) (dueGameTime & WHEEL_MASK);
            pending.dueGameTime = dueGameTime;
            pending.previousScheduled = null;
            pending.nextScheduled = wheel[slot];
            if (pending.nextScheduled != null) pending.nextScheduled.previousScheduled = pending;
            wheel[slot] = pending;
            pending.scheduled = true;
        }

        private void unlink(PendingSection pending) {
            int slot = (int) (pending.dueGameTime & WHEEL_MASK);
            if (pending.previousScheduled == null) {
                wheel[slot] = pending.nextScheduled;
            } else {
                pending.previousScheduled.nextScheduled = pending.nextScheduled;
            }
            if (pending.nextScheduled != null) {
                pending.nextScheduled.previousScheduled = pending.previousScheduled;
            }
            pending.previousScheduled = null;
            pending.nextScheduled = null;
            pending.scheduled = false;
        }
    }

    private static final class PendingSection {
        private final long sectionKey;
        private final long firstQueuedGameTime;
        private short[] positions = new short[4];
        private int size;
        private long lastQueuedGameTime;
        private int delayTicks;

        // Intrusive wheel links for O(1) rescheduling.
        private long dueGameTime;
        private PendingSection previousScheduled;
        private PendingSection nextScheduled;
        private boolean scheduled;

        private PendingSection(long sectionKey, long firstQueuedGameTime) {
            this.sectionKey = sectionKey;
            this.firstQueuedGameTime = firstQueuedGameTime;
            this.lastQueuedGameTime = firstQueuedGameTime;
        }

        private void add(short position) {
            if (size == positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
            }
            positions[size++] = position;
        }
    }
}
