package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.LaserExcavator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenuTypes {
    private ModMenuTypes() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, LaserExcavator.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<ExcavatorMenu>> EXCAVATOR_MENU =
            registerMenuType("excavator_menu", ExcavatorMenu::new);

    private static <T extends AbstractContainerMenu> DeferredHolder<MenuType<?>, MenuType<T>> registerMenuType(
            String name,
            IContainerFactory<T> factory
    ) {
        return MENUS.register(name, () -> IMenuTypeExtension.create(factory));
    }
}
