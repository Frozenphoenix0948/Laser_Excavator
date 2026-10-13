package de.balto.laserexcavator;

import com.mojang.logging.LogUtils;
import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.block.blockentities.ModBlockEntities;
import de.balto.laserexcavator.block.excavator.ExcavatorBlockSyncBatcher;
import de.balto.laserexcavator.block.excavator.ExcavatorLootCache;
import de.balto.laserexcavator.block.excavator.ExcavatorSharedColumnHeights;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.item.ModCreativeTabs;
import de.balto.laserexcavator.item.ModItems;
import de.balto.laserexcavator.network.excavator.ExcavatorNetworking;
import de.balto.laserexcavator.recipe.ConfigurableRecipes;
import de.balto.laserexcavator.screen.ModMenuTypes;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(LaserExcavator.MODID)
public final class LaserExcavator {
    public static final String MODID = "laserexcavator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LaserExcavator() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, LaserExcavatorClientConfig.SPEC, "laserexcavator-client.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, LaserExcavatorConfig.SPEC, "laserexcavator.toml");

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);

        ExcavatorNetworking.registerPackets();

        MinecraftForge.EVENT_BUS.addListener(ExcavatorNetworking::onServerTickPost);
        MinecraftForge.EVENT_BUS.addListener(LaserExcavator::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(ConfigurableRecipes::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ConfigurableRecipes::onDatapackSync);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ExcavatorNetworking.clearPendingVisuals();
        ExcavatorBlockSyncBatcher.clearPendingSections();
        ExcavatorSharedColumnHeights.clearAll();
        ExcavatorLootCache.clear(event.getServer());
    }
}
