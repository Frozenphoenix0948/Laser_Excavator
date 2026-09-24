package de.balto.laserexcavator.commands;

import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.TransportDebugMode;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.BlockUpdateDebugMode;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.RenderingDebugMode;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class ExcavatorRenderDebugCommands {
    private ExcavatorRenderDebugCommands() {}

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("excavatorrenderdebug")
                        .executes(context -> report(context.getSource()))
                        .then(Commands.literal("normal")
                                .executes(context -> set(context.getSource(), TransportDebugMode.NORMAL)))
                        .then(Commands.literal("disabled")
                                .executes(context -> set(context.getSource(), TransportDebugMode.DISABLED)))
                        .then(Commands.literal("process_only")
                                .executes(context -> set(context.getSource(), TransportDebugMode.PROCESS_ONLY)))
                        .then(Commands.literal("markers_only")
                                .executes(context -> set(context.getSource(), TransportDebugMode.MARKERS_ONLY)))
                        .then(Commands.literal("config")
                                .executes(context -> {
                                    LaserExcavatorClientConfig.clearTransportDebugModeOverride();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                    "Excavator transport debug override cleared; using client config: "
                                                            + LaserExcavatorClientConfig.configuredTransportDebugMode().name()
                                            ),
                                            false
                                    );
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("excavatorvisualdebug")
                        .executes(context -> reportRendering(context.getSource()))
                        .then(Commands.literal("normal")
                                .executes(context -> setRendering(context.getSource(), RenderingDebugMode.NORMAL)))
                        .then(Commands.literal("all_disabled")
                                .executes(context -> setRendering(context.getSource(), RenderingDebugMode.ALL_DISABLED)))
                        .then(Commands.literal("config")
                                .executes(context -> {
                                    LaserExcavatorClientConfig.clearRenderingDebugModeOverride();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                    "Excavator whole-render debug override cleared; using client config: "
                                                            + LaserExcavatorClientConfig.configuredRenderingDebugMode().name()
                                            ),
                                            false
                                    );
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("excavatorblockdebug")
                        .executes(context -> reportBlock(context.getSource()))
                        .then(Commands.literal("normal")
                                .executes(context -> setBlock(context.getSource(), BlockUpdateDebugMode.NORMAL)))
                        .then(Commands.literal("no_remesh")
                                .executes(context -> setBlock(context.getSource(), BlockUpdateDebugMode.NO_REMESH)))
                        .then(Commands.literal("no_block_sync")
                                .executes(context -> setBlock(context.getSource(), BlockUpdateDebugMode.NO_BLOCK_SYNC)))
                        .then(Commands.literal("config")
                                .executes(context -> {
                                    LaserExcavatorClientConfig.clearBlockUpdateDebugModeOverride();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                    "Excavator block-update debug override cleared; using client config: "
                                                            + LaserExcavatorClientConfig.configuredBlockUpdateDebugMode().name()
                                            ),
                                            false
                                    );
                                    return 1;
                                }))
        );
    }

    private static int set(net.minecraft.commands.CommandSourceStack source, TransportDebugMode mode) {
        LaserExcavatorClientConfig.setTransportDebugModeOverride(mode);
        source.sendSuccess(
                () -> Component.literal("Excavator transport debug mode: " + mode.name()),
                false
        );
        return 1;
    }

    private static int setRendering(net.minecraft.commands.CommandSourceStack source, RenderingDebugMode mode) {
        LaserExcavatorClientConfig.setRenderingDebugModeOverride(mode);
        source.sendSuccess(
                () -> Component.literal("Excavator whole-render debug mode: " + mode.name()),
                false
        );
        return 1;
    }

    private static int reportRendering(net.minecraft.commands.CommandSourceStack source) {
        RenderingDebugMode effective = LaserExcavatorClientConfig.renderingDebugMode();
        String suffix = LaserExcavatorClientConfig.hasRenderingDebugModeOverride()
                ? " (runtime override)"
                : " (client config)";
        source.sendSuccess(
                () -> Component.literal("Excavator whole-render debug mode: " + effective.name() + suffix),
                false
        );
        return 1;
    }

    private static int setBlock(net.minecraft.commands.CommandSourceStack source, BlockUpdateDebugMode mode) {
        LaserExcavatorClientConfig.setBlockUpdateDebugModeOverride(mode);
        String warning = mode == BlockUpdateDebugMode.NO_BLOCK_SYNC
                ? " (reload/re-enter chunks after this diagnostic; skipped block states cannot be reconstructed locally)"
                : "";
        source.sendSuccess(
                () -> Component.literal("Excavator block-update debug mode: " + mode.name() + warning),
                false
        );
        return 1;
    }

    private static int reportBlock(net.minecraft.commands.CommandSourceStack source) {
        BlockUpdateDebugMode effective = LaserExcavatorClientConfig.blockUpdateDebugMode();
        String suffix = LaserExcavatorClientConfig.hasBlockUpdateDebugModeOverride()
                ? " (runtime override)"
                : " (client config)";
        source.sendSuccess(
                () -> Component.literal("Excavator block-update debug mode: " + effective.name() + suffix),
                false
        );
        return 1;
    }

    private static int report(net.minecraft.commands.CommandSourceStack source) {
        TransportDebugMode effective = LaserExcavatorClientConfig.transportDebugMode();
        String suffix = LaserExcavatorClientConfig.hasTransportDebugModeOverride()
                ? " (runtime override)"
                : " (client config)";
        source.sendSuccess(
                () -> Component.literal("Excavator transport debug mode: " + effective.name() + suffix),
                false
        );
        return 1;
    }
}
