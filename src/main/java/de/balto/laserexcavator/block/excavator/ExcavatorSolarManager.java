package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;

/**
 * This class takes care of the Solar Upgrades.
 */

public final class ExcavatorSolarManager {
    public static final int STATUS_NONE = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_NIGHT = 2;
    public static final int STATUS_BLOCKED = 3;
    private final ExcavatorEnergyStorage energyStorage;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private long nextSkyCheckTick = Long.MIN_VALUE;
    private boolean skyClear;
    private int status;
    private int tier;

    public ExcavatorSolarManager(ExcavatorEnergyStorage energyStorage) {
        this.energyStorage = energyStorage;
    }

    public void setTier(int tier) {
        tier = Math.max(0, tier);
        if (this.tier == tier) return;
        this.tier = tier;
        nextSkyCheckTick = Long.MIN_VALUE;
        if (tier == 0) {
            skyClear = false;
            status = STATUS_NONE;
        }
    }

    public boolean isInstalled() { return tier > 0; }

    public int tick(ServerLevel level, BlockPos pos) {
        long gameTime = level.getGameTime();
        if (nextSkyCheckTick == Long.MIN_VALUE || gameTime >= nextSkyCheckTick) {
            skyClear = hasSkyAccess(level, pos);
            nextSkyCheckTick = gameTime + LaserExcavatorConfig.solarSkyCheckInterval();
        }

        if (!skyClear || !level.dimensionType().hasSkyLight()) {
            status = STATUS_BLOCKED;
            return 0;
        }
        if (!level.isBrightOutside()) {
            status = STATUS_NIGHT;
            return 0;
        }
        status = STATUS_ACTIVE;
        return energyStorage.addInternal(LaserExcavatorConfig.solarEnergyPerTick(tier));
    }

    public int status() { return status; }

    private boolean hasSkyAccess(ServerLevel level, BlockPos pos) {
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        int water = 0, maxWater = LaserExcavatorConfig.solarMaxWaterBlocks();
        for (int y = pos.getY() + 1; y < topY; y++) {
            BlockState state = level.getBlockState(cursor.set(pos.getX(), y, pos.getZ()));
            if (state.isAir() || state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)) continue;
            if (isWaterBlock(state) && ++water <= maxWater) continue;
            return false;
        }
        return true;
    }

    private static boolean isWaterBlock(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.WATER);
    }
}
