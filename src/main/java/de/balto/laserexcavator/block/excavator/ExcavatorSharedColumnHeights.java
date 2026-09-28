package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

public final class ExcavatorSharedColumnHeights {
    private static final Map<ServerLevel, LevelState> LEVELS = new WeakHashMap<>();

    private ExcavatorSharedColumnHeights() {
    }

    public static void clearAll() {
        for (LevelState state : LEVELS.values()) {
            for (OwnerRegistration owner : state.owners.values()) {
                owner.columns.clearSharedColumns();
                owner.columns.setSharedColumnsRegistered(false);
            }
        }
        LEVELS.clear();
    }

    public static void registerOwner(
            ServerLevel level,
            long ownerKey,
            ExcavatorArea area,
            ExcavatorColumnState columns
    ) {
        LevelState state = state(level);
        OwnerRegistration existing = state.owners.get(ownerKey);
        if (existing != null && existing.matches(area, columns)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_DUPLICATE_AREA_REGISTRATIONS_SKIPPED);
            return;
        }

        if (existing != null) {
            unregisterOwnerInternal(state, ownerKey, existing);
        }

        columns.clearSharedColumns();
        OwnerRegistration owner = new OwnerRegistration(area, columns);
        state.owners.put(ownerKey, owner);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_OWNER_AREAS_REGISTERED);

        for (Long2ObjectMap.Entry<OwnerRegistration> entry : state.owners.long2ObjectEntrySet()) {
            long otherKey = entry.getLongKey();
            if (otherKey == ownerKey) continue;

            OwnerRegistration other = entry.getValue();
            if (area.max().getY() < other.area.min().getY()
                    || area.min().getY() > other.area.max().getY()) continue;

            int minX = Math.max(area.min().getX(), other.area.min().getX());
            int maxX = Math.min(area.max().getX(), other.area.max().getX());
            int minZ = Math.max(area.min().getZ(), other.area.min().getZ());
            int maxZ = Math.min(area.max().getZ(), other.area.max().getZ());
            if (minX > maxX || minZ > maxZ) continue;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    promoteOverlap(state, ownerKey, owner, otherKey, other, x, z);
                }
            }
        }
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

        for (int columnIndex = registration.columns.nextSharedColumn(0);
             columnIndex >= 0;
             columnIndex = registration.columns.nextSharedColumn(columnIndex + 1)) {
            int x = registration.area.worldXForColumn(columnIndex);
            int z = registration.area.worldZForColumn(columnIndex);
            long key = key(x, z);
            SharedColumn shared = state.sharedColumns.get(key);
            registration.columns.setSharedColumn(columnIndex, false);
            if (shared == null || !shared.removeOwner(ownerKey)) continue;

            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REFERENCE_RELEASES);

            if (shared.ownerCount() <= 1) {
                if (shared.ownerCount() != 0) {
                    long remainingKey = shared.ownerAt(0);
                    OwnerRegistration remaining = state.owners.get(remainingKey);
                    if (remaining != null) {
                        syncDemotedOwner(remaining, x, z, shared.currentY);
                    }
                    ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REFERENCE_RELEASES);
                }
                state.sharedColumns.remove(key);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_COLUMNS_RELEASED);
                continue;
            }

            recomputeBounds(state, shared);
            int normalized = normalizeToBounds(shared.currentY, shared.minY, shared.maxY);
            if (normalized != shared.currentY) {
                shared.currentY = normalized;
                syncAllOwners(state, shared, x, z);
            }
        }

        registration.columns.clearSharedColumns();
    }

    public static int getCurrentY(ServerLevel level, int x, int z, int fallbackY) {
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_LOOKUPS);
        SharedColumn shared = state(level).sharedColumns.get(key(x, z));
        return shared == null ? fallbackY : shared.currentY;
    }

    public static int getRegisteredMinY(ServerLevel level, int x, int z, int fallbackMinY) {
        SharedColumn shared = state(level).sharedColumns.get(key(x, z));
        return shared == null ? fallbackMinY : shared.minY;
    }

    /**
     * Moves the cursor only if it still equals expectedY. This prevents a
     * late laser completion from moving the shared cursor back upward after another
     * excavator already advanced the same column.
     */
    public static int advance(ServerLevel level, int x, int z, int expectedY, int nextY) {
        SharedColumn shared = state(level).sharedColumns.get(key(x, z));
        if (shared == null) return nextY;

        if (shared.currentY == expectedY) {
            shared.currentY = nextY;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_ADVANCES);
            return nextY;
        }
        return shared.currentY;
    }

    private static void promoteOverlap(LevelState state, long ownerKey, OwnerRegistration owner, long otherKey, OwnerRegistration other, int x, int z) {
        long key = key(x, z);
        SharedColumn shared = state.sharedColumns.get(key);

        if (shared == null) {
            int seed = normalizedLocalY(other, x, z);
            shared = new SharedColumn(seed, other.area.min().getY(), other.area.max().getY());
            shared.addOwner(otherKey);
            state.sharedColumns.put(key, shared);
            markShared(other, x, z, seed);
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_COLUMNS_CREATED);
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REGISTRATIONS);
        }

        if (shared.hasOwner(ownerKey)) return;

        int oldCurrent = shared.currentY;
        int seed = normalizedLocalY(owner, x, z);
        mergeOwnerRange(shared, seed, owner.area.min().getY(), owner.area.max().getY());
        shared.addOwner(ownerKey);
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.SHARED_HEIGHT_REGISTRATIONS);

        if (shared.currentY != oldCurrent) {
            syncAllOwners(state, shared, x, z);
        } else {
            markShared(owner, x, z, shared.currentY);
        }
    }

    private static void mergeOwnerRange(SharedColumn shared, int seedY, int minY, int maxY) {
        int oldMin = shared.minY;
        int oldMax = shared.maxY;

        if (maxY > oldMax && seedY != ExcavationScanner.NO_SURFACE
                && (shared.currentY == ExcavationScanner.NO_SURFACE || seedY > shared.currentY)) {
            shared.currentY = seedY;
        }

        if (minY < oldMin
                && shared.currentY == ExcavationScanner.NO_SURFACE
                && seedY != ExcavationScanner.NO_SURFACE) {
            shared.currentY = seedY;
        }

        shared.minY = Math.min(oldMin, minY);
        shared.maxY = Math.max(oldMax, maxY);
    }

    private static int normalizedLocalY(OwnerRegistration owner, int x, int z) {
        int columnIndex = owner.area.columnIndex(x, z);
        if (columnIndex < 0 || columnIndex >= owner.columns.currentHeightCount()) {
            return ExcavationScanner.NO_SURFACE;
        }
        int y = owner.columns.currentHeight(columnIndex);
        return y == ExcavationScanner.NO_SURFACE ? y : Math.min(y, owner.area.max().getY());
    }

    private static void markShared(OwnerRegistration owner, int x, int z, int sharedY) {
        int columnIndex = owner.area.columnIndex(x, z);
        if (columnIndex < 0 || columnIndex >= owner.columns.currentHeightCount()) return;

        owner.columns.setSharedColumn(columnIndex, true);
        owner.columns.setCurrentHeight(columnIndex, sharedY);
        if (sharedY != ExcavationScanner.NO_SURFACE && sharedY >= owner.area.min().getY()) {
            owner.columns.ensureActiveColumn(columnIndex);
        } else {
            owner.columns.removeActiveColumnByColumnIndex(columnIndex);
        }
    }

    private static void syncDemotedOwner(OwnerRegistration owner, int x, int z, int sharedY) {
        int columnIndex = owner.area.columnIndex(x, z);
        if (columnIndex < 0 || columnIndex >= owner.columns.currentHeightCount()) return;

        int localY = normalizeToBounds(sharedY, owner.area.min().getY(), owner.area.max().getY());
        owner.columns.setSharedColumn(columnIndex, false);
        owner.columns.setCurrentHeight(columnIndex, localY);
        if (localY != ExcavationScanner.NO_SURFACE) {
            owner.columns.ensureActiveColumn(columnIndex);
        } else {
            owner.columns.removeActiveColumnByColumnIndex(columnIndex);
        }
    }

    private static void syncAllOwners(LevelState state, SharedColumn shared, int x, int z) {
        for (int i = 0; i < shared.ownerCount(); i++) {
            OwnerRegistration owner = state.owners.get(shared.ownerAt(i));
            if (owner != null) markShared(owner, x, z, shared.currentY);
        }
    }

    private static void recomputeBounds(LevelState state, SharedColumn shared) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < shared.ownerCount(); i++) {
            OwnerRegistration owner = state.owners.get(shared.ownerAt(i));
            if (owner == null) continue;
            minY = Math.min(minY, owner.area.min().getY());
            maxY = Math.max(maxY, owner.area.max().getY());
        }
        if (minY != Integer.MAX_VALUE) {
            shared.minY = minY;
            shared.maxY = maxY;
        }
    }

    private static int normalizeToBounds(int y, int minY, int maxY) {
        if (y == ExcavationScanner.NO_SURFACE || y < minY) return ExcavationScanner.NO_SURFACE;
        return Math.min(y, maxY);
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

    private static final class OwnerRegistration {
        private final ExcavatorArea area;
        private final ExcavatorColumnState columns;

        private OwnerRegistration(ExcavatorArea area, ExcavatorColumnState columns) {
            this.area = area;
            this.columns = columns;
        }

        private boolean matches(ExcavatorArea area, ExcavatorColumnState columns) {
            return this.columns == columns
                    && this.area.min().equals(area.min())
                    && this.area.max().equals(area.max());
        }
    }

    private static final class SharedColumn {
        private int currentY;
        private int minY;
        private int maxY;
        private long[] owners = new long[2];
        private int ownerCount;

        private SharedColumn(int currentY, int minY, int maxY) {
            this.currentY = currentY;
            this.minY = minY;
            this.maxY = maxY;
        }

        private int ownerCount() {
            return ownerCount;
        }

        private long ownerAt(int index) {
            return owners[index];
        }

        private boolean hasOwner(long ownerKey) {
            for (int i = 0; i < ownerCount; i++) {
                if (owners[i] == ownerKey) return true;
            }
            return false;
        }

        private void addOwner(long ownerKey) {
            if (hasOwner(ownerKey)) return;
            if (ownerCount == owners.length) owners = Arrays.copyOf(owners, owners.length * 2);
            owners[ownerCount++] = ownerKey;
        }

        private boolean removeOwner(long ownerKey) {
            for (int i = 0; i < ownerCount; i++) {
                if (owners[i] != ownerKey) continue;
                owners[i] = owners[--ownerCount];
                return true;
            }
            return false;
        }
    }

    private static final class LevelState {
        private final Long2ObjectOpenHashMap<SharedColumn> sharedColumns = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<OwnerRegistration> owners = new Long2ObjectOpenHashMap<>();
    }
}
