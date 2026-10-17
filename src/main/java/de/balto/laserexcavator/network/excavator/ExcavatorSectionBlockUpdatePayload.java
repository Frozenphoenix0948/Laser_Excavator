package de.balto.laserexcavator.network.excavator;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** One section's removals; positions are packed 12-bit section-local coordinates. */
public record ExcavatorSectionBlockUpdatePayload(
        int sectionX,
        int sectionY,
        int sectionZ,
        short[] localPositions
) {
    public static final int MAX_POSITIONS = 4096;

    private static final StreamCodec<RegistryFriendlyByteBuf, short[]> POSITION_ARRAY_CODEC =
            new StreamCodec<>() {
                @Override
                public short[] decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    if (size < 0 || size > MAX_POSITIONS) {
                        throw new IllegalArgumentException("Invalid excavator section update size: " + size);
                    }

                    short[] positions = new short[size];
                    for (int i = 0; i < size; i++) positions[i] = buf.readShort();
                    return positions;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, short[] positions) {
                    int size = positions.length;
                    if (size > MAX_POSITIONS) {
                        throw new IllegalArgumentException("Too many excavator block updates in one section: " + size);
                    }

                    buf.writeVarInt(size);
                    for (short position : positions) {
                        buf.writeShort(position);
                    }
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, ExcavatorSectionBlockUpdatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    ExcavatorSectionBlockUpdatePayload::sectionX,
                    ByteBufCodecs.VAR_INT,
                    ExcavatorSectionBlockUpdatePayload::sectionY,
                    ByteBufCodecs.VAR_INT,
                    ExcavatorSectionBlockUpdatePayload::sectionZ,
                    POSITION_ARRAY_CODEC,
                    ExcavatorSectionBlockUpdatePayload::localPositions,
                    ExcavatorSectionBlockUpdatePayload::new
            );

    public ExcavatorSectionBlockUpdatePayload {
        if (localPositions == null || localPositions.length > MAX_POSITIONS) {
            throw new IllegalArgumentException("Invalid excavator section update positions");
        }
    }

}
