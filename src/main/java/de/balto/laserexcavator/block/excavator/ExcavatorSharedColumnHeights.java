package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime-only shared excavation cursor for normal (unfiltered) excavators.
 *
 * Each world X/Z column has one shared current Y. Overlapping excavators therefore
 * observe progress made by each other instead of independently discovering that a
 * cached target has already become air.
 *
 * Registration is idempotent per excavator block position and excavation area. This
 * protects the shared reference counts against repeated initialization/load paths while
 * still allowing a genuinely changed area to unregister and register again.
 */
public final class ExcavatorSharedColumnHeights {
    private static final int ABSENT = Integer.MAX_VALUE;

    /** ServerLevel is weakly referenced so a closed world can be collected. */
    private static final Map<ServerLevel, LevelState> LEVELS = new WeakHashMap<>();

    private ExcavatorSharedColumnHeights() {
    }

    public static void clearAll() {
        LEVELS.clear();
    }

    /**
     * Begins one excavator-area registration.
     *
     * Returns true only when this owner/area is newly registered and its columns must
     * call register(...).
     */
    public static boolean beginOwnerRegistration(
            ServerLevel level,
            long ownerKey,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int minY,
            int maxY
    ) {
        LevelState state = state(level);
        OwnerRegistration existing = state.owners.get(ownerKey);
        if (existing != null && existing.matches(minX, maxX, minZ, maxZ, minY, maxY)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_DUPLICATE_AREA_REGISTRATIONS_SKIPPED);
            return false;
        }

        if (existing != null) {
            unregisterOwnerInternal(state, ownerKey, existing);
        }

        state.owners.put(ownerKey, new OwnerRegistration(minX, maxX, minZ, maxZ, minY, maxY));
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_OWNER_AREAS_REGISTERED);
        return true;
    }

    /**
     * Registers one newly accepted owner against a world X/Z column and returns the
     * shared Y. Callers must first receive true from beginOwnerRegistration.
     */
    public static int register(
            ServerLevel level,
            int x,
            int z,
            int seedY,
            int minY,
            int maxY
    ) {
        LevelState state = state(level);
        long key = key(x, z);
        int current = state.currentY.get(key);
        int normalizedSeedY = seedY == ExcavationScanner.NO_SURFACE
                ? seedY
                : Math.min(seedY, maxY);

        state.userCounts.put(key, state.userCounts.get(key) + 1);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REGISTRATIONS);

        if (current == ABSENT) {
            state.currentY.put(key, normalizedSeedY);
            state.bounds.put(key, packBounds(minY, maxY));
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_COLUMNS_CREATED);
            return normalizedSeedY;
        }

        long packed = state.bounds.get(key);
        int oldMin = unpackMinY(packed);
        int oldMax = unpackMaxY(packed);
        int mergedMin = Math.min(oldMin, minY);
        int mergedMax = Math.max(oldMax, maxY);

        int mergedCurrent = current;

        // A higher excavator can reveal terrain above the range represented so far.
        if (maxY > oldMax && normalizedSeedY != ExcavationScanner.NO_SURFACE) {
            if (mergedCurrent == ExcavationScanner.NO_SURFACE || normalizedSeedY > mergedCurrent) {
                mergedCurrent = normalizedSeedY;
            }
        }

        // Extending an exhausted column downward can make the shared cursor active again.
        if (minY < oldMin
                && mergedCurrent == ExcavationScanner.NO_SURFACE
                && normalizedSeedY != ExcavationScanner.NO_SURFACE) {
            mergedCurrent = normalizedSeedY;
        }

        if (mergedCurrent != current) {
            state.currentY.put(key, mergedCurrent);
        }
        if (mergedMin != oldMin || mergedMax != oldMax) {
            state.bounds.put(key, packBounds(mergedMin, mergedMax));
        }

        return mergedCurrent;
    }

    public static void unregisterOwner(ServerLevel level, long ownerKey) {
        LevelState state = LEVELS.get(level);
        if (state == null) return;
        OwnerRegistration registration = state.owners.get(ownerKey);
        if (registration == null) return;
        unregisterOwnerInternal(state, ownerKey, registration);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_OWNER_AREAS_UNREGISTERED);
    }

    private static void unregisterOwnerInternal(
            LevelState state,
            long ownerKey,
            OwnerRegistration registration
    ) {
        state.owners.remove(ownerKey);
        for (int x = registration.minX; x <= registration.maxX; x++) {
            for (int z = registration.minZ; z <= registration.maxZ; z++) {
                long key = key(x, z);
                int users = state.userCounts.get(key);
                if (users <= 0) continue;
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REFERENCE_RELEASES);
                if (users <= 1) {
                    state.userCounts.remove(key);
                    state.currentY.remove(key);
                    state.bounds.remove(key);
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_COLUMNS_RELEASED);
                } else {
                    state.userCounts.put(key, users - 1);
                }
            }
        }
    }

    public static int getCurrentY(ServerLevel level, int x, int z) {
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_LOOKUPS);
        return state(level).currentY.get(key(x, z));
    }

    public static int getRegisteredMinY(ServerLevel level, int x, int z, int fallbackMinY) {
        LevelState state = state(level);
        long key = key(x, z);
        if (!state.currentY.containsKey(key)) return fallbackMinY;
        return unpackMinY(state.bounds.get(key));
    }

    /**
     * Moves the cursor only if it still equals expectedY. This prevents a
     * late laser completion from moving the shared cursor back upward after another
     * excavator already advanced the same column.
     */
    public static int advance(
            ServerLevel level,
            int x,
            int z,
            int expectedY,
            int nextY
    ) {
        LevelState state = state(level);
        long key = key(x, z);
        int current = state.currentY.get(key);

        if (current == ABSENT) {
            // Defensive fallback. Normal excavation registers every column first.
            state.currentY.put(key, nextY);
            state.bounds.put(key, packBounds(nextY, expectedY));
            state.userCounts.put(key, Math.max(1, state.userCounts.get(key)));
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_COLUMNS_CREATED);
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_ADVANCES);
            return nextY;
        }

        if (current == expectedY) {
            state.currentY.put(key, nextY);
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_ADVANCES);
            return nextY;
        }

        return current;
    }

    private static LevelState state(ServerLevel level) {
        LevelState state = LEVELS.get(level);
        if (state != null) return state;

        state = new LevelState();
        LEVELS.put(level, state);
        return state;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static long packBounds(int minY, int maxY) {
        return ((long) minY << 32) | (maxY & 0xFFFFFFFFL);
    }

    private static int unpackMinY(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackMaxY(long packed) {
        return (int) packed;
    }

    private record OwnerRegistration(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int minY,
            int maxY
    ) {
        private boolean matches(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
            return this.minX == minX
                    && this.maxX == maxX
                    && this.minZ == minZ
                    && this.maxZ == maxZ
                    && this.minY == minY
                    && this.maxY == maxY;
        }
    }

    private static final class LevelState {
        private final Long2IntOpenHashMap currentY = new Long2IntOpenHashMap();
        private final Long2LongOpenHashMap bounds = new Long2LongOpenHashMap();
        private final Long2IntOpenHashMap userCounts = new Long2IntOpenHashMap();
        private final Long2ObjectOpenHashMap<OwnerRegistration> owners = new Long2ObjectOpenHashMap<>();

        private LevelState() {
            currentY.defaultReturnValue(ABSENT);
            userCounts.defaultReturnValue(0);
        }
    }
}
