package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.network.excavator.ExcavatorSectionBlockUpdatePayload;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Batches server-side excavator block removals into chunk-section sync payloads.
 *
 * World state changes are immediate on the server. Client-side block positions are
 * accumulated and sent to tracking players after a distance-dependent interval.
 */
public final class ExcavatorBlockSyncBatcher {
    private static final int NEAR_DELAY_TICKS = 5;
    private static final int MID_DELAY_TICKS = 10;
    private static final int FAR_DELAY_TICKS = 20;
    private static final int VERY_FAR_DELAY_TICKS = 60;

    // If a section accumulated a useful batch and then receives no further
    // excavator changes for a few ticks, flush it early instead of preserving a
    // far-distance delay after excavation has already moved elsewhere.
    private static final int QUIET_FLUSH_MIN_BATCH = 8;
    private static final int QUIET_FLUSH_AFTER_TICKS = 3;
    private static final int QUIET_FLUSH_MIN_AGE_TICKS = 5;

    private static final double NEAR_DISTANCE_SQ = 48.0 * 48.0;
    private static final double MID_DISTANCE_SQ = 96.0 * 96.0;
    private static final double FAR_DISTANCE_SQ = 160.0 * 160.0;

    private static final Map<ServerLevel, Map<SectionPos, PendingSection>> PENDING_SECTIONS_BY_LEVEL =
            new IdentityHashMap<>();

    private ExcavatorBlockSyncBatcher() {}

    public static void queueRemoval(ServerLevel level, BlockPos pos) {
        if (LaserExcavatorConfig.suppressBlockUpdateNetworkPayloads()) {
            return;
        }

        SectionPos sectionPos = SectionPos.of(pos);
        Map<SectionPos, PendingSection> bySection =
                PENDING_SECTIONS_BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
        long gameTime = level.getGameTime();
        PendingSection pending = bySection.computeIfAbsent(
                sectionPos,
                ignored -> new PendingSection(gameTime)
        );
        pending.positions.add(SectionPos.sectionRelativePos(pos));
        pending.lastQueuedGameTime = gameTime;
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
            Map<SectionPos, PendingSection> bySection = PENDING_SECTIONS_BY_LEVEL.get(level);
            if (bySection == null || bySection.isEmpty()) continue;

            long gameTime = level.getGameTime();
            ServerChunkCache chunkSource = level.getChunkSource();
            Iterator<Map.Entry<SectionPos, PendingSection>> iterator = bySection.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<SectionPos, PendingSection> entry = iterator.next();
                PendingSection pending = entry.getValue();
                if (pending.positions.isEmpty()) {
                    iterator.remove();
                    continue;
                }

                // Do not even perform tracking-player distance work before the smallest
                // possible batching window can have elapsed.
                long ageTicks = Math.max(0L, gameTime - pending.firstQueuedGameTime);
                if (ageTicks < NEAR_DELAY_TICKS - 1L) continue;

                SectionPos sectionPos = entry.getKey();
                ChunkPos chunkPos = new ChunkPos(sectionPos.x(), sectionPos.z());
                List<ServerPlayer> players = chunkSource.chunkMap.getPlayers(chunkPos, false);

                // If nobody tracks this chunk, there is no reason to preserve a delayed
                // update. A player that begins tracking it later receives the current
                // authoritative chunk state through the normal chunk sync.
                if (players.isEmpty()) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_NO_TRACKER_DROPS);
                    iterator.remove();
                    continue;
                }

                if (pending.delayTicks == 0) {
                    pending.delayTicks = chooseDelayTicks(sectionPos, players);
                }

                long quietTicks = Math.max(0L, gameTime - pending.lastQueuedGameTime);
                boolean ageDue = ageTicks >= pending.delayTicks - 1L;
                // Near/mid sections may flush early once excavation has moved on, but
                // FAR/VERY-FAR sections deliberately keep their full batching window.
                // Distant terrain benefits much more from fewer remeshes than from a
                // sub-second visual catch-up after a section becomes quiet.
                boolean quietEligible = pending.delayTicks <= MID_DELAY_TICKS;
                boolean quietDue = quietEligible
                        && pending.positions.size() >= QUIET_FLUSH_MIN_BATCH
                        && ageTicks >= QUIET_FLUSH_MIN_AGE_TICKS - 1L
                        && quietTicks >= QUIET_FLUSH_AFTER_TICKS;
                if (!ageDue && !quietDue) continue;

                if (quietDue && !ageDue) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_QUIET_SECTION_FLUSHES);
                }
                sendSectionPayload(sectionPos, pending, players);
                iterator.remove();
            }

            if (bySection.isEmpty()) PENDING_SECTIONS_BY_LEVEL.remove(level);
        }
    }

    private static int chooseDelayTicks(SectionPos sectionPos, List<ServerPlayer> players) {
        double centerX = sectionPos.minBlockX() + 8.0;
        double centerY = sectionPos.minBlockY() + 8.0;
        double centerZ = sectionPos.minBlockZ() + 8.0;
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

    private static void sendSectionPayload(
            SectionPos sectionPos,
            PendingSection pending,
            List<ServerPlayer> players
    ) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.BLOCK_UPDATE_PACKET_SEND);
        try {
            ArrayList<Short> localPositions = new ArrayList<>(pending.positions.size());
            for (short packed : pending.positions) localPositions.add(packed);

            ExcavatorSectionBlockUpdatePayload payload = new ExcavatorSectionBlockUpdatePayload(
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    localPositions
            );

            for (ServerPlayer player : players) {
                PacketDistributor.sendToPlayer(player, payload);
            }

            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.BLOCK_UPDATE_SECTION_PACKETS);
            ExcavatorProfiler.add(
                    ExcavatorProfiler.Counter.BLOCK_UPDATE_PACKET_TRANSMISSIONS,
                    players.size()
            );

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
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.BLOCK_UPDATE_PACKET_SEND, profile);
        }
    }

    private static final class PendingSection {
        private final long firstQueuedGameTime;
        private final ShortOpenHashSet positions = new ShortOpenHashSet();
        private long lastQueuedGameTime;
        private int delayTicks;

        private PendingSection(long firstQueuedGameTime) {
            this.firstQueuedGameTime = firstQueuedGameTime;
            this.lastQueuedGameTime = firstQueuedGameTime;
        }
    }
}
