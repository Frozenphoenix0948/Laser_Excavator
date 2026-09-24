package de.balto.laserexcavator.network.excavator;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static de.balto.laserexcavator.LaserExcavator.MODID;

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
) implements CustomPacketPayload {

    public static final int MAX_POSITIONS = 4096;

    public static final Type<ExcavatorSectionBlockUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MODID, "excavator_section_block_update")
    );

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Short>> POSITION_LIST_CODEC =
            new StreamCodec<>() {
                @Override
                public List<Short> decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    if (size < 0 || size > MAX_POSITIONS) {
                        throw new IllegalArgumentException("Invalid excavator section update size: " + size);
                    }

                    List<Short> positions = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        positions.add(buf.readShort());
                    }
                    return positions;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, List<Short> positions) {
                    int size = positions.size();
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
                    POSITION_LIST_CODEC,
                    ExcavatorSectionBlockUpdatePayload::localPositions,
                    ExcavatorSectionBlockUpdatePayload::new
            );

    public ExcavatorSectionBlockUpdatePayload {
        localPositions = List.copyOf(localPositions);
        if (localPositions.size() > MAX_POSITIONS) {
            throw new IllegalArgumentException("Too many excavator block updates in one section: " + localPositions.size());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
