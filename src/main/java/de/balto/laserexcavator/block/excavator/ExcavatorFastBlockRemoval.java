package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.IdentityHashMap;

/** Direct-chunk removal is restricted to plain Block states without special behavior. */
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

    public static boolean removeDirect(LevelChunk chunk, BlockPos pos) {
        BlockState previous = chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE);
        return previous != null;
    }

    private static boolean computeEligibility(BlockState state) {
        if (state.getBlock().getClass() != Block.class) return false;
        if (state.hasBlockEntity()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isRandomlyTicking()) return false;
        if (state.isSignalSource()) return false;
        if (state.hasAnalogOutputSignal()) return false;
        return !PoiTypes.hasPoi(state);
    }
}
