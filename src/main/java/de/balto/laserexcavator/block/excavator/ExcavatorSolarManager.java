package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.Blocks;

/** Handles solar generation and periodic sky/daylight checks. */
public final class ExcavatorSolarManager {
    public static final int STATUS_NONE = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_NIGHT = 2;
    public static final int STATUS_BLOCKED = 3;
    private static final int DAYLIGHT_CHECK_INTERVAL = 20;

    private final ExcavatorEnergyStorage energyStorage;
    private long nextCheckTick = Long.MIN_VALUE;
    private long nextSkyCheckTick = Long.MIN_VALUE;
    private long nextDaylightCheckTick = Long.MIN_VALUE;
    private boolean skyClear;
    private boolean daylight;
    private int generationPerTick;
    private int status;
    private int tier;

    public ExcavatorSolarManager(ExcavatorEnergyStorage energyStorage) {
        this.energyStorage = energyStorage;
    }

    public void setTier(int tier) {
        tier = Math.max(0, tier);
        if (this.tier == tier) return;
        this.tier = tier;
        nextCheckTick = Long.MIN_VALUE;
        generationPerTick = 0;
        nextSkyCheckTick = Long.MIN_VALUE;
        nextDaylightCheckTick = Long.MIN_VALUE;
        if (tier == 0) {
            skyClear = false;
            daylight = false;
            status = STATUS_NONE;
        }
    }

    public boolean isInstalled() { return tier > 0; }

    public int tick(ServerLevel level, BlockPos pos) {
        long gameTime = level.getGameTime();
        if (gameTime >= nextCheckTick) refresh(level, pos, gameTime);
        return generationPerTick > 0 ? energyStorage.addInternal(generationPerTick) : 0;
    }

    private void refresh(ServerLevel level, BlockPos pos, long gameTime) {
        if (!level.dimensionType().hasSkyLight()) {
            status = STATUS_BLOCKED;
            generationPerTick = 0;
            nextCheckTick = nextCheckTick(gameTime, pos, DAYLIGHT_CHECK_INTERVAL);
            return;
        }

        if (gameTime >= nextSkyCheckTick) {
            skyClear = hasSkyAccess(level, pos);
            nextSkyCheckTick = nextCheckTick(gameTime, pos, LaserExcavatorConfig.solarSkyCheckInterval());
        }
        if (gameTime >= nextDaylightCheckTick) {
            daylight = level.isDay();
            nextDaylightCheckTick = nextCheckTick(gameTime, pos, DAYLIGHT_CHECK_INTERVAL);
        }

        status = !skyClear ? STATUS_BLOCKED : !daylight ? STATUS_NIGHT : STATUS_ACTIVE;
        generationPerTick = status == STATUS_ACTIVE ? LaserExcavatorConfig.solarEnergyPerTick(tier) : 0;
        nextCheckTick = Math.min(nextSkyCheckTick, nextDaylightCheckTick);
    }

    public int status() { return status; }

    // First check is immediate; subsequent checks are spread across the interval by position.
    private static long nextCheckTick(long gameTime, BlockPos pos, int interval) {
        long hash = (long) pos.getX() * 0x9E3779B97F4A7C15L
                ^ (long) pos.getY() * 0xC2B2AE3D27D4EB4FL
                ^ (long) pos.getZ() * 0x165667B19E3779F9L;
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        return gameTime + 1L + Math.floorMod(hash - gameTime - 1L, interval);
    }

    private static boolean hasSkyAccess(ServerLevel level, BlockPos pos) {
        int x = pos.getX(), z = pos.getZ();
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return false;

        int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int water = 0, maxWater = LaserExcavatorConfig.solarMaxWaterBlocks();
        int localX = x & 15, localZ = z & 15;
        int y = pos.getY() + 1;
        while (y < topY) {
            int index = chunk.getSectionIndex(y);
            if (index < 0 || index >= chunk.getSections().length) return false;
            LevelChunkSection section = chunk.getSection(index);
            int sectionEnd = Math.min(topY, (y & ~15) + 16);
            if (section.hasOnlyAir()) {
                y = sectionEnd;
                continue;
            }
            while (y < sectionEnd) {
                BlockState state = section.getBlockState(localX, y & 15, localZ);
                if (!state.isAir() && !(state.getBlock() instanceof AbstractGlassBlock) && !(state.getBlock() instanceof StainedGlassPaneBlock) && !state.is(Blocks.GLASS_PANE)) {
                    if (!isWaterBlock(state) || ++water > maxWater) return false;
                }
                y++;
            }
        }
        return true;
    }

    private static boolean isWaterBlock(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.WATER);
    }
}
