package de.balto.laserexcavator.commands;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.config.LaserExcavatorConfig.NetworkDebugMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ExcavatorNetworkDebugCommands {
    private ExcavatorNetworkDebugCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("excavatornetworkdebug")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> report(context.getSource()))
                        .then(Commands.literal("normal")
                                .executes(context -> set(context.getSource(), NetworkDebugMode.NORMAL)))
                        .then(Commands.literal("no_visuals")
                                .executes(context -> set(context.getSource(), NetworkDebugMode.NO_VISUALS)))
                        .then(Commands.literal("disabled")
                                .executes(context -> set(context.getSource(), NetworkDebugMode.DISABLED)))
                        .then(Commands.literal("config")
                                .executes(context -> {
                                    LaserExcavatorConfig.clearNetworkDebugModeOverride();
                                    NetworkDebugMode configured = LaserExcavatorConfig.configuredNetworkDebugMode();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                    "Excavator network debug override cleared; using server config: "
                                                            + configured.name()
                                            ),
                                            false
                                    );
                                    return 1;
                                }))
        );
    }

    private static int set(CommandSourceStack source, NetworkDebugMode mode) {
        LaserExcavatorConfig.setNetworkDebugModeOverride(mode);
        String warning = mode == NetworkDebugMode.DISABLED
                ? " (visual + excavation block-update payloads suppressed server-side; reload/re-enter chunks after this diagnostic)"
                : mode == NetworkDebugMode.NO_VISUALS
                ? " (laser/transport payloads suppressed server-side)"
                : "";
        source.sendSuccess(
                () -> Component.literal("Excavator network debug mode: " + mode.name() + warning),
                false
        );
        return 1;
    }

    private static int report(CommandSourceStack source) {
        NetworkDebugMode effective = LaserExcavatorConfig.networkDebugMode();
        String suffix = LaserExcavatorConfig.hasNetworkDebugModeOverride()
                ? " (runtime override)"
                : " (server config)";
        source.sendSuccess(
                () -> Component.literal("Excavator network debug mode: " + effective.name() + suffix),
                false
        );
        return 1;
    }
}
