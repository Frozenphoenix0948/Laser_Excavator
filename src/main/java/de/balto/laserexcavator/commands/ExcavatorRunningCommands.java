package de.balto.laserexcavator.commands;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.excavator.ExcavatorRunningLimits;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;

/**
 * Adds  command to allow each player to check their running excavators.
 */

@EventBusSubscriber(modid = LaserExcavator.MODID)
public final class ExcavatorRunningCommands {
    private ExcavatorRunningCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("excavators")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> list(context.getSource().getPlayerOrException())));
    }

    private static int list(ServerPlayer player) {
        var entries = ExcavatorRunningLimits.get(player.serverLevel()).list(player.getUUID());
        entries.sort(Comparator.comparingInt(ExcavatorRunningLimits.RunningExcavator::tier)
                .thenComparing(ExcavatorRunningLimits.RunningExcavator::dimension)
                .thenComparing(entry -> entry.position().asLong()));
        player.sendSystemMessage(Component.literal("Your running excavators (" + entries.size() + "):"));
        if (entries.isEmpty()) player.sendSystemMessage(Component.literal("None."));
        for (var entry : entries) {
            var pos = entry.position();
            player.sendSystemMessage(Component.literal("Tier " + entry.tier() + " | " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " | " + entry.dimension()));
        }
        return entries.size();
    }
}
