package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExcavatorRunningLimits extends SavedData {
    private static final String DATA_NAME = "laserexcavator_running_limits";

    private record Entry(UUID owner, int tier) {}

    private final Map<String, Entry> running = new HashMap<>();

    public static ExcavatorRunningLimits get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(ExcavatorRunningLimits::load, ExcavatorRunningLimits::new, DATA_NAME);
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + ":" + pos.asLong();
    }

    public boolean claim(ServerLevel level, BlockPos pos, UUID owner, int tier) {
        String key = key(level, pos);
        Entry previous = running.get(key);

        if (previous != null && previous.owner().equals(owner)
                && previous.tier() == tier) {
            return true;
        }

        if (LaserExcavatorConfig.RUNNING_LIMITS_ENABLED.get()
                && activeCount(owner, tier) >= LaserExcavatorConfig.RUNNING_LIMITS[tier].get()) {
            return false;
        }

        running.put(key, new Entry(owner, tier));
        setDirty();
        return true;
    }

    public record RunningExcavator(String dimension, BlockPos position, int tier) {}

    public List<RunningExcavator> list(UUID owner) {
        List<RunningExcavator> result = new ArrayList<>();

        running.forEach((key, entry) -> {
            if (!entry.owner().equals(owner)) return;

            int separator = key.lastIndexOf(':');
            if (separator < 0) return;

            try {
                result.add(new RunningExcavator(
                        key.substring(0, separator),
                        BlockPos.of(Long.parseLong(key.substring(separator + 1))),
                        entry.tier()
                ));
            } catch (NumberFormatException ignored) {
            }
        });

        return result;
    }

    public int activeCount(UUID owner, int tier) {
        int count = 0;

        for (Entry entry : running.values()) {
            if (entry.tier() == tier && entry.owner().equals(owner)) {
                count++;
            }
        }

        return count;
    }

    public boolean changeTier(ServerLevel level, BlockPos pos, int tier) {
        Entry old = running.get(key(level, pos));
        return old == null || old.tier() == tier
                || claim(level, pos, old.owner(), tier);
    }

    public void release(ServerLevel level, BlockPos pos) {
        if (running.remove(key(level, pos)) != null) {
            setDirty();
        }
    }

    private static ExcavatorRunningLimits load(
            CompoundTag tag) {

        ExcavatorRunningLimits data = new ExcavatorRunningLimits();

        ListTag list = tag.getList("Running", Tag.TAG_COMPOUND);
        for (Tag element : list) {
            if (!(element instanceof CompoundTag entry)
                    || !entry.hasUUID("Owner")) {
                continue;
            }

            String position = entry.getString("Position");
            if (position.isEmpty()) continue;

            UUID owner = entry.getUUID("Owner");
            int tier = Mth.clamp(entry.getInt("Tier"), 0, 5);

            data.running.put(position, new Entry(owner, tier));
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();

        running.forEach((key, entry) -> {
            CompoundTag row = new CompoundTag();
            row.putString("Position", key);
            row.putUUID("Owner", entry.owner());
            row.putInt("Tier", entry.tier());
            list.add(row);
        });

        tag.put("Running", list);
        return tag;
    }
}
