package de.balto.laserexcavator.item;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeItem;
import de.balto.laserexcavator.item.upgrade.ExcavatorUpgradeType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LaserExcavator.MODID);

    public static final DeferredItem<BlockItem> EXCAVATOR = ITEMS.register(
            "excavator",
            () -> new BlockItem(ModBlocks.EXCAVATOR.get(), new Item.Properties())
    );

    public static final DeferredItem<ExcavatorUpgradeItem> SPEED_UPGRADE_TIER_1 = upgrade("speed_upgrade_tier_1", ExcavatorUpgradeType.SPEED, 1, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> SPEED_UPGRADE_TIER_2 = upgrade("speed_upgrade_tier_2", ExcavatorUpgradeType.SPEED, 2, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> SPEED_UPGRADE_TIER_3 = upgrade("speed_upgrade_tier_3", ExcavatorUpgradeType.SPEED, 3, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> SPEED_UPGRADE_TIER_4 = upgrade("speed_upgrade_tier_4", ExcavatorUpgradeType.SPEED, 4, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> SPEED_UPGRADE_TIER_5 = upgrade("speed_upgrade_tier_5", ExcavatorUpgradeType.SPEED, 5, 5);

    public static final DeferredItem<ExcavatorUpgradeItem> AUTO_SMELTING_UPGRADE = upgrade(
            "auto_smelting_upgrade", ExcavatorUpgradeType.AUTO_SMELTING, 1, 1
    );

    public static final DeferredItem<ExcavatorUpgradeItem> AREA_UPGRADE_32 = upgrade("area_upgrade_32", ExcavatorUpgradeType.AREA, 1, 3);
    public static final DeferredItem<ExcavatorUpgradeItem> AREA_UPGRADE_64 = upgrade("area_upgrade_64", ExcavatorUpgradeType.AREA, 2, 3);
    public static final DeferredItem<ExcavatorUpgradeItem> AREA_UPGRADE_128 = upgrade("area_upgrade_128", ExcavatorUpgradeType.AREA, 3, 3);

    public static final DeferredItem<ExcavatorUpgradeItem> LUCK_UPGRADE_TIER_1 = upgrade("luck_upgrade_tier_1", ExcavatorUpgradeType.LUCK, 1, 3);
    public static final DeferredItem<ExcavatorUpgradeItem> LUCK_UPGRADE_TIER_2 = upgrade("luck_upgrade_tier_2", ExcavatorUpgradeType.LUCK, 2, 3);
    public static final DeferredItem<ExcavatorUpgradeItem> LUCK_UPGRADE_TIER_3 = upgrade("luck_upgrade_tier_3", ExcavatorUpgradeType.LUCK, 3, 3);

    public static final DeferredItem<ExcavatorUpgradeItem> FILTER_UPGRADE_TIER_1 = upgrade("filter_upgrade_tier_1", ExcavatorUpgradeType.FILTER, 1, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> FILTER_UPGRADE_TIER_2 = upgrade("filter_upgrade_tier_2", ExcavatorUpgradeType.FILTER, 2, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> FILTER_UPGRADE_TIER_3 = upgrade("filter_upgrade_tier_3", ExcavatorUpgradeType.FILTER, 3, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> FILTER_UPGRADE_TIER_4 = upgrade("filter_upgrade_tier_4", ExcavatorUpgradeType.FILTER, 4, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> FILTER_UPGRADE_TIER_5 = upgrade("filter_upgrade_tier_5", ExcavatorUpgradeType.FILTER, 5, 5);

    public static final DeferredItem<ExcavatorUpgradeItem> SILK_TOUCH_UPGRADE = upgrade(
            "silk_touch_upgrade", ExcavatorUpgradeType.SILK_TOUCH, 1, 1
    );

    public static final DeferredItem<ExcavatorUpgradeItem> FLUID_IGNORE_UPGRADE = upgrade(
            "fluid_ignore_upgrade", ExcavatorUpgradeType.FLUID_IGNORE, 1, 1
    );

    public static final DeferredItem<ExcavatorUpgradeItem> NETHER_COOLING_UPGRADE = upgrade(
            "nether_cooling_upgrade", ExcavatorUpgradeType.NETHER_COOLING, 1, 1
    );

    public static final DeferredItem<ExcavatorUpgradeItem> ENERGY_EFFICIENCY_UPGRADE_TIER_1 = upgrade("energy_efficiency_upgrade_tier_1", ExcavatorUpgradeType.ENERGY_EFFICIENCY, 1, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> ENERGY_EFFICIENCY_UPGRADE_TIER_2 = upgrade("energy_efficiency_upgrade_tier_2", ExcavatorUpgradeType.ENERGY_EFFICIENCY, 2, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> ENERGY_EFFICIENCY_UPGRADE_TIER_3 = upgrade("energy_efficiency_upgrade_tier_3", ExcavatorUpgradeType.ENERGY_EFFICIENCY, 3, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> ENERGY_EFFICIENCY_UPGRADE_TIER_4 = upgrade("energy_efficiency_upgrade_tier_4", ExcavatorUpgradeType.ENERGY_EFFICIENCY, 4, 5);
    public static final DeferredItem<ExcavatorUpgradeItem> ENERGY_EFFICIENCY_UPGRADE_TIER_5 = upgrade("energy_efficiency_upgrade_tier_5", ExcavatorUpgradeType.ENERGY_EFFICIENCY, 5, 5);

    private static DeferredItem<ExcavatorUpgradeItem> upgrade(
            String id,
            ExcavatorUpgradeType type,
            int tier,
            int maxTier
    ) {
        return ITEMS.register(id, () -> new ExcavatorUpgradeItem(type, tier, maxTier, new Item.Properties()));
    }
}
