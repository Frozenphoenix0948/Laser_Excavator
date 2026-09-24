package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class ExcavationScanner {
    public static final int NO_SURFACE = Integer.MIN_VALUE;

    private ExcavationScanner() {
    }

    /**
     * Returns the highest non-air block in the requested Y range.
     * Fluids and vegetation count as part of the top layer.
     */
    public static int findSurfaceY(ServerLevel level, int x, int z, int minY, int maxY) {
        int clampedMinY = Math.max(minY, level.getMinBuildHeight());
        int clampedMaxY = Math.min(maxY, level.getMaxBuildHeight() - 1);

        if (clampedMinY > clampedMaxY) {
            return NO_SURFACE;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, clampedMaxY, z);
        boolean profile = ExcavatorProfiler.isEnabled();
        int lookups = 0;
        int result = NO_SURFACE;

        for (int y = clampedMaxY; y >= clampedMinY; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (profile) lookups++;

            if (!state.isAir()) {
                result = y;
                break;
            }
        }

        if (profile) {
            ExcavatorProfiler.add(ExcavatorProfiler.Counter.SCAN_BLOCK_STATE_LOOKUPS, lookups);
        }
        return result;
    }
}
