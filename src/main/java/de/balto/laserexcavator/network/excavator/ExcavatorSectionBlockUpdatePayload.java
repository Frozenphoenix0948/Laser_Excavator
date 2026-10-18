package de.balto.laserexcavator.network.excavator;

import net.minecraft.network.FriendlyByteBuf;

/** One section's removals; positions are packed 12-bit section-local coordinates. */
public record ExcavatorSectionBlockUpdatePayload(
        int sectionX,
        int sectionY,
        int sectionZ,
        short[] localPositions
) {
    public static final int MAX_POSITIONS = 4096;

    public static ExcavatorSectionBlockUpdatePayload decode(FriendlyByteBuf buf) {
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_POSITIONS) throw new IllegalArgumentException("Invalid section position count");
        short[] positions = new short[count];
        for (int i = 0; i < count; i++) positions[i] = buf.readShort();
        return new ExcavatorSectionBlockUpdatePayload(x, y, z, positions);
    }

    public static void encode(ExcavatorSectionBlockUpdatePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.sectionX());
        buf.writeVarInt(payload.sectionY());
        buf.writeVarInt(payload.sectionZ());
        buf.writeVarInt(payload.localPositions().length);
        for (short pos : payload.localPositions()) buf.writeShort(pos);
    }

    public ExcavatorSectionBlockUpdatePayload {
        if (localPositions == null || localPositions.length > MAX_POSITIONS) {
            throw new IllegalArgumentException("Invalid excavator section positions");
        }
    }
}
