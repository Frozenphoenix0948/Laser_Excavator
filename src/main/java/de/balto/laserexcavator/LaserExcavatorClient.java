package de.balto.laserexcavator;

import de.balto.laserexcavator.block.blockentities.ModBlockEntities;
import de.balto.laserexcavator.block.renderer.ExcavatorForceFieldRenderer;
import de.balto.laserexcavator.block.renderer.ExcavatorTransportMarkerRenderer;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.screen.ExcavatorScreen;
import de.balto.laserexcavator.screen.ModMenuTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RenderLevelStageEvent;

@Mod.EventBusSubscriber(modid = LaserExcavator.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LaserExcavatorClient {
    private LaserExcavatorClient() {}

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModMenuTypes.EXCAVATOR_MENU.get(), ExcavatorScreen::new));
        MinecraftForge.EVENT_BUS.addListener(LaserExcavatorClient::renderExcavatorTransportMarkers);
        de.balto.laserexcavator.dev.stress.client.ExcavatorStressClientHooks.register();
    }

    public static void renderExcavatorTransportMarkers(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_FRAMES);
        }
        ExcavatorTransportMarkerRenderer.flush(event);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.EXCAVATOR.get(),
                ExcavatorForceFieldRenderer::new
        );
    }
}
