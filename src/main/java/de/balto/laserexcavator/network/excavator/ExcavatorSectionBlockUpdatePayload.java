package de.balto.laserexcavator.network.excavator;

import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact clientbound excavation block-state update for one 16x16x16 chunk section.
 *
 * Excavation only sends removals, so the payload does not need to transmit a
 * BlockState for every position. Each short is the vanilla 12-bit section-local
 * position produced by SectionPos.sectionRelativePos(...). The client applies all
 * removals without automatic rerender notifications and explicitly dirties the
 * affected render section once afterwards.
 */
public record ExcavatorSectionBlockUpdatePayload(
        int sectionX,
        int sectionY,
        int sectionZ,
        List<Short> localPositions
) {
    public static final int MAX_POSITIONS = 4096;

    public ExcavatorSectionBlockUpdatePayload {
        localPositions = List.copyOf(localPositions);
        if (localPositions.size() > MAX_POSITIONS) {
            throw new IllegalArgumentException("Too many excavator section updates");
        }
    }

    public static ExcavatorSectionBlockUpdatePayload decode(FriendlyByteBuf buf) {
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_POSITIONS) {
            throw new IllegalArgumentException("Invalid excavator section update size: " + count);
        }
        List<Short> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) positions.add(buf.readShort());
        return new ExcavatorSectionBlockUpdatePayload(x, y, z, positions);
    }

    public static void encode(ExcavatorSectionBlockUpdatePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.sectionX());
        buf.writeVarInt(payload.sectionY());
        buf.writeVarInt(payload.sectionZ());
        buf.writeVarInt(payload.localPositions().size());
        for (short pos : payload.localPositions()) buf.writeShort(pos);
    }
}
