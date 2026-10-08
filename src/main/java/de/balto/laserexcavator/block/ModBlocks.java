package de.balto.laserexcavator.block;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.excavator.ExcavatorBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LaserExcavator.MODID);

    public static final DeferredBlock<ExcavatorBlock> EXCAVATOR = BLOCKS.register(
            "excavator",
            () -> new ExcavatorBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(LaserExcavator.MODID, "excavator"))).strength(5.0F).requiresCorrectToolForDrops().sound(SoundType.METAL))
    );
}
