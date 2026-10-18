package de.balto.laserexcavator.mixin;

import de.balto.laserexcavator.block.excavator.ExcavatorFluidProtection;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class ExcavatorWaterFreezeMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void laserexcavator$freezeWaterTick(Level level, BlockPos pos, FluidState fluid, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel && fluid.is(FluidTags.WATER)
                && ExcavatorFluidProtection.contains(serverLevel, pos)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FLUID_TICKS_SUPPRESSED);
            ci.cancel();
        }
    }

    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void laserexcavator$preventWaterEntry(LevelAccessor level, BlockPos targetPos, BlockState oldState,
                                                  Direction direction, FluidState incoming, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel && incoming.is(FluidTags.WATER)
                && ExcavatorFluidProtection.contains(serverLevel, targetPos)) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.FLUID_ENTRIES_SUPPRESSED);
            ci.cancel();
        }
    }
}
