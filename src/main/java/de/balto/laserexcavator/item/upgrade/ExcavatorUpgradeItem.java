package de.balto.laserexcavator.item.upgrade;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public final class ExcavatorUpgradeItem extends Item {
    private final ExcavatorUpgradeType type;
    private final int tier;

    public ExcavatorUpgradeItem(
            ExcavatorUpgradeType type,
            int tier,
            int maxTier,
            Properties properties
    ) {
        super(properties.stacksTo(64));
        if (tier < 1 || tier > maxTier) {
            throw new IllegalArgumentException(type + " upgrade tier must be 1-" + maxTier);
        }
        this.type = type;
        this.tier = tier;
    }

    public ExcavatorUpgradeType getUpgradeType() {
        return type;
    }

    public int getTier() {
        return tier;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, context, display, tooltipComponents, tooltipFlag);

        switch (type) {
            case SPEED -> {
                tooltipComponents.accept(description("Reduces the time between mined blocks."));
                tooltipComponents.accept(value("Mining interval", LaserExcavatorConfig.speedInterval(tier) + " ticks"));
                tooltipComponents.accept(value(
                        "Energy draw",
                        LaserExcavatorConfig.energyPerTick(tier, 0) + " FE/t at base efficiency"
                ));
            }
            case ENERGY_EFFICIENCY -> {
                tooltipComponents.accept(description("Reduces the total FE needed per excavated block."));
                int reduction = LaserExcavatorConfig.energyEfficiencyReductionPercent(tier);
                int cost = LaserExcavatorConfig.energyPerBlock(tier);
                int perTick = LaserExcavatorConfig.energyPerTick(0, tier);
                tooltipComponents.accept(value("Reduction", reduction + "%"));
                tooltipComponents.accept(value("Energy use", cost + " FE/block (" + perTick + " FE/t at base speed)"));
            }
            case AREA -> {
                tooltipComponents.accept(description("Increases the maximum horizontal excavation area."));
                int size = LaserExcavatorConfig.areaSize(tier);
                tooltipComponents.accept(value("Maximum X/Z", size + " x " + size + " blocks"));
            }
            case LUCK -> {
                tooltipComponents.accept(description("Applies Fortune to excavated block drops."));
                tooltipComponents.accept(value("Fortune level", Integer.toString(LaserExcavatorConfig.luckLevel(tier))));
                tooltipComponents.accept(Component.literal("Incompatible with Silk Touch").withStyle(ChatFormatting.DARK_RED));
            }
            case FILTER -> {
                tooltipComponents.accept(description("Leaves configured block types untouched."));
                tooltipComponents.accept(value("Filter entries", Integer.toString(LaserExcavatorConfig.filterCapacity(tier))));
                int reduction = LaserExcavatorConfig.filterCooldownReductionPercent(tier);
                tooltipComponents.accept(value(
                        "Skip cooldown",
                        reduction >= 100 ? "100% reduction (instant)" : reduction + "% reduction"
                ));
            }
            case SILK_TOUCH -> {
                tooltipComponents.accept(description("Uses Silk Touch drops for excavated blocks."));
                tooltipComponents.accept(status("Silk Touch", LaserExcavatorConfig.SILK_TOUCH_ENABLED.get()));
                tooltipComponents.accept(Component.literal("Incompatible with Luck and Auto-Smelt").withStyle(ChatFormatting.DARK_RED));
            }
            case AUTO_SMELTING -> {
                tooltipComponents.accept(description("Automatically smelts compatible block drops."));
                tooltipComponents.accept(status("Auto-Smelt", LaserExcavatorConfig.AUTO_SMELTING_ENABLED.get()));
                tooltipComponents.accept(Component.literal("Incompatible with Silk Touch").withStyle(ChatFormatting.DARK_RED));
            }
            case FLUID_IGNORE -> {
                tooltipComponents.accept(description("Leaves fluid blocks untouched while excavating."));
                tooltipComponents.accept(Component.literal("Fluid blocks are skipped instantly").withStyle(ChatFormatting.AQUA));
            }
            case NETHER_COOLING -> {
                tooltipComponents.accept(description("Protects the excavator from extreme Nether heat."));
                tooltipComponents.accept(Component.literal("Allows scanning and excavation in the Nether").withStyle(ChatFormatting.AQUA));
            }
            case SOLAR -> {
                tooltipComponents.accept(description("Generates FE from 06:00-18:00."));
                tooltipComponents.accept(value("Generation", LaserExcavatorConfig.solarEnergyPerTick(tier) + " FE/t"));
                tooltipComponents.accept(Component.literal("Allows glass blocks/panes and up to " + LaserExcavatorConfig.solarMaxWaterBlocks() + " blocks of water above").withStyle(ChatFormatting.AQUA));
            }
        }
    }

    private static Component description(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static Component value(String label, String value) {
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }

    private static Component status(String label, boolean enabled) {
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(enabled ? "Enabled" : "Disabled")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
}
