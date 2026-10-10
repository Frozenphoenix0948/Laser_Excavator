package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.block.blockentities.ModBlockEntities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class ExcavatorCapabilities {
    private ExcavatorCapabilities() {}

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, ModBlockEntities.EXCAVATOR.get(),
                (blockEntity, side) -> blockEntity.getTransferItemHandler(side));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, ModBlockEntities.EXCAVATOR.get(),
                (blockEntity, side) -> blockEntity.getTransferEnergyHandler());
    }
}
