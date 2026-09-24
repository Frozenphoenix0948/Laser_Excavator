package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.IdentityHashMap;

/**
 * Conservative direct-chunk removal fast path for plain terrain states.
 *
 * The cache intentionally starts narrow. Only the vanilla/base Block
 * implementation is accepted; subclasses are left on Level#setBlock so
 * custom block lifecycle, POI, redstone, machines, falling blocks, ice behavior,
 * etc. keep the normal world-mutation wrapper. LevelChunk#setBlockState
 * still performs the actual chunk mutation and the normal block-state remove
 * callback, while the excavator skips wrapper work it does not need for these
 * simple terrain blocks.
 */
public final class ExcavatorFastBlockRemoval {
    private static final IdentityHashMap<BlockState, Boolean> ELIGIBILITY = new IdentityHashMap<>();

    private ExcavatorFastBlockRemoval() {}

    public static boolean isEligible(BlockState state) {
        Boolean cached = ELIGIBILITY.get(state);
        if (cached != null) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FAST_REMOVAL_ELIGIBILITY_CACHE_HITS);
            return cached;
        }

        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FAST_REMOVAL_ELIGIBILITY_CACHE_MISSES);
        boolean eligible = computeEligibility(state);
        ELIGIBILITY.put(state, eligible);
        return eligible;
    }

    public static boolean removeDirect(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        BlockState previous = chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
        return previous != null;
    }

    private static boolean computeEligibility(BlockState state) {
        // Direct removal is restricted to plain registered terrain blocks. Subclasses
        // may carry removal, POI or network semantics that require the normal Level path.
        if (state.getBlock().getClass() != Block.class) return false;
        if (state.hasBlockEntity()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isRandomlyTicking()) return false;
        if (state.isSignalSource()) return false;
        if (state.hasAnalogOutputSignal()) return false;
        return !PoiTypes.hasPoi(state);
    }
}
