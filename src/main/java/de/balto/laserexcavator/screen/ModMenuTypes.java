package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.LaserExcavator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.network.IContainerFactory;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class ModMenuTypes {
    private ModMenuTypes() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.MENU_TYPES, LaserExcavator.MODID);

    public static final RegistryObject<MenuType<ExcavatorMenu>> EXCAVATOR_MENU =
            registerMenuType("excavator_menu", ExcavatorMenu::new);

    private static <T extends AbstractContainerMenu> RegistryObject<MenuType<T>> registerMenuType(
            String name,
            IContainerFactory<T> factory
    ) {
        return MENUS.register(name, () -> IForgeMenuType.create(factory));
    }
}
