package de.balto.laserexcavator.block.excavator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import de.balto.laserexcavator.debug.ExcavatorProfiler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.common.ToolAction;
import javax.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a conservative subset of the RESOLVED loot table for the actual
 * excavator tool and block state. Unrecognized context/function semantics go
 * through vanilla Block.getDrops instead of silently changing their meaning.
 * Compiled random outcomes preserve the long-term mean, not their variance.
 */
final class ExcavatorLootTableInspector {
    private static final double EPSILON = 1.0e-9;
    private static final Codec<LootTable> TABLE_CODEC = lookupTableCodec();

    @SuppressWarnings("unchecked")
    private static Codec<LootTable> lookupTableCodec() {
        try {
            for (Field field : LootDataType.class.getDeclaredFields()) {
                if (field.getType() == Codec.class) {
                    field.setAccessible(true);
                    return (Codec<LootTable>) field.get(LootDataType.TABLE);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private ExcavatorLootTableInspector() {}

    enum Failure {
        BLOCK_ENTITY_OR_NO_TABLE,
        TABLE_ENCODING,
        CONDITION,
        TOOL_PREDICATE,
        FUNCTION,
        ENTRY,
        NUMBER_PROVIDER,
        STRUCTURE
    }

    record Inspection(@Nullable Rule rule, @Nullable Failure failure, @Nullable String detail) {}

    private static final class Reject extends RuntimeException {
        private final Failure reason;
        private final String detail;
        Reject(Failure reason, String detail) {
            super(null, null, false, false);
            this.reason = reason;
            this.detail = detail;
        }
    }

    private static Reject reject(Failure reason) { return new Reject(reason, "unspecified"); }
    private static Reject reject(Failure reason, String detail) { return new Reject(reason, detail); }

    static Inspection inspect(ServerLevel level, BlockState state, int fortuneLevel, boolean silkTouch) {
        if (state.hasBlockEntity()) {
            return new Inspection(null, Failure.BLOCK_ENTITY_OR_NO_TABLE, "block entity");
        }
        try {
            LootTable resolved = level.getServer().getLootData()
                    .getLootTable(state.getBlock().getLootTable());
            // Resolve through the live server registry so datapack-defined entries
            // and functions are seen, rather than assuming vanilla JSON defaults.
            if (TABLE_CODEC == null) throw reject(Failure.TABLE_ENCODING, "loot table codec unavailable");
            JsonElement encoded = TABLE_CODEC.encodeStart(
                    RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), resolved)
                    .result().orElse(null);
            if (encoded == null || !encoded.isJsonObject()) throw reject(Failure.TABLE_ENCODING);
            JsonObject root = encoded.getAsJsonObject();
            // Matches ExcavatorUpgradeManager.excavationTool(): a netherite
            // pickaxe enchanted with the active loot mode, not a shears tool.
            // This is created only on first inspection of a state/mode.
            ItemStack tool = inspectionTool(level, fortuneLevel, silkTouch);
            if (root.has("modifier")) throw reject(Failure.FUNCTION, "table modifier");
            requireNoOpFunctions(root, "table");
            JsonArray pools = array(root, "pools");
            if (pools == null) throw reject(Failure.STRUCTURE);
            LinkedHashMap<Item, Double> expected = new LinkedHashMap<>();
            for (JsonElement poolElement : pools) {
                if (!poolElement.isJsonObject()) throw reject(Failure.STRUCTURE);
                JsonObject pool = poolElement.getAsJsonObject();
                requireNoOpFunctions(pool, "pool");
                double poolChance = conditions(pool, state, fortuneLevel, silkTouch, tool);
                Double rolls = fixedNumber(pool.get("rolls"), 1.0);
                Double bonusRolls = fixedNumber(pool.get("bonus_rolls"), 0.0);
                if (rolls == null || bonusRolls == null || bonusRolls != 0.0
                        || rolls < 0 || rolls > 64 || Math.rint(rolls) != rolls)
                    throw reject(Failure.NUMBER_PROVIDER);
                JsonArray entries = array(pool, "entries");
                if (entries == null) throw reject(Failure.STRUCTURE);
                if (poolChance == 0 || rolls == 0) continue;
                if (entries.size() == 1) {
                    ExpectedEntry part = entry(entries.get(0), state, fortuneLevel, silkTouch, tool);
                    merge(expected, part.items, poolChance * rolls);
                } else if (!entries.isEmpty()) {
                    // Predicates that are definitely true/false for this
                    // state/tool may filter weighted entries before selection.
                    // Genuinely stochastic eligibility still needs a fuller model.
                    double totalWeight = 0;
                    List<ExpectedEntry> choices = new ArrayList<>();
                    List<Integer> weights = new ArrayList<>();
                    for (JsonElement el : entries) {
                        if (!el.isJsonObject()) throw reject(Failure.ENTRY);
                        JsonObject obj = el.getAsJsonObject();
                        ExpectedEntry part = entry(el, state, fortuneLevel, silkTouch, tool);
                        if (part.chance == 0) continue;
                        if (Math.abs(part.chance - 1.0) > EPSILON) throw reject(Failure.ENTRY);
                        int weight = integer(obj, "weight", 1);
                        int quality = integer(obj, "quality", 0);
                        if (weight < 0 || quality != 0) throw reject(Failure.ENTRY);
                        choices.add(part);
                        weights.add(weight);
                        totalWeight += weight;
                    }
                    if (totalWeight == 0 && choices.isEmpty()) continue;
                    if (totalWeight <= 0) throw reject(Failure.ENTRY);
                    for (int i = 0; i < choices.size(); i++) {
                        merge(expected, choices.get(i).items,
                                poolChance * rolls * weights.get(i) / totalWeight);
                    }
                }
            }
            if (expected.size() > 16) throw reject(Failure.STRUCTURE);
            for (double quantity : expected.values()) {
                if (!Double.isFinite(quantity) || quantity < 0 || quantity > 64
                        || quantity > 63 && Math.rint(quantity) != quantity)
                    throw reject(Failure.STRUCTURE);
            }
            return new Inspection(new Rule(expected), null, null);
        } catch (Reject ex) {
            return new Inspection(null, ex.reason, ex.detail);
        } catch (RuntimeException ex) {
            // Unknown modded codecs may not serialize into a supported form.
            return new Inspection(null, Failure.TABLE_ENCODING, ex.getClass().getSimpleName());
        }
    }

    private static ItemStack inspectionTool(ServerLevel level, int fortune, boolean silk) {
        ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
        if (silk) {
            tool.enchant(Enchantments.SILK_TOUCH, 1);
        } else if (fortune > 0) {
            tool.enchant(Enchantments.BLOCK_FORTUNE, fortune);
        }
        return tool;
    }

    /** Per-level/state/mode remainder, shared by all excavators and committed after a successful break. */
    static final class Rule {
        private final List<Item> items;
        private final double[] means;
        private final double[] fractional;
        private final boolean fixed;
        private final ExcavatorLootCache.CachedDrops fixedDrops;
        private ExcavatorLootCache.CachedDrops lastDrops;
        private int[] lastCounts;

        Rule(Map<Item, Double> expected) {
            items = List.copyOf(expected.keySet());
            means = new double[items.size()];
            fractional = new double[items.size()];
            boolean allInteger = true;
            int i = 0;
            for (double n : expected.values()) {
                means[i++] = n;
                if (Math.abs(n - Math.rint(n)) > EPSILON) allInteger = false;
            }
            fixed = allInteger;
            fixedDrops = fixed ? fromCounts(amounts()) : null;
        }

        boolean isFixed() { return fixed; }

        ExcavatorLootCache.CachedDrops preview() {
            if (fixed) return fixedDrops;
            // The normal case (especially for rare leaf drops) reuses the
            // previous immutable reservation. Do not allocate a counts array
            // until the actual output changes.
            boolean changed = lastCounts == null;
            if (!changed) {
                for (int i = 0; i < means.length; i++) {
                    if (lastCounts[i] != amountAt(i)) {
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) {
                if (lastCounts == null) lastCounts = new int[means.length];
                for (int i = 0; i < means.length; i++) lastCounts[i] = amountAt(i);
                lastDrops = fromCounts(lastCounts);
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LOOT_EXPECTED_SNAPSHOTS_BUILT);
            } else {
                ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LOOT_EXPECTED_SNAPSHOTS_REUSED);
            }
            return lastDrops;
        }

        void commit() {
            if (fixed) return;
            ExcavatorProfiler.increment(ExcavatorProfiler.Counter.LOOT_EXPECTED_COMMITS);
            for (int i = 0; i < means.length; i++) {
                double updated = fractional[i] + means[i];
                fractional[i] = updated - Math.floor(updated + EPSILON);
                if (fractional[i] < 0) fractional[i] = 0;
            }
        }

        private int amountAt(int index) {
            return (int) Math.floor(fractional[index] + means[index] + EPSILON);
        }

        private int[] amounts() {
            int[] counts = new int[means.length];
            for (int i = 0; i < means.length; i++) counts[i] = amountAt(i);
            return counts;
        }

        private ExcavatorLootCache.CachedDrops fromCounts(int[] counts) {
            // Pass ownership of the count snapshot directly. CachedDrops builds
            // each ItemStack once; the old path built temporary stacks, cloned
            // all of them, and allocated an extra ArrayList per snapshot.
            return new ExcavatorLootCache.CachedDrops(items, counts, fixed ? null : this);
        }
    }

    /** items already include the probability of this entry's own conditions. */
    private record ExpectedEntry(double chance, Map<Item, Double> items) {}

    private static ExpectedEntry entry(JsonElement el, BlockState state, int fortune, boolean silk, ItemStack tool) {
        if (!el.isJsonObject()) throw reject(Failure.ENTRY);
        JsonObject object = el.getAsJsonObject();
        double chance = conditions(object, state, fortune, silk, tool);
        // A definitively ineligible entry cannot run its functions or emit
        // drops. This also avoids rejecting an unused alternative branch.
        if (chance == 0) return new ExpectedEntry(0, Map.of());
        String type = string(object, "type", "");
        if ("minecraft:empty".equals(type)) return new ExpectedEntry(chance, Map.of());
        if ("minecraft:alternatives".equals(type)) {
            JsonArray children = array(object, "children");
            if (children == null || children.isEmpty()) throw reject(Failure.ENTRY);
            LinkedHashMap<Item, Double> sum = new LinkedHashMap<>();
            double remaining = 1;
            for (JsonElement child : children) {
                if (remaining <= EPSILON) break;
                ExpectedEntry branch = entry(child, state, fortune, silk, tool);
                merge(sum, branch.items, remaining);
                remaining *= 1 - branch.chance;
            }
            scale(sum, chance);
            return new ExpectedEntry(chance * (1 - remaining), sum);
        }
        if ("minecraft:group".equals(type) || "minecraft:sequence".equals(type)) {
            // Unconditional children of both constructs produce all their drops.
            // Conditional success/short-circuit semantics need a fuller model.
            JsonArray children = array(object, "children");
            if (children == null || children.isEmpty()) throw reject(Failure.ENTRY);
            LinkedHashMap<Item, Double> sum = new LinkedHashMap<>();
            for (JsonElement child : children) {
                ExpectedEntry branch = entry(child, state, fortune, silk, tool);
                if (Math.abs(branch.chance - 1.0) > EPSILON) throw reject(Failure.ENTRY);
                merge(sum, branch.items, 1);
            }
            scale(sum, chance);
            return new ExpectedEntry(chance, sum);
        }
        if (!"minecraft:item".equals(type)) throw reject(Failure.ENTRY);
        ResourceLocation id = ResourceLocation.tryParse(string(object, "name", ""));
        if (id == null) throw reject(Failure.ENTRY);
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) throw reject(Failure.ENTRY);
        double count = itemCount(object, state, fortune, silk, tool);
        return new ExpectedEntry(chance, Map.of(item, count * chance));
    }

    private static double itemCount(JsonObject object, BlockState state, int fortune, boolean silk, ItemStack tool) {
        JsonArray functions = array(object, "functions");
        if (functions == null) throw reject(Failure.FUNCTION);
        double count = 1;
        for (JsonElement el : functions) {
            if (!el.isJsonObject()) throw reject(Failure.FUNCTION);
            JsonObject fn = el.getAsJsonObject();
            String type = string(fn, "function", "<missing function>");
            // Only set_count is supported conditionally: its expectation
            // composes linearly. For an independent eligibility chance p,
            // replace-count yields (1-p)*old + p*new; add-count yields
            // old + p*extra. All other conditional functions stay vanilla.
            if (fn.has("conditions") && !"minecraft:set_count".equals(type))
                throw reject(Failure.FUNCTION, type + " [conditional]");
            try {
                switch (type) {
                    case "minecraft:set_count" -> {
                        double p = conditions(fn, state, fortune, silk, tool);
                        if (p > 0) {
                            double n = numberProviderMean(fn.get("count"));
                            if (booleanValue(fn, "add", false)) count += p * n;
                            else count = (1 - p) * count + p * n;
                        }
                    }
                    case "minecraft:apply_bonus" -> {
                        if (!"minecraft:fortune".equals(string(fn, "enchantment", "")))
                            throw reject(Failure.FUNCTION, "minecraft:apply_bonus [enchantment="
                                    + string(fn, "enchantment", "<missing>") + "]");
                        String formula = string(fn, "formula", "");
                        switch (formula) {
                            case "minecraft:ore_drops" -> {
                                // Vanilla ore_drops: 1 + max(0, uniform[0, fortune+1]-1).
                                count *= 1.0 + (double) fortune * (fortune + 1) / (2.0 * (fortune + 2));
                            }
                            case "minecraft:binomial_with_bonus_count" -> {
                                JsonObject args = object(fn, "parameters", Failure.FUNCTION);
                                int extra = integer(args, "extra", Integer.MIN_VALUE);
                                Double p = probability(args.get("probability"));
                                if (extra < 0 || fortune + extra > 256 || p == null)
                                    throw reject(Failure.FUNCTION);
                                count += (fortune + extra) * p;
                            }
                            case "minecraft:uniform_bonus_count" -> {
                                JsonObject args = object(fn, "parameters", Failure.FUNCTION);
                                int multiplier = integer(args, "bonusMultiplier", Integer.MIN_VALUE);
                                if (multiplier < 0 || (long) multiplier * fortune > 256)
                                    throw reject(Failure.FUNCTION);
                                count += (double) multiplier * fortune / 2;
                            }
                            default -> throw reject(Failure.FUNCTION, "minecraft:apply_bonus [formula=" + formula + "]");
                        }
                    }
                    // Excavation always supplies a tool but not EXPLOSION_RADIUS.
                    case "minecraft:explosion_decay" -> { }
                    default -> throw reject(Failure.FUNCTION, type);
                }
                if (!Double.isFinite(count) || count < 0 || count > 64)
                    throw reject(Failure.FUNCTION);
            } catch (Reject ex) {
                if (ex.reason == Failure.FUNCTION && "unspecified".equals(ex.detail))
                    throw reject(Failure.FUNCTION, type + " [unsupported parameters]");
                throw ex;
            }
        }
        return count;
    }

    private static double conditions(JsonObject object, BlockState state, int fortune, boolean silk, ItemStack tool) {
        JsonArray conditions = array(object, "conditions");
        if (conditions == null) throw reject(Failure.CONDITION);
        double result = 1;
        for (JsonElement el : conditions) result *= condition(el, state, fortune, silk, tool);
        return result;
    }

    private static double condition(JsonElement el, BlockState state, int fortune, boolean silk, ItemStack tool) {
        if (el == null || !el.isJsonObject()) throw reject(Failure.CONDITION);
        JsonObject c = el.getAsJsonObject();
        String kind = string(c, "condition", "");
        try {
            switch (kind) {
                case "minecraft:survives_explosion" -> { return 1; }
                case "minecraft:random_chance" -> {
                    Double p = probability(numberProviderMean(c.get("chance")));
                    if (p == null) throw reject(Failure.NUMBER_PROVIDER);
                    return p;
                }
                case "minecraft:random_chance_with_enchanted_bonus" -> {
                    String enchantment = string(c, "enchantment", "");
                    int level = enchantmentLevel(enchantment, fortune, silk);
                    if (level < 0) throw reject(Failure.CONDITION);
                    Double p = level == 0
                            ? probability(c.get("unenchanted_chance"))
                            : probability(levelBasedValue(c.get("enchanted_chance"), level));
                    if (p == null) throw reject(Failure.CONDITION);
                    return p;
                }
                case "minecraft:table_bonus" -> {
                    if (!"minecraft:fortune".equals(string(c, "enchantment", "")))
                        throw reject(Failure.CONDITION);
                    JsonArray values = array(c, "chances");
                    if (values == null || values.isEmpty()) throw reject(Failure.CONDITION);
                    Double p = probability(values.get(Math.min(fortune, values.size() - 1)));
                    if (p == null) throw reject(Failure.CONDITION);
                    return p;
                }
                case "minecraft:block_state_property" -> { return blockStateProperty(c, state); }
                case "minecraft:inverted" -> { return 1 - condition(c.get("term"), state, fortune, silk, tool); }
                case "minecraft:any_of", "minecraft:all_of" -> {
                    JsonArray terms = array(c, "terms");
                    if (terms == null) throw reject(Failure.CONDITION);
                    boolean any = "minecraft:any_of".equals(kind);
                    double p = any ? 0 : 1;
                    for (JsonElement term : terms) {
                        double next = condition(term, state, fortune, silk, tool);
                        p = any ? p + (1 - p) * next : p * next;
                    }
                    return p;
                }
                case "minecraft:match_tool" -> { return matchTool(c.get("predicate"), fortune, silk); }
                case "neoforge:can_item_perform_ability" -> {
                    // NeoForge tests the actual loot-context tool. The
                    // inspector uses the same tool+enchantments as excavation.
                    if (c.size() != 2 || !c.has("ability") || !c.get("ability").isJsonPrimitive()
                            || !c.get("ability").getAsJsonPrimitive().isString())
                        throw reject(Failure.CONDITION, kind + " [invalid ability]");
                    String ability = c.get("ability").getAsString();
                    if (ability.isBlank()) throw reject(Failure.CONDITION, kind + " [invalid ability]");
                    return tool.canPerformAction(ToolAction.get(ability)) ? 1 : 0;
                }
                default -> throw reject(Failure.CONDITION, kind.isEmpty() ? "<missing condition>" : kind);
            }
        } catch (Reject ex) {
            // Report the enclosing condition when its parameters are unsupported.
            if (ex.reason == Failure.CONDITION && "unspecified".equals(ex.detail))
                throw reject(Failure.CONDITION, kind + " [unsupported parameters]");
            throw ex;
        }
    }

    private static double blockStateProperty(JsonObject condition, BlockState state) {
        String id = string(condition, "block", "");
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(id)) return 0;
        JsonObject values = condition.has("properties")
                ? object(condition, "properties", Failure.CONDITION) : new JsonObject();
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            Property<?> prop = state.getBlock().getStateDefinition().getProperty(e.getKey());
            if (prop == null || !e.getValue().isJsonPrimitive()) throw reject(Failure.CONDITION);
            if (!propertyValueName(state, prop).equals(e.getValue().getAsString())) return 0;
        }
        return 1;
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static double matchTool(JsonElement element, int fortune, boolean silk) {
        if (element == null || !element.isJsonObject()) throw reject(Failure.TOOL_PREDICATE);
        JsonObject predicate = element.getAsJsonObject();
        // The real excavation tool is always a netherite pickaxe, optionally
        // enchanted with Fortune OR Silk Touch. Recognize exact item lists only.
        boolean matches = true;
        if (predicate.has("items")) {
            JsonElement entries = predicate.get("items");
            boolean found = false;
            if (entries.isJsonPrimitive()) {
                String name = entries.getAsString();
                if (name.startsWith("#")) throw reject(Failure.TOOL_PREDICATE);
                found = "minecraft:netherite_pickaxe".equals(name);
            } else if (entries.isJsonArray()) {
                for (JsonElement candidate : entries.getAsJsonArray()) {
                    if (!candidate.isJsonPrimitive()) throw reject(Failure.TOOL_PREDICATE);
                    String name = candidate.getAsString();
                    if (name.startsWith("#")) throw reject(Failure.TOOL_PREDICATE);
                    if ("minecraft:netherite_pickaxe".equals(name)) found = true;
                }
            } else throw reject(Failure.TOOL_PREDICATE);
            matches = found;
        }
        if (predicate.has("predicates")) {
            JsonObject predicates = object(predicate, "predicates", Failure.TOOL_PREDICATE);
            if (predicates.size() != 1 || !predicates.has("minecraft:enchantments"))
                throw reject(Failure.TOOL_PREDICATE);
            JsonArray enchList = array(predicates, "minecraft:enchantments");
            if (enchList == null) throw reject(Failure.TOOL_PREDICATE);
            for (JsonElement el : enchList) {
                if (!el.isJsonObject()) throw reject(Failure.TOOL_PREDICATE);
                JsonObject enchant = el.getAsJsonObject();
                int actual = enchantmentLevel(string(enchant, "enchantments", ""), fortune, silk);
                if (actual < 0) throw reject(Failure.TOOL_PREDICATE);
                if (!enchant.has("levels")) {
                    matches &= actual > 0;
                } else {
                    JsonElement levels = enchant.get("levels");
                    if (!levels.isJsonObject()) throw reject(Failure.TOOL_PREDICATE);
                    JsonObject range = levels.getAsJsonObject();
                    int min = integer(range, "min", Integer.MIN_VALUE);
                    int max = integer(range, "max", Integer.MAX_VALUE);
                    matches &= actual >= min && actual <= max;
                }
            }
        }
        // All other item predicates (components, count, enchantment tags, etc.)
        // can change outcomes in modded setups and must not be ignored.
        for (String key : predicate.keySet()) {
            if (!"items".equals(key) && !"predicates".equals(key))
                throw reject(Failure.TOOL_PREDICATE);
        }
        if (!predicate.has("items") && !predicate.has("predicates"))
            throw reject(Failure.TOOL_PREDICATE);
        return matches ? 1 : 0;
    }

    private static int enchantmentLevel(String id, int fortune, boolean silk) {
        return switch (id) {
            case "minecraft:fortune" -> fortune;
            case "minecraft:silk_touch" -> silk ? 1 : 0;
            // The synthetic pickaxe has no other enchantments.
            default -> id.startsWith("minecraft:") ? 0 : -1;
        };
    }

    private static @Nullable Double levelBasedValue(JsonElement element, int enchantmentLevel) {
        if (element == null) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber())
            return element.getAsDouble();
        if (!element.isJsonObject()) return null;
        JsonObject v = element.getAsJsonObject();
        return switch (string(v, "type", "")) {
            case "minecraft:constant" -> fixedNumber(v.get("value"), null);
            case "minecraft:linear" -> {
                Double base = fixedNumber(v.get("base"), null);
                Double slope = fixedNumber(v.get("per_level_above_first"), null);
                yield base == null || slope == null ? null : base + slope * (enchantmentLevel - 1);
            }
            default -> null;
        };
    }

    private static void requireNoOpFunctions(JsonObject object, String location) {
        JsonArray functions = array(object, "functions");
        if (functions == null) throw reject(Failure.FUNCTION, location + " [invalid functions]");
        for (JsonElement el : functions) {
            if (!el.isJsonObject()) throw reject(Failure.FUNCTION, location + " [invalid function]");
            JsonObject fn = el.getAsJsonObject();
            String type = string(fn, "function", "<missing function>");
            if (!"minecraft:explosion_decay".equals(type) || fn.has("conditions"))
                throw reject(Failure.FUNCTION, type + " [" + location
                        + (fn.has("conditions") ? ", conditional]" : "]"));
        }
    }

    private static double numberProviderMean(JsonElement el) {
        if (el == null) throw reject(Failure.NUMBER_PROVIDER);
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsDouble();
        if (!el.isJsonObject()) throw reject(Failure.NUMBER_PROVIDER);
        JsonObject number = el.getAsJsonObject();
        return switch (string(number, "type", "minecraft:constant")) {
            case "minecraft:constant" -> {
                Double n = fixedNumber(number.get("value"), null);
                if (n == null) throw reject(Failure.NUMBER_PROVIDER);
                yield n;
            }
            case "minecraft:uniform" -> {
                Double lo = fixedNumber(number.get("min"), null);
                Double hi = fixedNumber(number.get("max"), null);
                if (lo == null || hi == null || lo > hi || Math.rint(lo) != lo || Math.rint(hi) != hi)
                    throw reject(Failure.NUMBER_PROVIDER);
                yield (lo + hi) / 2;
            }
            case "minecraft:binomial" -> {
                Double n = fixedNumber(number.get("n"), null);
                Double p = probability(number.get("p"));
                if (n == null || p == null || n < 0 || n > 256 || Math.rint(n) != n)
                    throw reject(Failure.NUMBER_PROVIDER);
                yield n * p;
            }
            default -> throw reject(Failure.NUMBER_PROVIDER,
                    string(number, "type", "<missing number provider>"));
        };
    }

    private static @Nullable Double fixedNumber(JsonElement el, @Nullable Double otherwise) {
        if (el == null) return otherwise;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsDouble();
        if (el.isJsonObject() && "minecraft:constant".equals(string(el.getAsJsonObject(), "type", "")))
            return fixedNumber(el.getAsJsonObject().get("value"), null);
        return null;
    }

    private static @Nullable Double probability(JsonElement el) {
        Double p = fixedNumber(el, null);
        return probability(p);
    }

    private static @Nullable Double probability(@Nullable Double p) {
        return p == null || !Double.isFinite(p) || p < 0 || p > 1 ? null : p;
    }

    private static JsonObject object(JsonObject parent, String key, Failure failure) {
        JsonElement el = parent.get(key);
        if (el == null || !el.isJsonObject()) throw reject(failure);
        return el.getAsJsonObject();
    }

    private static boolean booleanValue(JsonObject parent, String key, boolean fallback) {
        JsonElement el = parent.get(key);
        if (el == null) return fallback;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean())
            throw reject(Failure.FUNCTION);
        return el.getAsBoolean();
    }

    private static @Nullable JsonArray array(JsonObject obj, String key) {
        if (!obj.has(key)) return new JsonArray();
        return obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject obj, String key, int fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isNumber()
                ? obj.get(key).getAsInt() : fallback;
    }

    private static void merge(Map<Item, Double> target, Map<Item, Double> additions, double multiplier) {
        for (Map.Entry<Item, Double> e : additions.entrySet())
            target.merge(e.getKey(), e.getValue() * multiplier, Double::sum);
    }

    private static void scale(Map<Item, Double> values, double multiplier) {
        for (Map.Entry<Item, Double> e : values.entrySet()) e.setValue(e.getValue() * multiplier);
    }
}
