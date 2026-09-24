package de.balto.laserexcavator.network.excavator;

import de.balto.laserexcavator.block.excavator.ExcavatorBlockSyncBatcher;
import de.balto.laserexcavator.client.ExcavatorClientBlockUpdates;
import de.balto.laserexcavator.client.renderer.ExcavatorClientVisuals;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers excavator client-sync payloads and batches visual events for tracking players.
 *
 * Visual starts are grouped by tracking chunk and excavator, retained across
 * several server ticks, then sent as compact payloads. Exact mined-block
 * positions are preserved as signed relative deltas; batching only changes when
 * the network transfer occurs, not which block each visual belongs to.
 */
public final class ExcavatorNetworking {
    private static final Map<ServerLevel, Map<ChunkPos, PendingChunkVisuals>> PENDING_VISUALS_BY_LEVEL = new IdentityHashMap<>();

    private ExcavatorNetworking() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("6");

        registrar.playToClient(
                ExcavatorSectionBlockUpdatePayload.TYPE,
                ExcavatorSectionBlockUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ExcavatorClientBlockUpdates.applySectionRemovals(payload)
                )
        );

        registrar.playToClient(
                ExcavatorVisualBatchPayload.TYPE,
                ExcavatorVisualBatchPayload.STREAM_CODEC,
                // Decoded payloads enter the client queue directly and are materialized
                // together during the client tick.
                (payload, context) -> ExcavatorClientVisuals.enqueueVisualBatch(payload)
        );
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ExcavatorBlockSyncBatcher.flushDueSections(server);
        flushQueuedVisuals(server);
    }

    public static void clearPendingVisuals() {
        PENDING_VISUALS_BY_LEVEL.clear();
    }

    public static void sendLaserStart(
            ServerLevel level,
            BlockPos excavatorPos,
            BlockPos targetPos
    ) {
        if (LaserExcavatorConfig.suppressVisualNetworkPayloads()) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NETWORK_DEBUG_LASER_EVENTS_SUPPRESSED);
            return;
        }

        PendingChunkVisuals pendingChunk = getOrCreatePendingChunk(level, excavatorPos);
        PendingExcavatorVisuals excavatorVisuals = pendingChunk.getOrCreateExcavatorVisuals(excavatorPos);
        excavatorVisuals.lasers.add(new QueuedLaser(
                targetPos.immutable(),
                level.getGameTime()
        ));
        pendingChunk.eventCount++;
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LASER_VISUAL_EVENTS_QUEUED);
    }

    public static void sendTransportStart(
            ServerLevel level,
            BlockPos excavatorPos,
            BlockPos sourcePos,
            ItemStack visualStack,
            int totalDurationTicks,
            int forceFieldY
    ) {
        if (visualStack.isEmpty()) return;
        if (LaserExcavatorConfig.suppressVisualNetworkPayloads()) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NETWORK_DEBUG_TRANSPORT_EVENTS_SUPPRESSED);
            return;
        }

        ItemStack visualStackCopy = visualStack.copy();
        visualStackCopy.setCount(1);

        PendingChunkVisuals pendingChunk = getOrCreatePendingChunk(level, excavatorPos);
        PendingExcavatorVisuals excavatorVisuals = pendingChunk.getOrCreateExcavatorVisuals(excavatorPos);
        excavatorVisuals.transports.add(new QueuedTransport(
                sourcePos.immutable(),
                visualStackCopy,
                Math.max(1, totalDurationTicks),
                forceFieldY,
                level.getGameTime()
        ));
        pendingChunk.eventCount++;
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.TRANSPORT_VISUAL_EVENTS_QUEUED);
    }

    private static PendingChunkVisuals getOrCreatePendingChunk(ServerLevel level, BlockPos excavatorPos) {
        Map<ChunkPos, PendingChunkVisuals> byChunk = PENDING_VISUALS_BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
        ChunkPos chunkPos = new ChunkPos(excavatorPos);
        return byChunk.computeIfAbsent(chunkPos, ignored -> new PendingChunkVisuals(level.getGameTime()));
    }

    private static void flushQueuedVisuals(MinecraftServer server) {
        if (LaserExcavatorConfig.suppressVisualNetworkPayloads()) {
            long dropped = 0L;
            for (Map<ChunkPos, PendingChunkVisuals> byChunk : PENDING_VISUALS_BY_LEVEL.values()) {
                for (PendingChunkVisuals pending : byChunk.values()) {
                    dropped += pending.eventCount;
                }
            }
            PENDING_VISUALS_BY_LEVEL.clear();
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.NETWORK_DEBUG_PENDING_VISUAL_EVENTS_DROPPED, dropped);
            return;
        }

        int batchTicks = Math.max(1, LaserExcavatorConfig.VISUAL_BATCH_TICKS.get());
        int maxEvents = Math.min(
                ExcavatorVisualBatchPayload.MAX_TOTAL_EVENTS,
                Math.max(1, LaserExcavatorConfig.VISUAL_BATCH_MAX_EVENTS.get())
        );

        for (ServerLevel level : server.getAllLevels()) {
            Map<ChunkPos, PendingChunkVisuals> byChunk = PENDING_VISUALS_BY_LEVEL.get(level);
            if (byChunk == null || byChunk.isEmpty()) continue;

            long now = level.getGameTime();
            var iterator = byChunk.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ChunkPos, PendingChunkVisuals> entry = iterator.next();
                PendingChunkVisuals pending = entry.getValue();

                boolean dueToEventCap = pending.eventCount >= maxEvents;
                boolean dueToInterval = now - pending.firstQueuedGameTime >= batchTicks - 1L;
                if (!dueToEventCap && !dueToInterval) continue;

                if (dueToEventCap) {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.VISUAL_BATCH_CAP_FLUSHES);
                } else {
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.VISUAL_BATCH_INTERVAL_FLUSHES);
                }

                sendChunkBatches(level, entry.getKey(), pending, now, maxEvents);
                iterator.remove();
            }

            if (byChunk.isEmpty()) {
                PENDING_VISUALS_BY_LEVEL.remove(level);
            }
        }
    }

    private static void sendChunkBatches(
            ServerLevel level,
            ChunkPos chunkPos,
            PendingChunkVisuals pending,
            long flushGameTime,
            int maxEvents
    ) {
        List<GroupSlice> slices = new ArrayList<>();
        int payloadEvents = 0;

        for (Map.Entry<BlockPos, PendingExcavatorVisuals> groupEntry : pending.byExcavator.entrySet()) {
            BlockPos excavatorPos = groupEntry.getKey();
            PendingExcavatorVisuals queued = groupEntry.getValue();
            int laserIndex = 0;
            int transportIndex = 0;

            while (laserIndex < queued.lasers.size() || transportIndex < queued.transports.size()) {
                if (payloadEvents >= maxEvents) {
                    sendPayload(level, chunkPos, flushGameTime, slices, payloadEvents);
                    slices = new ArrayList<>();
                    payloadEvents = 0;
                }

                int capacity = maxEvents - payloadEvents;
                int laserTake = Math.min(capacity, queued.lasers.size() - laserIndex);
                capacity -= laserTake;
                int transportTake = Math.min(capacity, queued.transports.size() - transportIndex);

                int groupEvents = laserTake + transportTake;
                if (groupEvents <= 0) break;

                slices.add(new GroupSlice(
                        excavatorPos,
                        queued,
                        laserIndex,
                        laserTake,
                        transportIndex,
                        transportTake
                ));
                laserIndex += laserTake;
                transportIndex += transportTake;
                payloadEvents += groupEvents;
            }
        }

        if (payloadEvents > 0) {
            sendPayload(level, chunkPos, flushGameTime, slices, payloadEvents);
        }
    }

    private static void sendPayload(
            ServerLevel level,
            ChunkPos chunkPos,
            long flushGameTime,
            List<GroupSlice> slices,
            int eventCount
    ) {
        if (eventCount <= 0 || slices.isEmpty()) return;

        int groupCount = slices.size();
        int totalLasers = 0;
        int totalTransports = 0;
        for (GroupSlice slice : slices) {
            totalLasers += slice.laserCount;
            totalTransports += slice.transportCount;
        }

        BlockPos[] excavatorPositions = new BlockPos[groupCount];
        int[] groupLaserStart = new int[groupCount];
        int[] groupLaserCount = new int[groupCount];
        int[] groupTransportStart = new int[groupCount];
        int[] groupTransportCount = new int[groupCount];

        int[] laserDeltaX = new int[totalLasers];
        int[] laserDeltaY = new int[totalLasers];
        int[] laserDeltaZ = new int[totalLasers];
        int[] laserAge = new int[totalLasers];

        int[] transportDeltaX = new int[totalTransports];
        int[] transportDeltaY = new int[totalTransports];
        int[] transportDeltaZ = new int[totalTransports];
        ItemStack[] transportStacks = new ItemStack[totalTransports];
        int[] transportDurationTicks = new int[totalTransports];
        int[] transportForceFieldYOffset = new int[totalTransports];
        int[] transportAge = new int[totalTransports];

        int laserOut = 0;
        int transportOut = 0;
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            GroupSlice slice = slices.get(groupIndex);
            BlockPos excavatorPos = slice.excavatorPos;

            excavatorPositions[groupIndex] = excavatorPos;
            groupLaserStart[groupIndex] = laserOut;
            groupLaserCount[groupIndex] = slice.laserCount;
            groupTransportStart[groupIndex] = transportOut;
            groupTransportCount[groupIndex] = slice.transportCount;

            int laserEnd = slice.laserStart + slice.laserCount;
            for (int sourceIndex = slice.laserStart; sourceIndex < laserEnd; sourceIndex++) {
                QueuedLaser laser = slice.queued.lasers.get(sourceIndex);
                int ageTicks = ageTicks(flushGameTime, laser.serverStartGameTime);
                laserDeltaX[laserOut] = laser.targetPos.getX() - excavatorPos.getX();
                laserDeltaY[laserOut] = laser.targetPos.getY() - excavatorPos.getY();
                laserDeltaZ[laserOut] = laser.targetPos.getZ() - excavatorPos.getZ();
                laserAge[laserOut] = ageTicks;
                laserOut++;
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.VISUAL_EVENT_AGE_TICKS_SENT, ageTicks);
            }

            int transportEnd = slice.transportStart + slice.transportCount;
            for (int sourceIndex = slice.transportStart; sourceIndex < transportEnd; sourceIndex++) {
                QueuedTransport transport = slice.queued.transports.get(sourceIndex);
                int ageTicks = ageTicks(flushGameTime, transport.serverStartGameTime);
                transportDeltaX[transportOut] = transport.sourcePos.getX() - excavatorPos.getX();
                transportDeltaY[transportOut] = transport.sourcePos.getY() - excavatorPos.getY();
                transportDeltaZ[transportOut] = transport.sourcePos.getZ() - excavatorPos.getZ();
                transportStacks[transportOut] = transport.visualStack;
                transportDurationTicks[transportOut] = transport.totalDurationTicks;
                transportForceFieldYOffset[transportOut] = transport.forceFieldY - excavatorPos.getY();
                transportAge[transportOut] = ageTicks;
                transportOut++;
                ExcavatorProfiler.add(ExcavatorProfiler.Counter.VISUAL_EVENT_AGE_TICKS_SENT, ageTicks);
            }
        }

        ExcavatorVisualBatchPayload payload = new ExcavatorVisualBatchPayload(
                flushGameTime,
                excavatorPositions,
                groupLaserStart,
                groupLaserCount,
                groupTransportStart,
                groupTransportCount,
                laserDeltaX,
                laserDeltaY,
                laserDeltaZ,
                laserAge,
                transportDeltaX,
                transportDeltaY,
                transportDeltaZ,
                transportStacks,
                transportDurationTicks,
                transportForceFieldYOffset,
                transportAge
        );

        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.VISUAL_PACKET_SEND);
        PacketDistributor.sendToPlayersTrackingChunk(level, chunkPos, payload);
        ExcavatorProfiler.end(ExcavatorProfiler.Section.VISUAL_PACKET_SEND, profile);

        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.VISUAL_BATCH_PACKETS_SENT);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.VISUAL_EVENTS_SENT, eventCount);
        ExcavatorProfiler.add(ExcavatorProfiler.Counter.VISUAL_BATCH_EXCAVATOR_GROUPS_SENT, groupCount);
    }

    private record GroupSlice(
            BlockPos excavatorPos,
            PendingExcavatorVisuals queued,
            int laserStart,
            int laserCount,
            int transportStart,
            int transportCount
    ) {}

    private static int ageTicks(long flushGameTime, long startGameTime) {
        long age = Math.max(0L, flushGameTime - startGameTime);
        return age >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) age;
    }

    private static final class PendingChunkVisuals {
        private final long firstQueuedGameTime;
        private final Map<BlockPos, PendingExcavatorVisuals> byExcavator = new LinkedHashMap<>();
        private int eventCount;

        private PendingChunkVisuals(long firstQueuedGameTime) {
            this.firstQueuedGameTime = firstQueuedGameTime;
        }

        private PendingExcavatorVisuals getOrCreateExcavatorVisuals(BlockPos excavatorPos) {
            BlockPos key = excavatorPos.immutable();
            return byExcavator.computeIfAbsent(key, ignored -> new PendingExcavatorVisuals());
        }
    }

    private static final class PendingExcavatorVisuals {
        private final List<QueuedLaser> lasers = new ArrayList<>();
        private final List<QueuedTransport> transports = new ArrayList<>();
    }

    private record QueuedLaser(
            BlockPos targetPos,
            long serverStartGameTime
    ) {}

    private record QueuedTransport(
            BlockPos sourcePos,
            ItemStack visualStack,
            int totalDurationTicks,
            int forceFieldY,
            long serverStartGameTime
    ) {}
}
