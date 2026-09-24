package de.balto.laserexcavator.dev.stress.client;

import de.balto.laserexcavator.block.renderer.ExcavatorTransportStressHarness;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ExcavatorStressClientHooks {
    private ExcavatorStressClientHooks() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ExcavatorTransportStressCommands::register);
        NeoForge.EVENT_BUS.addListener(ExcavatorTransportStressHarness::onClientTick);
    }

    public static void renderBeforeTransportFlush(RenderLevelStageEvent event) {
        ExcavatorTransportStressHarness.render(event);
    }
}
