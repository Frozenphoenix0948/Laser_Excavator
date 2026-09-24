package de.balto.laserexcavator.recipe;

import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.excavator.ExcavatorLootCache;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.item.ModItems;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class ConfigurableRecipes {
    private ConfigurableRecipes() {}

    private static final List<Definition> DEFINITIONS = List.of(
            definition("excavator", LaserExcavatorConfig.EXCAVATOR_RECIPE, ModItems.EXCAVATOR, CraftingBookCategory.REDSTONE, "laserexcavator:excavator"),
            definition("speed_upgrade_tier_1", LaserExcavatorConfig.SPEED_TIER_1_RECIPE, ModItems.SPEED_UPGRADE_TIER_1, CraftingBookCategory.MISC, "laserexcavator:speed_upgrades"),
            definition("speed_upgrade_tier_2", LaserExcavatorConfig.SPEED_TIER_2_RECIPE, ModItems.SPEED_UPGRADE_TIER_2, CraftingBookCategory.MISC, "laserexcavator:speed_upgrades"),
            definition("speed_upgrade_tier_3", LaserExcavatorConfig.SPEED_TIER_3_RECIPE, ModItems.SPEED_UPGRADE_TIER_3, CraftingBookCategory.MISC, "laserexcavator:speed_upgrades"),
            definition("speed_upgrade_tier_4", LaserExcavatorConfig.SPEED_TIER_4_RECIPE, ModItems.SPEED_UPGRADE_TIER_4, CraftingBookCategory.MISC, "laserexcavator:speed_upgrades"),
            definition("speed_upgrade_tier_5", LaserExcavatorConfig.SPEED_TIER_5_RECIPE, ModItems.SPEED_UPGRADE_TIER_5, CraftingBookCategory.MISC, "laserexcavator:speed_upgrades"),
            definition("auto_smelting_upgrade", LaserExcavatorConfig.AUTO_SMELTING_RECIPE, ModItems.AUTO_SMELTING_UPGRADE, CraftingBookCategory.MISC, ""),
            definition("area_upgrade_32", LaserExcavatorConfig.AREA_TIER_1_RECIPE, ModItems.AREA_UPGRADE_32, CraftingBookCategory.MISC, "laserexcavator:area_upgrades"),
            definition("area_upgrade_64", LaserExcavatorConfig.AREA_TIER_2_RECIPE, ModItems.AREA_UPGRADE_64, CraftingBookCategory.MISC, "laserexcavator:area_upgrades"),
            definition("area_upgrade_128", LaserExcavatorConfig.AREA_TIER_3_RECIPE, ModItems.AREA_UPGRADE_128, CraftingBookCategory.MISC, "laserexcavator:area_upgrades"),
            definition("luck_upgrade_tier_1", LaserExcavatorConfig.LUCK_TIER_1_RECIPE, ModItems.LUCK_UPGRADE_TIER_1, CraftingBookCategory.MISC, "laserexcavator:luck_upgrades"),
            definition("luck_upgrade_tier_2", LaserExcavatorConfig.LUCK_TIER_2_RECIPE, ModItems.LUCK_UPGRADE_TIER_2, CraftingBookCategory.MISC, "laserexcavator:luck_upgrades"),
            definition("luck_upgrade_tier_3", LaserExcavatorConfig.LUCK_TIER_3_RECIPE, ModItems.LUCK_UPGRADE_TIER_3, CraftingBookCategory.MISC, "laserexcavator:luck_upgrades"),
            definition("filter_upgrade_tier_1", LaserExcavatorConfig.FILTER_TIER_1_RECIPE, ModItems.FILTER_UPGRADE_TIER_1, CraftingBookCategory.MISC, "laserexcavator:filter_upgrades"),
            definition("filter_upgrade_tier_2", LaserExcavatorConfig.FILTER_TIER_2_RECIPE, ModItems.FILTER_UPGRADE_TIER_2, CraftingBookCategory.MISC, "laserexcavator:filter_upgrades"),
            definition("filter_upgrade_tier_3", LaserExcavatorConfig.FILTER_TIER_3_RECIPE, ModItems.FILTER_UPGRADE_TIER_3, CraftingBookCategory.MISC, "laserexcavator:filter_upgrades"),
            definition("filter_upgrade_tier_4", LaserExcavatorConfig.FILTER_TIER_4_RECIPE, ModItems.FILTER_UPGRADE_TIER_4, CraftingBookCategory.MISC, "laserexcavator:filter_upgrades"),
            definition("filter_upgrade_tier_5", LaserExcavatorConfig.FILTER_TIER_5_RECIPE, ModItems.FILTER_UPGRADE_TIER_5, CraftingBookCategory.MISC, "laserexcavator:filter_upgrades"),
            definition("silk_touch_upgrade", LaserExcavatorConfig.SILK_TOUCH_RECIPE, ModItems.SILK_TOUCH_UPGRADE, CraftingBookCategory.MISC, ""),
            definition("fluid_ignore_upgrade", LaserExcavatorConfig.FLUID_IGNORE_RECIPE, ModItems.FLUID_IGNORE_UPGRADE, CraftingBookCategory.MISC, ""),
            definition("nether_cooling_upgrade", LaserExcavatorConfig.NETHER_COOLING_RECIPE, ModItems.NETHER_COOLING_UPGRADE, CraftingBookCategory.MISC, ""),
            definition("energy_efficiency_upgrade_tier_1", LaserExcavatorConfig.ENERGY_EFFICIENCY_TIER_1_RECIPE, ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_1, CraftingBookCategory.MISC, "laserexcavator:energy_efficiency_upgrades"),
            definition("energy_efficiency_upgrade_tier_2", LaserExcavatorConfig.ENERGY_EFFICIENCY_TIER_2_RECIPE, ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_2, CraftingBookCategory.MISC, "laserexcavator:energy_efficiency_upgrades"),
            definition("energy_efficiency_upgrade_tier_3", LaserExcavatorConfig.ENERGY_EFFICIENCY_TIER_3_RECIPE, ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_3, CraftingBookCategory.MISC, "laserexcavator:energy_efficiency_upgrades"),
            definition("energy_efficiency_upgrade_tier_4", LaserExcavatorConfig.ENERGY_EFFICIENCY_TIER_4_RECIPE, ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_4, CraftingBookCategory.MISC, "laserexcavator:energy_efficiency_upgrades"),
            definition("energy_efficiency_upgrade_tier_5", LaserExcavatorConfig.ENERGY_EFFICIENCY_TIER_5_RECIPE, ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_5, CraftingBookCategory.MISC, "laserexcavator:energy_efficiency_upgrades")
    );

    public static void onServerStarted(ServerStartedEvent event) {
        ExcavatorLootCache.clear(event.getServer());
        apply(event.getServer().getRecipeManager(), event.getServer().registryAccess());
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ExcavatorLootCache.clear(event.getPlayerList().getServer());
        apply(event.getPlayerList().getServer().getRecipeManager(), event.getPlayerList().getServer().registryAccess());
    }

    private static void apply(RecipeManager manager, RegistryAccess registryAccess) {
        List<RecipeHolder<?>> recipes = new ArrayList<>(manager.getRecipes());
        boolean changed = false;

        for (Definition definition : DEFINITIONS) {
            LaserExcavatorConfig.RecipeConfig config = definition.config();

            if (!config.enabled().get()) {
                changed |= recipes.removeIf(holder -> holder.id().equals(definition.id()));
                continue;
            }

            try {
                Recipe<?> configured = createRecipe(definition, registryAccess);
                recipes.removeIf(holder -> holder.id().equals(definition.id()));
                recipes.add(new RecipeHolder<>(definition.id(), configured));
                changed = true;
            } catch (RuntimeException ex) {
                LaserExcavator.LOGGER.error(
                        "Invalid configured recipe {}. Keeping the currently loaded recipe. {}",
                        definition.id(),
                        ex.getMessage()
                );
            }
        }

        if (changed) {
            manager.replaceRecipes(recipes);
        }
    }

    private static Recipe<?> createRecipe(Definition definition, RegistryAccess registryAccess) {
        LaserExcavatorConfig.RecipeConfig config = definition.config();
        List<? extends String> configuredIngredients = config.ingredients().get();
        ItemLike resultItem = definition.result().get();
        int resultCount = config.resultCount().get();
        if (resultCount > resultItem.asItem().getDefaultMaxStackSize()) {
            throw new IllegalArgumentException(
                    "resultCount " + resultCount + " exceeds this item's max stack size " + resultItem.asItem().getDefaultMaxStackSize()
            );
        }
        ItemStack result = new ItemStack(resultItem, resultCount);

        if (config.shapeless().get()) {
            if (configuredIngredients.isEmpty() || configuredIngredients.size() > 9) {
                throw new IllegalArgumentException("Shapeless recipes require 1-9 ingredients");
            }

            NonNullList<Ingredient> ingredients = NonNullList.create();
            for (String entry : configuredIngredients) {
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException("Shapeless recipes cannot contain empty ingredients");
                }
                ingredients.add(parseIngredient(entry, registryAccess));
            }
            return new ShapelessRecipe(definition.group(), definition.category(), result, ingredients);
        }

        if (configuredIngredients.size() != 9) {
            throw new IllegalArgumentException("Shaped recipes require exactly 9 row-major ingredients");
        }

        NonNullList<Ingredient> ingredients = NonNullList.withSize(9, Ingredient.EMPTY);
        for (int i = 0; i < 9; i++) {
            String entry = configuredIngredients.get(i);
            ingredients.set(i, entry == null || entry.isBlank() ? Ingredient.EMPTY : parseIngredient(entry, registryAccess));
        }

        ShapedRecipePattern pattern = new ShapedRecipePattern(3, 3, ingredients, Optional.empty());
        return new ShapedRecipe(definition.group(), definition.category(), pattern, result);
    }

    private static Ingredient parseIngredient(String raw, RegistryAccess registryAccess) {
        String value = raw.trim();
        if (value.equals("@silk_touch_book")) {
            var silkTouch = registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(silkTouch, 1);
            return DataComponentIngredient.of(
                    false,
                    DataComponents.STORED_ENCHANTMENTS,
                    enchantments.toImmutable(),
                    Items.ENCHANTED_BOOK
            );
        }

        boolean isTag = value.startsWith("#");
        String idText = isTag ? value.substring(1) : value;
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            throw new IllegalArgumentException("Invalid ingredient id: " + raw);
        }

        if (isTag) {
            return Ingredient.of(TagKey.create(Registries.ITEM, id));
        }

        Item item = BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item: " + id));
        return Ingredient.of(item);
    }

    private static Definition definition(
            String path,
            LaserExcavatorConfig.RecipeConfig config,
            Supplier<? extends ItemLike> result,
            CraftingBookCategory category,
            String group
    ) {
        return new Definition(
                ResourceLocation.fromNamespaceAndPath(LaserExcavator.MODID, path),
                config,
                result,
                category,
                group
        );
    }

    private record Definition(
            ResourceLocation id,
            LaserExcavatorConfig.RecipeConfig config,
            Supplier<? extends ItemLike> result,
            CraftingBookCategory category,
            String group
    ) {}
}
