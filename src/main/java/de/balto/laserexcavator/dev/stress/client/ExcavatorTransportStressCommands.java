package de.balto.laserexcavator.dev.stress.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.balto.laserexcavator.block.renderer.ExcavatorTransportStressHarness;
import de.balto.laserexcavator.block.renderer.ExcavatorTransportStressHarness.ComponentKind;
import de.balto.laserexcavator.block.renderer.ExcavatorTransportStressHarness.LodTier;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class ExcavatorTransportStressCommands {
    private ExcavatorTransportStressCommands() {}

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("excavatortransportstress")
                        .requires(source -> LaserExcavatorConfig.stressTestCommandsEnabled())
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("start")
                                .executes(context -> {
                                    ExcavatorTransportStressHarness.start();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Synthetic excavator transport stream started."),
                                            false
                                    );
                                    return status(context.getSource());
                                }))
                        .then(Commands.literal("stop")
                                .executes(context -> {
                                    ExcavatorTransportStressHarness.stop();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Synthetic excavator transport stream stopped and cleared."),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("reanchor")
                                .executes(context -> {
                                    ExcavatorTransportStressHarness.reanchor();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Synthetic transport planes re-anchored in front of the player."),
                                            false
                                    );
                                    return status(context.getSource());
                                }))
                        .then(Commands.literal("tier")
                                .then(Commands.literal("auto").executes(context -> setTier(context.getSource(), LodTier.AUTO)))
                                .then(Commands.literal("individual").executes(context -> setTier(context.getSource(), LodTier.INDIVIDUAL)))
                                .then(Commands.literal("batched").executes(context -> setTier(context.getSource(), LodTier.BATCHED)))
                                .then(Commands.literal("hidden").executes(context -> setTier(context.getSource(), LodTier.HIDDEN))))
                        .then(Commands.literal("component")
                                .then(Commands.literal("block").executes(context -> setComponent(context.getSource(), ComponentKind.BLOCK)))
                                .then(Commands.literal("item").executes(context -> setComponent(context.getSource(), ComponentKind.ITEM))))
                        .then(Commands.literal("rate")
                                .then(Commands.argument("transportsPerTick", IntegerArgumentType.integer(1, 4096))
                                        .executes(context -> {
                                            int value = IntegerArgumentType.getInteger(context, "transportsPerTick");
                                            ExcavatorTransportStressHarness.setTransportsPerTick(value);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Synthetic transport rate: " + value + "/tick"),
                                                    false
                                            );
                                            return 1;
                                        })))
                        .then(Commands.literal("lanes")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1024))
                                        .executes(context -> {
                                            int value = IntegerArgumentType.getInteger(context, "count");
                                            ExcavatorTransportStressHarness.setLaneCount(value);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Synthetic destination lanes: " + value),
                                                    false
                                            );
                                            return 1;
                                        })))
                        .then(Commands.literal("distance")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(4, 512))
                                        .executes(context -> {
                                            int value = IntegerArgumentType.getInteger(context, "blocks");
                                            ExcavatorTransportStressHarness.setDestinationDistance(value);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Destination plane distance: " + value + " blocks"),
                                                    false
                                            );
                                            return 1;
                                        })))
                        .then(Commands.literal("separation")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(4, 256))
                                        .executes(context -> {
                                            int value = IntegerArgumentType.getInteger(context, "blocks");
                                            ExcavatorTransportStressHarness.setPlaneSeparation(value);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("A->B plane separation: " + value + " blocks"),
                                                    false
                                            );
                                            return 1;
                                        })))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
        );
    }

    private static int setTier(net.minecraft.commands.CommandSourceStack source, LodTier tier) {
        ExcavatorTransportStressHarness.setLodTier(tier);
        source.sendSuccess(
                () -> Component.literal("Synthetic transport LOD tier: " + tier.name()),
                false
        );
        return 1;
    }

    private static int setComponent(net.minecraft.commands.CommandSourceStack source, ComponentKind kind) {
        ExcavatorTransportStressHarness.setComponentKind(kind);
        source.sendSuccess(
                () -> Component.literal("Synthetic transport component: " + kind.name()
                        + " (existing synthetic transports cleared)"),
                false
        );
        return 1;
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(
                () -> Component.literal("========== Synthetic Transport Stress =========="),
                false
        );
        source.sendSuccess(
                () -> Component.literal("Status: " + (ExcavatorTransportStressHarness.isRunning() ? "RUNNING" : "STOPPED")
                        + " | tier=" + ExcavatorTransportStressHarness.lodTier().name()
                        + " | component=" + ExcavatorTransportStressHarness.componentKind().name()),
                false
        );
        source.sendSuccess(
                () -> Component.literal("Rate: " + ExcavatorTransportStressHarness.transportsPerTick()
                        + "/tick | lanes=" + ExcavatorTransportStressHarness.laneCount()
                        + " | active=" + ExcavatorTransportStressHarness.activeTransportCount()
                        + " | injected=" + ExcavatorTransportStressHarness.totalInjected()),
                false
        );
        source.sendSuccess(
                () -> Component.literal("Plane B distance=" + ExcavatorTransportStressHarness.destinationDistance()
                        + " | A->B separation=" + ExcavatorTransportStressHarness.planeSeparation()),
                false
        );
        if (ExcavatorTransportStressHarness.isRunning()) {
            source.sendSuccess(
                    () -> Component.literal("Plane A center=" + ExcavatorTransportStressHarness.sourcePlaneCenter().toShortString()
                            + " | Plane B center=" + ExcavatorTransportStressHarness.destinationPlaneCenter().toShortString()),
                    false
            );
        }
        return 1;
    }
}
