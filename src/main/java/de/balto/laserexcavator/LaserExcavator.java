package de.balto.laserexcavator;

import com.mojang.logging.LogUtils;
import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.block.blockentities.ModBlockEntities;
import de.balto.laserexcavator.block.excavator.ExcavatorBlockSyncBatcher;
import de.balto.laserexcavator.block.excavator.ExcavatorCapabilities;
import de.balto.laserexcavator.block.excavator.ExcavatorLootCache;
import de.balto.laserexcavator.block.excavator.ExcavatorSharedColumnHeights;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.item.ModCreativeTabs;
import de.balto.laserexcavator.item.ModItems;
import de.balto.laserexcavator.network.excavator.ExcavatorNetworking;
import de.balto.laserexcavator.recipe.ConfigurableRecipes;
import de.balto.laserexcavator.screen.ModMenuTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(LaserExcavator.MODID)
public final class LaserExcavator {
    public static final String MODID = "laserexcavator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LaserExcavator(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, LaserExcavatorConfig.SPEC, "laserexcavator.toml");

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);

        modEventBus.addListener(ExcavatorCapabilities::registerCapabilities);
        modEventBus.addListener(ExcavatorNetworking::registerPayloads);

        NeoForge.EVENT_BUS.addListener(ExcavatorNetworking::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(LaserExcavator::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ConfigurableRecipes::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ConfigurableRecipes::onDatapackSync);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ExcavatorNetworking.clearPendingVisuals();
        ExcavatorBlockSyncBatcher.clearPendingSections();
        ExcavatorSharedColumnHeights.clearAll();
        ExcavatorLootCache.clear(event.getServer());
    }
}
