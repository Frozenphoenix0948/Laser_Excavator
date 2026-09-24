package de.balto.laserexcavator.block.blockentities;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LaserExcavator.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ExcavatorBlockEntity>> EXCAVATOR =
            BLOCK_ENTITIES.register(
                    "excavator",
                    () -> BlockEntityType.Builder.of(ExcavatorBlockEntity::new, ModBlocks.EXCAVATOR.get()).build(null)
            );
}
