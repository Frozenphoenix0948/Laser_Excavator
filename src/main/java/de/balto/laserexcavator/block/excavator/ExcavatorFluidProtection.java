package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * This update blocks fluids from being updated in the excavation area.
 */
public final class ExcavatorFluidProtection {
    private static final Map<ServerLevel, Index> BY_LEVEL = new WeakHashMap<>();

    private ExcavatorFluidProtection() {}

    public static void update(ServerLevel level, BlockPos owner, @Nullable ExcavatorArea area) {
        if (!LaserExcavatorConfig.SUPPRESS_NEIGHBOR_UPDATES.get()) area = null;
        Index index = BY_LEVEL.get(level);
        if (area == null) {
            if (index != null) {
                index.remove(owner.asLong());
                if (index.byOwner.isEmpty()) BY_LEVEL.remove(level);
            }
            return;
        }
        if (index == null) {
            index = new Index();
            BY_LEVEL.put(level, index);
        }
        index.add(owner.asLong(), area);
    }

    public static boolean contains(ServerLevel level, BlockPos pos) {
        if (!LaserExcavatorConfig.SUPPRESS_NEIGHBOR_UPDATES.get()) return false;
        Index index = BY_LEVEL.get(level);
        if (index == null) return false;
        ArrayList<Volume> candidates = index.byChunk.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
        if (candidates == null) return false;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (int i = 0; i < candidates.size(); i++) {
            Volume v = candidates.get(i);
            if (x >= v.minX && x <= v.maxX && y >= v.minY && y <= v.maxY && z >= v.minZ && z <= v.maxZ) return true;
        }
        return false;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    public static void clearAll() {
        BY_LEVEL.clear();
    }

    private static final class Index {
        private final Long2ObjectOpenHashMap<Volume> byOwner = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<ArrayList<Volume>> byChunk = new Long2ObjectOpenHashMap<>();

        private void add(long owner, ExcavatorArea area) {
            BlockPos min = area.min();
            BlockPos max = area.max();
            Volume previous = byOwner.get(owner);
            if (previous != null && previous.matches(min, max)) return;
            remove(owner);
            Volume v = new Volume(owner, min, max);
            byOwner.put(owner, v);
            for (int cx = v.minX >> 4; cx <= v.maxX >> 4; cx++) {
                for (int cz = v.minZ >> 4; cz <= v.maxZ >> 4; cz++) {
                    long key = chunkKey(cx, cz);
                    byChunk.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(v);
                }
            }
        }

        private void remove(long owner) {
            Volume v = byOwner.remove(owner);
            if (v == null) return;
            for (int cx = v.minX >> 4; cx <= v.maxX >> 4; cx++) {
                for (int cz = v.minZ >> 4; cz <= v.maxZ >> 4; cz++) {
                    long key = chunkKey(cx, cz);
                    ArrayList<Volume> list = byChunk.get(key);
                    if (list != null) {
                        list.remove(v);
                        if (list.isEmpty()) byChunk.remove(key);
                    }
                }
            }
        }
    }

    private static final class Volume {
        final long owner;
        final int minX, minY, minZ, maxX, maxY, maxZ;

        Volume(long owner, BlockPos min, BlockPos max) {
            this.owner = owner;
            minX = min.getX(); minY = min.getY(); minZ = min.getZ();
            maxX = max.getX(); maxY = max.getY(); maxZ = max.getZ();
        }

        boolean matches(BlockPos min, BlockPos max) {
            return minX == min.getX() && minY == min.getY() && minZ == min.getZ() && maxX == max.getX() && maxY == max.getY() && maxZ == max.getZ();
        }
    }
}
