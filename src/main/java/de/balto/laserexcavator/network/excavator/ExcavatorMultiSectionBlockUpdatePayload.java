package de.balto.laserexcavator.network.excavator;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

import static de.balto.laserexcavator.LaserExcavator.MODID;

/** Due section removals for one player, capped at 32 KiB encoded. */
public record ExcavatorMultiSectionBlockUpdatePayload(
        List<ExcavatorSectionBlockUpdatePayload> sections
) implements CustomPacketPayload {
    public static final int MAX_BYTES = 32 * 1024;
    public static final int MAX_SECTIONS = 4096;

    public static final Type<ExcavatorMultiSectionBlockUpdatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MODID, "excavator_multi_section_block_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExcavatorMultiSectionBlockUpdatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ExcavatorMultiSectionBlockUpdatePayload decode(RegistryFriendlyByteBuf buf) {
                    int count = buf.readVarInt();
                    if (count < 1 || count > MAX_SECTIONS) throw new IllegalArgumentException("Invalid section count: " + count);
                    int bytes = varIntBytes(count);
                    List<ExcavatorSectionBlockUpdatePayload> sections = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        ExcavatorSectionBlockUpdatePayload section = ExcavatorSectionBlockUpdatePayload.STREAM_CODEC.decode(buf);
                        bytes += encodedSectionBytes(section);
                        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Excavator section packet too large");
                        sections.add(section);
                    }
                    return new ExcavatorMultiSectionBlockUpdatePayload(sections);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ExcavatorMultiSectionBlockUpdatePayload payload) {
                    buf.writeVarInt(payload.sections.size());
                    for (ExcavatorSectionBlockUpdatePayload section : payload.sections) {
                        ExcavatorSectionBlockUpdatePayload.STREAM_CODEC.encode(buf, section);
                    }
                }
            };

    public ExcavatorMultiSectionBlockUpdatePayload {
        sections = List.copyOf(sections);
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("Invalid excavator multi-section count");
        }
        int bytes = varIntBytes(sections.size());
        for (ExcavatorSectionBlockUpdatePayload section : sections) bytes += encodedSectionBytes(section);
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Excavator multi-section packet exceeds 32 KiB");
    }

    public static int encodedSectionBytes(ExcavatorSectionBlockUpdatePayload section) {
        return varIntBytes(section.sectionX()) + varIntBytes(section.sectionY())
                + varIntBytes(section.sectionZ()) + varIntBytes(section.localPositions().length)
                + 2 * section.localPositions().length;
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
