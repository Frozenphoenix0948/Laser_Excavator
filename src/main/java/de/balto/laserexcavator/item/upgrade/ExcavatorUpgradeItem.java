package de.balto.laserexcavator.item.upgrade;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

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
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        switch (type) {
            case SPEED -> {
                tooltipComponents.add(description("Reduces the time between mined blocks."));
                tooltipComponents.add(value("Mining interval", LaserExcavatorConfig.speedInterval(tier) + " ticks"));
                tooltipComponents.add(value(
                        "Energy draw",
                        LaserExcavatorConfig.energyPerTick(tier, 0) + " FE/t at base efficiency"
                ));
            }
            case ENERGY_EFFICIENCY -> {
                tooltipComponents.add(description("Reduces the total FE needed per excavated block."));
                int reduction = LaserExcavatorConfig.energyEfficiencyReductionPercent(tier);
                int cost = LaserExcavatorConfig.energyPerBlock(tier);
                int perTick = LaserExcavatorConfig.energyPerTick(0, tier);
                tooltipComponents.add(value("Reduction", reduction + "%"));
                tooltipComponents.add(value("Energy use", cost + " FE/block (" + perTick + " FE/t at base speed)"));
            }
            case AREA -> {
                tooltipComponents.add(description("Increases the maximum horizontal excavation area."));
                int size = LaserExcavatorConfig.areaSize(tier);
                tooltipComponents.add(value("Maximum X/Z", size + " x " + size + " blocks"));
            }
            case LUCK -> {
                tooltipComponents.add(description("Applies Fortune to excavated block drops."));
                tooltipComponents.add(value("Fortune level", Integer.toString(LaserExcavatorConfig.luckLevel(tier))));
                tooltipComponents.add(Component.literal("Incompatible with Silk Touch").withStyle(ChatFormatting.DARK_RED));
            }
            case FILTER -> {
                tooltipComponents.add(description("Leaves configured block types untouched."));
                tooltipComponents.add(value("Filter entries", Integer.toString(LaserExcavatorConfig.filterCapacity(tier))));
                int reduction = LaserExcavatorConfig.filterCooldownReductionPercent(tier);
                tooltipComponents.add(value(
                        "Skip cooldown",
                        reduction >= 100 ? "100% reduction (instant)" : reduction + "% reduction"
                ));
            }
            case SILK_TOUCH -> {
                tooltipComponents.add(description("Uses Silk Touch drops for excavated blocks."));
                tooltipComponents.add(status("Silk Touch", LaserExcavatorConfig.SILK_TOUCH_ENABLED.get()));
                tooltipComponents.add(Component.literal("Incompatible with Luck and Auto-Smelt").withStyle(ChatFormatting.DARK_RED));
            }
            case AUTO_SMELTING -> {
                tooltipComponents.add(description("Automatically smelts compatible block drops."));
                tooltipComponents.add(status("Auto-Smelt", LaserExcavatorConfig.AUTO_SMELTING_ENABLED.get()));
                tooltipComponents.add(Component.literal("Incompatible with Silk Touch").withStyle(ChatFormatting.DARK_RED));
            }
            case FLUID_IGNORE -> {
                tooltipComponents.add(description("Leaves fluid blocks untouched while excavating."));
                tooltipComponents.add(Component.literal("Fluid blocks are skipped instantly").withStyle(ChatFormatting.AQUA));
            }
            case NETHER_COOLING -> {
                tooltipComponents.add(description("Protects the excavator from extreme Nether heat."));
                tooltipComponents.add(Component.literal("Allows scanning and excavation in the Nether").withStyle(ChatFormatting.AQUA));
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
