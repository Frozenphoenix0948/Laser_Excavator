package de.balto.laserexcavator.block.blockentities;

import de.balto.laserexcavator.block.excavator.ExcavationScanner;
import de.balto.laserexcavator.block.excavator.ExcavatorSharedColumnHeights;
import de.balto.laserexcavator.block.excavator.ExcavatorUpgradeManager;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Set;

import static de.balto.laserexcavator.block.excavator.ExcavatorUpgradeManager.isFluidBlock;

/**
 * The new Target Scanner/Selection:
 * Contains all the code previously distributed across the Entity and also contains a new palette system for 100% cooldown
 * filters that allows them to skip blocks much faster reducing server time. This is done by reading the chuck data of the column
 * and checking in the chunk palette whether there is any non filtered block if not then it immediately skips the whole 16 block column.
 */

final class ExcavatorTargetScanner {
    private static final byte UNKNOWN = 0;
    private static final byte AIR = 1;
    private static final byte IGNORED = 2;
    private static final byte FILTERED = 3;
    private static final byte NORMAL = 4;

    enum Handling {
        NORMAL,
        FILTERED,
        IGNORED
    }

    record Result(int y, Handling targetHandling, @Nullable Block block) {
        static Result normal(int y, Block block) {
            return new Result(y, Handling.NORMAL, block);
        }

        static Result filtered(int y) {
            return new Result(y, Handling.FILTERED, null);
        }

        static Result ignored(int y) {
            return new Result(y, Handling.IGNORED, null);
        }

        ExcavatorUpgradeManager.TargetHandling handling() {
            return switch (targetHandling) {
                case NORMAL -> ExcavatorUpgradeManager.TargetHandling.NORMAL;
                case FILTERED -> ExcavatorUpgradeManager.TargetHandling.FILTERED;
                case IGNORED -> ExcavatorUpgradeManager.TargetHandling.IGNORED;
            };
        }
    }

    private record NormalTarget(int y, @Nullable Block block) {}

    private final ExcavatorUpgradeManager upgrades;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final IdentityHashMap<BlockState, Byte> stateClassifications = new IdentityHashMap<>();
    private final IdentityHashMap<Palette<BlockState>, PaletteClassification> paletteClassifications = new IdentityHashMap<>();
    private @Nullable PaletteClassification globalPaletteClassification;

    ExcavatorTargetScanner(ExcavatorUpgradeManager upgrades) {
        this.upgrades = upgrades;
    }

    void clearCaches() {
        stateClassifications.clear();
        paletteClassifications.clear();
        globalPaletteClassification = null;
    }

    Result initializeColumn(ServerLevel level, int x, int z, int startY, int minY, boolean unrestricted, LevelChunk loadedChunk) {
        if (unrestricted) {
            NormalTarget target = findNextNormalTarget(level, loadedChunk, x, z, startY, minY);
            return normalResult(target);
        }

        Result result = scanPrivate(level, x, z, startY, minY, loadedChunk);
        recordPrivateRegression(startY, result.y());
        return result;
    }

    Result resolveTarget(ServerLevel level, int x, int z, int currentY, int minY, int maxY, boolean unrestricted, boolean sharedColumn, LevelChunk loadedChunk) {
        if (currentY == ExcavationScanner.NO_SURFACE || currentY < minY || (sharedColumn && currentY > maxY)) {
            return Result.ignored(currentY);
        }

        if (!unrestricted) {
            Result result = scanPrivate(level, x, z, currentY, minY, loadedChunk);
            recordPrivateRegression(currentY, result.y());
            return result;
        }

        cursor.set(x, currentY, z);
        BlockState state = loadedChunk.getBlockState(cursor);
        Block block = state.getBlock();
        if (!state.isAir() && !LaserExcavatorConfig.unbreakableBlocks().contains(block)) {
            return Result.normal(currentY, block);
        }

        int scanMinY = sharedColumn ? ExcavatorSharedColumnHeights.getRegisteredMinY(level, x, z, minY) : minY;
        NormalTarget resolved = findNextNormalTarget(level, loadedChunk, x, z, currentY - 1, scanMinY);
        int resolvedY = resolved.y();
        if (!sharedColumn) return normalResult(resolved);

        int actualY = ExcavatorSharedColumnHeights.advance(level, x, z, currentY, resolvedY);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REPAIRS);
        return actualY == resolvedY ? normalResult(resolved) : Result.ignored(actualY);
    }

    int advanceLocalAfterRemoval(ServerLevel level, LevelChunk loadedChunk, int x, int z, int removedY, int minY) {
        return findNextNormalTarget(level, loadedChunk, x, z, removedY - 1, minY).y();
    }

    int advanceSharedAfterRemoval(ServerLevel level, LevelChunk loadedChunk, int x, int z, int removedY, int minY) {
        int registeredMinY = ExcavatorSharedColumnHeights.getRegisteredMinY(level, x, z, minY);
        int nextY = findNextNormalTarget(level, loadedChunk, x, z, removedY - 1, registeredMinY).y();
        return ExcavatorSharedColumnHeights.advance(level, x, z, removedY, nextY);
    }

    private NormalTarget findNextNormalTarget(ServerLevel level, LevelChunk loadedChunk, int x, int z, int startY, int minY) {
        long profileStart = ExcavatorProfiler.begin(ExcavatorProfiler.Section.NEXT_TARGET_LOOKUP);
        boolean profile = profileStart != 0L;
        int lookups = 0;
        boolean attempted = false;
        NormalTarget result = new NormalTarget(ExcavationScanner.NO_SURFACE, null);

        try {
            if (startY == ExcavationScanner.NO_SURFACE) {
                if (profile) ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NEXT_TARGET_NO_SURFACE_INPUTS);
                return result;
            }

            int start = Math.min(startY, level.getMaxBuildHeight() - 1);
            int min = Math.max(minY, level.getMinBuildHeight());
            Set<Block> unbreakable = LaserExcavatorConfig.unbreakableBlocks();
            cursor.set(x, start, z);
            attempted = true;

            for (int y = start; y >= min; y--) {
                cursor.setY(y);
                BlockState state = loadedChunk.getBlockState(cursor);
                lookups++;
                if (state.isAir() || unbreakable.contains(state.getBlock())) continue;
                result = new NormalTarget(y, state.getBlock());
                return result;
            }
            return result;
        } finally {
            if (profile) {
                ExcavatorProfiler.recordNextTargetScan(lookups, 0, attempted, result.y() != ExcavationScanner.NO_SURFACE, false);
                ExcavatorProfiler.end(ExcavatorProfiler.Section.NEXT_TARGET_LOOKUP, profileStart);
            }
        }
    }

    private Result normalResult(NormalTarget target) {
        if (target.y() == ExcavationScanner.NO_SURFACE || target.block() == null) return noSurface();
        return Result.normal(target.y(), target.block());
    }

    private Result scanPrivate(ServerLevel level, int x, int z, int startY, int minY, LevelChunk loadedChunk) {
        long profileStart = ExcavatorProfiler.begin(ExcavatorProfiler.Section.NEXT_TARGET_LOOKUP);
        boolean profile = profileStart != 0L;
        int lookups = 0;
        int filterSkips = 0;
        boolean attempted = false;
        byte returned = UNKNOWN;

        try {
            if (startY == ExcavationScanner.NO_SURFACE) {
                if (profile) ExcavatorProfiler.increment(ExcavatorProfiler.Counter.NEXT_TARGET_NO_SURFACE_INPUTS);
                return noSurface();
            }

            int start = Math.min(startY, level.getMaxBuildHeight() - 1);
            int min = Math.max(minY, level.getMinBuildHeight());
            Set<Block> unbreakable = LaserExcavatorConfig.unbreakableBlocks();
            attempted = true;

            boolean fluidIgnore = upgrades.fluidIgnoreForTargetScan();
            int filterTier = upgrades.tier(ExcavatorUpgradeType.FILTER);
            boolean filterEnabled = filterTier > 0;
            boolean cooldownFilter = filterEnabled && LaserExcavatorConfig.filterCooldownReductionPercent(filterTier) < 100;

            if (cooldownFilter) {
                cursor.set(x, start, z);
                for (int y = start; y >= min; y--) {
                    cursor.setY(y);
                    BlockState state = loadedChunk.getBlockState(cursor);
                    lookups++;

                    byte handling = classifyState(level, y, state, unbreakable, fluidIgnore, true);
                    if (handling == AIR || handling == IGNORED) continue;
                    if (handling == FILTERED) {
                        returned = FILTERED;
                        return Result.filtered(y);
                    }

                    returned = NORMAL;
                    return Result.normal(y, state.getBlock());
                }
                return noSurface();
            }

            cursor.set(x, start, z);
            int localColumnIndex = ((z & 15) << 4) | (x & 15);
            int y = start;

            while (y >= min) {
                LevelChunkSection section = loadedChunk.getSection(loadedChunk.getSectionIndex(y));
                PalettedContainer.Data<BlockState> data = section.getStates().data;
                BitStorage storage = data.storage();
                Palette<BlockState> palette = data.palette();
                PaletteClassification classification = classificationFor(palette);
                int sectionBottom = Math.max(min, y & ~15);
                int storageIndex = ((y & 15) << 8) | localColumnIndex;

                for (; y >= sectionBottom; y--, storageIndex -= 256) {
                    int paletteId = storage.get(storageIndex);
                    lookups++;

                    if (paletteId >= classification.size()) {
                        classification.ensureCapacity(Math.max(palette.getSize(), paletteId + 1));
                    }

                    byte handling = classification.handling(paletteId);
                    BlockState state = null;
                    if (handling == UNKNOWN) {
                        state = palette.valueFor(paletteId);
                        handling = classifyCacheableState(state, unbreakable, fluidIgnore, filterEnabled);
                        if (handling != UNKNOWN) {
                            classification.set(paletteId, handling, state);
                        } else {
                            cursor.setY(y);
                            handling = upgrades.isFilteredBlockForTargetScan(level, cursor, state) ? FILTERED : NORMAL;
                        }
                    }

                    if (handling == AIR || handling == IGNORED) continue;
                    if (handling == FILTERED) {
                        filterSkips++;
                        continue;
                    }

                    returned = NORMAL;
                    Block normalBlock = classification.normalBlock(paletteId);
                    if (normalBlock == null) {
                        if (state == null) state = palette.valueFor(paletteId);
                        normalBlock = state.getBlock();
                    }
                    return Result.normal(y, normalBlock);
                }
            }

            return noSurface();
        } finally {
            if (profile) {
                ExcavatorProfiler.recordNextTargetScan(lookups, filterSkips, attempted, returned == NORMAL, returned == FILTERED);
                ExcavatorProfiler.end(ExcavatorProfiler.Section.NEXT_TARGET_LOOKUP, profileStart);
            }
        }
    }

    private PaletteClassification classificationFor(Palette<BlockState> palette) {
        int size = palette.getSize();
        if (palette instanceof GlobalPalette<?>) {
            if (globalPaletteClassification == null) {
                globalPaletteClassification = new PaletteClassification(size);
            } else {
                globalPaletteClassification.ensureCapacity(size);
            }
            return globalPaletteClassification;
        }

        PaletteClassification classification = paletteClassifications.get(palette);
        if (classification == null) {
            classification = new PaletteClassification(size);
            paletteClassifications.put(palette, classification);
        } else {
            classification.ensureCapacity(size);
        }
        return classification;
    }

    private byte classifyState(ServerLevel level, int y, BlockState state, Set<Block> unbreakable, boolean fluidIgnore, boolean filterEnabled) {
        byte result = classifyCacheableState(state, unbreakable, fluidIgnore, filterEnabled);
        if (result != UNKNOWN) return result;

        cursor.setY(y);
        return upgrades.isFilteredBlockForTargetScan(level, cursor, state) ? FILTERED : NORMAL;
    }

    private byte classifyCacheableState(BlockState state, Set<Block> unbreakable, boolean fluidIgnore, boolean filterEnabled) {
        Byte cached = stateClassifications.get(state);
        if (cached != null) return cached;

        byte result;
        if (state.isAir()) {
            result = AIR;
        } else if (unbreakable.contains(state.getBlock()) || (fluidIgnore && isFluidBlock(state))) {
            result = IGNORED;
        } else if (!filterEnabled) {
            result = NORMAL;
        } else if (upgrades.isDefinitelyFilteredPaletteState(state)) {
            result = FILTERED;
        } else {
            result = UNKNOWN;
        }

        stateClassifications.put(state, result);
        return result;
    }

    private static void recordPrivateRegression(int previousY, int nextY) {
        if (previousY != ExcavationScanner.NO_SURFACE && nextY > previousY) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.PRIVATE_CURSOR_UPWARD_REGRESSIONS);
        }
    }

    private static Result noSurface() {
        return Result.ignored(ExcavationScanner.NO_SURFACE);
    }

    private static final class PaletteClassification {
        private byte[] handling;
        private Block[] normalBlocks;

        private PaletteClassification(int size) {
            handling = new byte[Math.max(1, size)];
            normalBlocks = new Block[handling.length];
        }

        private int size() {
            return handling.length;
        }

        private void ensureCapacity(int size) {
            if (size <= handling.length) return;
            handling = Arrays.copyOf(handling, size);
            normalBlocks = Arrays.copyOf(normalBlocks, size);
        }

        private byte handling(int paletteId) {
            return handling[paletteId];
        }

        private Block normalBlock(int paletteId) {
            return normalBlocks[paletteId];
        }

        private void set(int paletteId, byte targetHandling, BlockState state) {
            handling[paletteId] = targetHandling;
            if (targetHandling == NORMAL) normalBlocks[paletteId] = state.getBlock();
        }
    }
}
