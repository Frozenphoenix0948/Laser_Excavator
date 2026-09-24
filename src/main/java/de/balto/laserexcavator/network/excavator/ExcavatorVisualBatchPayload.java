package de.balto.laserexcavator.network.excavator;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static de.balto.laserexcavator.LaserExcavator.MODID;

/**
 * Compact clientbound visual payload containing several server ticks of
 * excavator visual starts for one tracking chunk.
 *
 * Decoded events are stored in flat payload-wide arrays. Group metadata contains
 * the excavator position plus start/count slices into the laser and transport arrays,
 * keeping allocation count independent of the number of excavator groups.
 *
 * Per-event positions remain signed VarInt deltas from the excavator, so
 * excavation areas are not constrained to a fixed size.
 */
public final class ExcavatorVisualBatchPayload implements CustomPacketPayload {
    /** Hard protocol safety bound. The configurable packet cap may be lower. */
    public static final int MAX_TOTAL_EVENTS = 4096;
    private static final int MAX_GROUPS = MAX_TOTAL_EVENTS;
    private static final int MAX_STACK_PALETTE = MAX_TOTAL_EVENTS;

    public static final Type<ExcavatorVisualBatchPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MODID, "excavator_visual_batch")
    );

    private final long serverGameTime;

    // Group metadata. Each group's event arrays are represented by a contiguous
    // slice [start, start + count) into the payload-wide flat arrays below.
    private final BlockPos[] excavatorPositions;
    private final int[] groupLaserStart;
    private final int[] groupLaserCount;
    private final int[] groupTransportStart;
    private final int[] groupTransportCount;

    // Flat laser arrays.
    private final int[] laserDeltaX;
    private final int[] laserDeltaY;
    private final int[] laserDeltaZ;
    private final int[] laserAgeTicks;

    // Flat transport arrays.
    private final int[] transportDeltaX;
    private final int[] transportDeltaY;
    private final int[] transportDeltaZ;
    private final ItemStack[] transportStacks;
    private final int[] transportDurationTicks;
    private final int[] transportForceFieldYOffset;
    private final int[] transportAgeTicks;

    private final int eventCount;

    public static final StreamCodec<RegistryFriendlyByteBuf, ExcavatorVisualBatchPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ExcavatorVisualBatchPayload decode(RegistryFriendlyByteBuf buf) {
                    long serverGameTime = buf.readVarLong();

                    int paletteSize = readBoundedCount(buf, "visual stack palette", MAX_STACK_PALETTE);
                    ItemStack[] palette = new ItemStack[paletteSize];
                    for (int i = 0; i < paletteSize; i++) {
                        palette[i] = ItemStack.STREAM_CODEC.decode(buf);
                    }

                    int groupCount = readBoundedCount(buf, "excavator visual groups", MAX_GROUPS);
                    BlockPos[] excavatorPositions = new BlockPos[groupCount];
                    int[] groupLaserStart = new int[groupCount];
                    int[] groupLaserCount = new int[groupCount];
                    int[] groupTransportStart = new int[groupCount];
                    int[] groupTransportCount = new int[groupCount];

                    int totalLasers = 0;
                    int totalTransports = 0;
                    for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                        excavatorPositions[groupIndex] = BlockPos.STREAM_CODEC.decode(buf).immutable();

                        int remaining = MAX_TOTAL_EVENTS - totalLasers - totalTransports;
                        int laserCount = readBoundedCount(buf, "laser events", remaining);
                        remaining -= laserCount;
                        int transportCount = readBoundedCount(buf, "transport events", remaining);

                        groupLaserStart[groupIndex] = totalLasers;
                        groupLaserCount[groupIndex] = laserCount;
                        groupTransportStart[groupIndex] = totalTransports;
                        groupTransportCount[groupIndex] = transportCount;

                        totalLasers += laserCount;
                        totalTransports += transportCount;
                    }

                    int[] laserDeltaX = new int[totalLasers];
                    int[] laserDeltaY = new int[totalLasers];
                    int[] laserDeltaZ = new int[totalLasers];
                    int[] laserAgeTicks = new int[totalLasers];

                    int[] transportDeltaX = new int[totalTransports];
                    int[] transportDeltaY = new int[totalTransports];
                    int[] transportDeltaZ = new int[totalTransports];
                    ItemStack[] transportStacks = new ItemStack[totalTransports];
                    int[] transportDurationTicks = new int[totalTransports];
                    int[] transportForceFieldYOffset = new int[totalTransports];
                    int[] transportAgeTicks = new int[totalTransports];

                    // Event bodies follow group metadata so every group occupies one
                    // contiguous slice in the payload-wide arrays.
                    for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                        int laserStart = groupLaserStart[groupIndex];
                        int laserEnd = laserStart + groupLaserCount[groupIndex];
                        for (int i = laserStart; i < laserEnd; i++) {
                            laserDeltaX[i] = readSignedVarInt(buf);
                            laserDeltaY[i] = readSignedVarInt(buf);
                            laserDeltaZ[i] = readSignedVarInt(buf);
                            laserAgeTicks[i] = readNonNegativeVarInt(buf, "laser age");
                        }

                        int transportStart = groupTransportStart[groupIndex];
                        int transportEnd = transportStart + groupTransportCount[groupIndex];
                        for (int i = transportStart; i < transportEnd; i++) {
                            transportDeltaX[i] = readSignedVarInt(buf);
                            transportDeltaY[i] = readSignedVarInt(buf);
                            transportDeltaZ[i] = readSignedVarInt(buf);
                            int paletteIndex = readNonNegativeVarInt(buf, "transport palette index");
                            if (paletteIndex >= palette.length) {
                                throw new IllegalArgumentException(
                                        "Invalid excavator visual stack palette index: " + paletteIndex
                                );
                            }
                            transportStacks[i] = palette[paletteIndex];
                            transportDurationTicks[i] = readPositiveVarInt(buf, "transport duration");
                            transportForceFieldYOffset[i] = readSignedVarInt(buf);
                            transportAgeTicks[i] = readNonNegativeVarInt(buf, "transport age");
                        }
                    }

                    return new ExcavatorVisualBatchPayload(
                            serverGameTime,
                            excavatorPositions,
                            groupLaserStart,
                            groupLaserCount,
                            groupTransportStart,
                            groupTransportCount,
                            laserDeltaX,
                            laserDeltaY,
                            laserDeltaZ,
                            laserAgeTicks,
                            transportDeltaX,
                            transportDeltaY,
                            transportDeltaZ,
                            transportStacks,
                            transportDurationTicks,
                            transportForceFieldYOffset,
                            transportAgeTicks
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ExcavatorVisualBatchPayload payload) {
                    buf.writeVarLong(payload.serverGameTime);

                    List<ItemStack> palette = buildStackPalette(payload.transportStacks);
                    buf.writeVarInt(palette.size());
                    for (ItemStack stack : palette) {
                        ItemStack.STREAM_CODEC.encode(buf, stack);
                    }

                    int groupCount = payload.groupCount();
                    buf.writeVarInt(groupCount);

                    // Metadata precedes event bodies so the decoder can allocate exact
                    // payload-wide array sizes before reading events.
                    for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                        BlockPos.STREAM_CODEC.encode(buf, payload.excavatorPositions[groupIndex]);
                        buf.writeVarInt(payload.groupLaserCount[groupIndex]);
                        buf.writeVarInt(payload.groupTransportCount[groupIndex]);
                    }

                    for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                        int laserStart = payload.groupLaserStart[groupIndex];
                        int laserEnd = laserStart + payload.groupLaserCount[groupIndex];
                        for (int i = laserStart; i < laserEnd; i++) {
                            writeSignedVarInt(buf, payload.laserDeltaX[i]);
                            writeSignedVarInt(buf, payload.laserDeltaY[i]);
                            writeSignedVarInt(buf, payload.laserDeltaZ[i]);
                            buf.writeVarInt(Math.max(0, payload.laserAgeTicks[i]));
                        }

                        int transportStart = payload.groupTransportStart[groupIndex];
                        int transportEnd = transportStart + payload.groupTransportCount[groupIndex];
                        for (int i = transportStart; i < transportEnd; i++) {
                            writeSignedVarInt(buf, payload.transportDeltaX[i]);
                            writeSignedVarInt(buf, payload.transportDeltaY[i]);
                            writeSignedVarInt(buf, payload.transportDeltaZ[i]);
                            buf.writeVarInt(findPaletteIndex(palette, payload.transportStacks[i]));
                            buf.writeVarInt(Math.max(1, payload.transportDurationTicks[i]));
                            writeSignedVarInt(buf, payload.transportForceFieldYOffset[i]);
                            buf.writeVarInt(Math.max(0, payload.transportAgeTicks[i]));
                        }
                    }
                }
            };

    public ExcavatorVisualBatchPayload(
            long serverGameTime,
            BlockPos[] excavatorPositions,
            int[] groupLaserStart,
            int[] groupLaserCount,
            int[] groupTransportStart,
            int[] groupTransportCount,
            int[] laserDeltaX,
            int[] laserDeltaY,
            int[] laserDeltaZ,
            int[] laserAgeTicks,
            int[] transportDeltaX,
            int[] transportDeltaY,
            int[] transportDeltaZ,
            ItemStack[] transportStacks,
            int[] transportDurationTicks,
            int[] transportForceFieldYOffset,
            int[] transportAgeTicks
    ) {
        this.serverGameTime = serverGameTime;
        this.excavatorPositions = excavatorPositions;
        this.groupLaserStart = groupLaserStart;
        this.groupLaserCount = groupLaserCount;
        this.groupTransportStart = groupTransportStart;
        this.groupTransportCount = groupTransportCount;
        this.laserDeltaX = laserDeltaX;
        this.laserDeltaY = laserDeltaY;
        this.laserDeltaZ = laserDeltaZ;
        this.laserAgeTicks = laserAgeTicks;
        this.transportDeltaX = transportDeltaX;
        this.transportDeltaY = transportDeltaY;
        this.transportDeltaZ = transportDeltaZ;
        this.transportStacks = transportStacks;
        this.transportDurationTicks = transportDurationTicks;
        this.transportForceFieldYOffset = transportForceFieldYOffset;
        this.transportAgeTicks = transportAgeTicks;

        int groupCount = excavatorPositions.length;
        requireLength("group laser start", groupLaserStart.length, groupCount);
        requireLength("group laser count", groupLaserCount.length, groupCount);
        requireLength("group transport start", groupTransportStart.length, groupCount);
        requireLength("group transport count", groupTransportCount.length, groupCount);
        if (groupCount > MAX_GROUPS) {
            throw new IllegalArgumentException("Too many excavator groups in one visual payload: " + groupCount);
        }

        int laserCount = laserDeltaX.length;
        requireLength("laser deltaY", laserDeltaY.length, laserCount);
        requireLength("laser deltaZ", laserDeltaZ.length, laserCount);
        requireLength("laser age", laserAgeTicks.length, laserCount);

        int transportCount = transportDeltaX.length;
        requireLength("transport deltaY", transportDeltaY.length, transportCount);
        requireLength("transport deltaZ", transportDeltaZ.length, transportCount);
        requireLength("transport stacks", transportStacks.length, transportCount);
        requireLength("transport duration", transportDurationTicks.length, transportCount);
        requireLength("transport force-field Y", transportForceFieldYOffset.length, transportCount);
        requireLength("transport age", transportAgeTicks.length, transportCount);

        int total = laserCount + transportCount;
        if (total > MAX_TOTAL_EVENTS) {
            throw new IllegalArgumentException("Too many visual events in one payload: " + total);
        }

        int expectedLaserStart = 0;
        int expectedTransportStart = 0;
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            if (groupLaserStart[groupIndex] != expectedLaserStart || groupLaserCount[groupIndex] < 0) {
                throw new IllegalArgumentException("Invalid/non-contiguous laser slice for visual group " + groupIndex);
            }
            if (groupTransportStart[groupIndex] != expectedTransportStart || groupTransportCount[groupIndex] < 0) {
                throw new IllegalArgumentException("Invalid/non-contiguous transport slice for visual group " + groupIndex);
            }
            expectedLaserStart += groupLaserCount[groupIndex];
            expectedTransportStart += groupTransportCount[groupIndex];
        }
        if (expectedLaserStart != laserCount || expectedTransportStart != transportCount) {
            throw new IllegalArgumentException("Visual group slices do not cover their flat event arrays");
        }

        this.eventCount = total;
    }

    public long serverGameTime() { return serverGameTime; }
    public int groupCount() { return excavatorPositions.length; }
    public int eventCount() { return eventCount; }

    public BlockPos excavatorPos(int groupIndex) { return excavatorPositions[groupIndex]; }
    public int groupLaserStart(int groupIndex) { return groupLaserStart[groupIndex]; }
    public int groupLaserCount(int groupIndex) { return groupLaserCount[groupIndex]; }
    public int groupTransportStart(int groupIndex) { return groupTransportStart[groupIndex]; }
    public int groupTransportCount(int groupIndex) { return groupTransportCount[groupIndex]; }

    public int laserDeltaX(int eventIndex) { return laserDeltaX[eventIndex]; }
    public int laserDeltaY(int eventIndex) { return laserDeltaY[eventIndex]; }
    public int laserDeltaZ(int eventIndex) { return laserDeltaZ[eventIndex]; }
    public int laserAgeTicks(int eventIndex) { return laserAgeTicks[eventIndex]; }

    public int transportDeltaX(int eventIndex) { return transportDeltaX[eventIndex]; }
    public int transportDeltaY(int eventIndex) { return transportDeltaY[eventIndex]; }
    public int transportDeltaZ(int eventIndex) { return transportDeltaZ[eventIndex]; }
    public ItemStack transportStack(int eventIndex) { return transportStacks[eventIndex]; }
    public int transportDurationTicks(int eventIndex) { return transportDurationTicks[eventIndex]; }
    public int transportForceFieldYOffset(int eventIndex) { return transportForceFieldYOffset[eventIndex]; }
    public int transportAgeTicks(int eventIndex) { return transportAgeTicks[eventIndex]; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static int readBoundedCount(RegistryFriendlyByteBuf buf, String label, int max) {
        int value = buf.readVarInt();
        if (value < 0 || value > max) {
            throw new IllegalArgumentException("Invalid " + label + " count: " + value + " (max " + max + ")");
        }
        return value;
    }

    private static int readPositiveVarInt(RegistryFriendlyByteBuf buf, String label) {
        int value = buf.readVarInt();
        if (value <= 0) throw new IllegalArgumentException("Invalid " + label + ": " + value);
        return value;
    }

    private static int readNonNegativeVarInt(RegistryFriendlyByteBuf buf, String label) {
        int value = buf.readVarInt();
        if (value < 0) throw new IllegalArgumentException("Invalid " + label + ": " + value);
        return value;
    }

    private static void writeSignedVarInt(RegistryFriendlyByteBuf buf, int value) {
        buf.writeVarInt((value << 1) ^ (value >> 31));
    }

    private static int readSignedVarInt(RegistryFriendlyByteBuf buf) {
        int encoded = buf.readVarInt();
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static void requireLength(String label, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Mismatched " + label + " array length: " + actual + " != " + expected);
        }
    }

    private static List<ItemStack> buildStackPalette(ItemStack[] transportStacks) {
        List<ItemStack> palette = new ArrayList<>();
        for (ItemStack stack : transportStacks) {
            if (findPaletteIndexOrMinusOne(palette, stack) >= 0) continue;
            if (palette.size() >= MAX_STACK_PALETTE) {
                throw new IllegalArgumentException("Too many unique transport visual stacks in one payload");
            }
            palette.add(stack);
        }
        return palette;
    }

    private static int findPaletteIndex(List<ItemStack> palette, ItemStack stack) {
        int index = findPaletteIndexOrMinusOne(palette, stack);
        if (index < 0) {
            throw new IllegalArgumentException("Transport visual stack was not present in payload palette");
        }
        return index;
    }

    private static int findPaletteIndexOrMinusOne(List<ItemStack> palette, ItemStack stack) {
        for (int i = 0; i < palette.size(); i++) {
            if (ItemStack.isSameItemSameComponents(palette.get(i), stack)) return i;
        }
        return -1;
    }
}
