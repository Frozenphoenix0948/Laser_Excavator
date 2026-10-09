package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExcavatorRunningLimits extends SavedData {
    private record Entry(UUID owner, int tier) {}
    private final Map<String, Entry> running = new HashMap<>();

    private static final SavedDataType<ExcavatorRunningLimits> DATA_TYPE = new SavedDataType<>(
            "laserexcavator_running_limits",
            ExcavatorRunningLimits::new,
            CompoundTag.CODEC.xmap(ExcavatorRunningLimits::load, ExcavatorRunningLimits::save)
    );

    public static ExcavatorRunningLimits get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(DATA_TYPE);
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + ":" + pos.asLong();
    }

    public boolean claim(ServerLevel level, BlockPos pos, UUID owner, int tier) {
        String key = key(level, pos);
        Entry previous = running.get(key);
        if (previous != null && previous.owner.equals(owner) && previous.tier == tier) return true;
        if (LaserExcavatorConfig.RUNNING_LIMITS_ENABLED.get() && activeCount(owner, tier) >= LaserExcavatorConfig.RUNNING_LIMITS[tier].get()) return false;
        running.put(key, new Entry(owner, tier));
        setDirty();
        return true;
    }

    public record RunningExcavator(String dimension, BlockPos position, int tier) {}

    public List<RunningExcavator> list(UUID owner) {
        List<RunningExcavator> result = new ArrayList<>();
        running.forEach((key, entry) -> {
            if (!entry.owner.equals(owner)) return;
            int separator = key.lastIndexOf(':');
            if (separator < 0) return;
            try {
                result.add(new RunningExcavator(key.substring(0, separator), BlockPos.of(Long.parseLong(key.substring(separator + 1))), entry.tier));
            } catch (NumberFormatException ignored) {}
        });
        return result;
    }

    public int activeCount(UUID owner, int tier) {
        int count = 0;
        for (Entry entry : running.values()) if (entry.tier == tier && entry.owner.equals(owner)) count++;
        return count;
    }

    public boolean changeTier(ServerLevel level, BlockPos pos, int tier) {
        Entry old = running.get(key(level, pos));
        return old == null || old.tier == tier || claim(level, pos, old.owner, tier);
    }

    public void release(ServerLevel level, BlockPos pos) {
        if (running.remove(key(level, pos)) != null) setDirty();
    }

    private static ExcavatorRunningLimits load(CompoundTag tag) {
        ExcavatorRunningLimits data = new ExcavatorRunningLimits();
        for (Tag element : tag.getListOrEmpty("Running")) {
            if (!(element instanceof CompoundTag entry)) continue;
            entry.read("Owner", UUIDUtil.CODEC).ifPresent(owner ->
                    data.running.put(entry.getStringOr("Position", ""),
                            new Entry(owner, Math.clamp(entry.getIntOr("Tier", 0), 0, 5))));
        }
        return data;
    }

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        running.forEach((key, entry) -> {
            CompoundTag row = new CompoundTag();
            row.putString("Position", key);
            row.store("Owner", UUIDUtil.CODEC, entry.owner);
            row.putInt("Tier", entry.tier);
            list.add(row);
        });
        tag.put("Running", list);
        return tag;
    }
}
