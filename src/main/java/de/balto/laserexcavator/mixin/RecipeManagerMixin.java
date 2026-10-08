package de.balto.laserexcavator.mixin;

import de.balto.laserexcavator.recipe.ConfigurableRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow @Final private HolderLookup.Provider registries;

    @ModifyVariable(method = "apply(Lnet/minecraft/world/item/crafting/RecipeMap;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"), argsOnly = true)
    private RecipeMap laserexcavator$applyConfig(RecipeMap recipes) {
        return ConfigurableRecipes.apply(recipes, registries);
    }
}
