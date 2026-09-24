package de.balto.laserexcavator.block.excavator;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import java.util.ArrayList;
import java.util.List;

public final class ExcavatorAutoSmelter {
    private static final RecipeManager.CachedCheck<SingleRecipeInput, SmeltingRecipe> SMELTING_CHECK =
            RecipeManager.createCheck(RecipeType.SMELTING);

    private ExcavatorAutoSmelter() {}

    public static Result smelt(ServerLevel level, List<ItemStack> drops) {
        if (drops.isEmpty()) return new Result(drops, false);

        ArrayList<ItemStack> result = null;
        for (int index = 0; index < drops.size(); index++) {
            ItemStack input = drops.get(index);
            List<ItemStack> smelted = smeltStack(level, input);
            if (smelted == null) {
                if (result != null) result.add(input);
                continue;
            }

            if (result == null) {
                result = new ArrayList<>(drops.size() + 1);
                result.addAll(drops.subList(0, index));
            }
            result.addAll(smelted);
        }

        return result == null
                ? new Result(drops, false)
                : new Result(List.copyOf(result), true);
    }

    /** Returns null when the input has no furnace-smelting recipe. */
    private static List<ItemStack> smeltStack(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) return null;

        ItemStack singleInput = input.copy();
        singleInput.setCount(1);
        SingleRecipeInput recipeInput = new SingleRecipeInput(singleInput);

        return SMELTING_CHECK.getRecipeFor(recipeInput, level)
                .map(holder -> holder.value().assemble(recipeInput, level.registryAccess()))
                .filter(stack -> !stack.isEmpty())
                .map(output -> multiplyAndSplit(output, input.getCount()))
                .orElse(null);
    }

    private static List<ItemStack> multiplyAndSplit(ItemStack output, int operations) {
        long remaining = (long) output.getCount() * operations;
        int maxStackSize = Math.max(1, output.getMaxStackSize());
        ArrayList<ItemStack> stacks = new ArrayList<>((int) Math.max(1L, (remaining + maxStackSize - 1L) / maxStackSize));

        while (remaining > 0L) {
            int count = (int) Math.min(remaining, maxStackSize);
            ItemStack stack = output.copy();
            stack.setCount(count);
            stacks.add(stack);
            remaining -= count;
        }
        return stacks;
    }

    public record Result(List<ItemStack> stacks, boolean changed) {}
}
