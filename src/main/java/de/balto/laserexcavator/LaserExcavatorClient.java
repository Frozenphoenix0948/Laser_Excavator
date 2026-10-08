package de.balto.laserexcavator;

import de.balto.laserexcavator.block.blockentities.ModBlockEntities;
import de.balto.laserexcavator.block.renderer.ExcavatorForceFieldRenderer;
import de.balto.laserexcavator.block.renderer.ExcavatorTransportMarkerRenderer;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.screen.ExcavatorScreen;
import de.balto.laserexcavator.screen.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = LaserExcavator.MODID, dist = Dist.CLIENT)
public final class LaserExcavatorClient {
    public LaserExcavatorClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        container.registerConfig(ModConfig.Type.CLIENT, LaserExcavatorClientConfig.SPEC, "laserexcavator-client.toml");
        modBus.addListener(LaserExcavatorClient::registerScreens);
        modBus.addListener(LaserExcavatorClient::registerRenderers);
        NeoForge.EVENT_BUS.addListener(LaserExcavatorClient::renderExcavatorTransportMarkers);
    }

    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.EXCAVATOR_MENU.get(), ExcavatorScreen::new);
    }

    public static void renderExcavatorTransportMarkers(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.CLIENT_FRAMES);
        }
        ExcavatorTransportMarkerRenderer.flush(event);
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.EXCAVATOR.get(),
                ExcavatorForceFieldRenderer::new
        );
    }
}
