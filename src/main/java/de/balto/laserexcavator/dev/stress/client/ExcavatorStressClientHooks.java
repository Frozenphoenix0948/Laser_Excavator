package de.balto.laserexcavator.dev.stress.client;

import de.balto.laserexcavator.block.renderer.ExcavatorTransportStressHarness;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;

public final class ExcavatorStressClientHooks {
    private ExcavatorStressClientHooks() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ExcavatorTransportStressCommands::register);
        MinecraftForge.EVENT_BUS.addListener(ExcavatorTransportStressHarness::onClientTick);
    }

    public static void renderBeforeTransportFlush(RenderLevelStageEvent event) {
        ExcavatorTransportStressHarness.render(event);
    }
}
