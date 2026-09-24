package de.balto.laserexcavator.commands;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = LaserExcavator.MODID)
public final class ExcavatorProfilerCommands {
    private ExcavatorProfilerCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("excavatorprofiler")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> report(context.getSource()))
                        .then(Commands.literal("start")
                                .executes(context -> {
                                    ExcavatorProfiler.start();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Excavator profiler started and reset (RUNNING)."),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("stop")
                                .executes(context -> {
                                    ExcavatorProfiler.stop();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Excavator profiler stopped."),
                                            false
                                    );
                                    return report(context.getSource());
                                }))
                        .then(Commands.literal("reset")
                                .executes(context -> {
                                    ExcavatorProfiler.reset();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Excavator profiler statistics reset."),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("report")
                                .executes(context -> report(context.getSource())))
                        .then(Commands.literal("detailed")
                                .executes(context -> detailedReport(context.getSource())))
        );
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ExcavatorProfiler.increment(ExcavatorProfiler.Counter.GLOBAL_SERVER_TICKS);
    }

    private static int report(net.minecraft.commands.CommandSourceStack source) {
        for (String line : ExcavatorProfiler.buildReportLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int detailedReport(net.minecraft.commands.CommandSourceStack source) {
        for (String line : ExcavatorProfiler.buildDetailedReportLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
