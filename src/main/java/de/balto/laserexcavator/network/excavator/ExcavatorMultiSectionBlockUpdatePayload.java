package de.balto.laserexcavator.network.excavator;

import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;

public record ExcavatorMultiSectionBlockUpdatePayload(List<ExcavatorSectionBlockUpdatePayload> sections) {
    public static final int MAX_BYTES = 32 * 1024;
    public static final int MAX_SECTIONS = 4096;

    public ExcavatorMultiSectionBlockUpdatePayload {
        sections = List.copyOf(sections);
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) throw new IllegalArgumentException("Invalid section count");
        int bytes = varIntBytes(sections.size());
        for (ExcavatorSectionBlockUpdatePayload section : sections) bytes += encodedSectionBytes(section);
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Packet too large");
    }

    public static ExcavatorMultiSectionBlockUpdatePayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 1 || count > MAX_SECTIONS) throw new IllegalArgumentException("Invalid section count");
        List<ExcavatorSectionBlockUpdatePayload> sections = new ArrayList<>(count);
        int bytes = varIntBytes(count);
        for (int i = 0; i < count; i++) {
            ExcavatorSectionBlockUpdatePayload section = ExcavatorSectionBlockUpdatePayload.decode(buf);
            bytes += encodedSectionBytes(section);
            if (bytes > MAX_BYTES) throw new IllegalArgumentException("Packet too large");
            sections.add(section);
        }
        return new ExcavatorMultiSectionBlockUpdatePayload(sections);
    }

    public static void encode(ExcavatorMultiSectionBlockUpdatePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.sections().size());
        for (ExcavatorSectionBlockUpdatePayload section : payload.sections())
            ExcavatorSectionBlockUpdatePayload.encode(section, buf);
    }

    public static int encodedSectionBytes(ExcavatorSectionBlockUpdatePayload section) {
        return varIntBytes(section.sectionX()) + varIntBytes(section.sectionY()) + varIntBytes(section.sectionZ())
                + varIntBytes(section.localPositions().length) + 2 * section.localPositions().length;
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7f) != 0) {value >>>= 7; bytes++;}
        return bytes;
    }
}
