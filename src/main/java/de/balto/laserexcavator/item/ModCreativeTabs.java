package de.balto.laserexcavator.item;

import de.balto.laserexcavator.LaserExcavator;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    private ModCreativeTabs() {}

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LaserExcavator.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> LASER_EXCAVATOR =
            TABS.register("laser_excavator", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.laserexcavator"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .icon(() -> ModItems.EXCAVATOR.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.EXCAVATOR.get());

                        output.accept(ModItems.SPEED_UPGRADE_TIER_1.get());
                        output.accept(ModItems.SPEED_UPGRADE_TIER_2.get());
                        output.accept(ModItems.SPEED_UPGRADE_TIER_3.get());
                        output.accept(ModItems.SPEED_UPGRADE_TIER_4.get());
                        output.accept(ModItems.SPEED_UPGRADE_TIER_5.get());

                        output.accept(ModItems.AUTO_SMELTING_UPGRADE.get());

                        output.accept(ModItems.AREA_UPGRADE_32.get());
                        output.accept(ModItems.AREA_UPGRADE_64.get());
                        output.accept(ModItems.AREA_UPGRADE_128.get());

                        output.accept(ModItems.LUCK_UPGRADE_TIER_1.get());
                        output.accept(ModItems.LUCK_UPGRADE_TIER_2.get());
                        output.accept(ModItems.LUCK_UPGRADE_TIER_3.get());

                        output.accept(ModItems.FILTER_UPGRADE_TIER_1.get());
                        output.accept(ModItems.FILTER_UPGRADE_TIER_2.get());
                        output.accept(ModItems.FILTER_UPGRADE_TIER_3.get());
                        output.accept(ModItems.FILTER_UPGRADE_TIER_4.get());
                        output.accept(ModItems.FILTER_UPGRADE_TIER_5.get());

                        output.accept(ModItems.SILK_TOUCH_UPGRADE.get());
                        output.accept(ModItems.FLUID_IGNORE_UPGRADE.get());
                        output.accept(ModItems.NETHER_COOLING_UPGRADE.get());

                        output.accept(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_1.get());
                        output.accept(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_2.get());
                        output.accept(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_3.get());
                        output.accept(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_4.get());
                        output.accept(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_5.get());
                    })
                    .build());
}