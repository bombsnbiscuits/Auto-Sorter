package com.autosort;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.stream.*;

/**
 * Categorizes items into groups for cross-chest organization.
 * Uses item properties and registry paths to assign categories.
 */
public class ItemCategoryFilter {

    public enum Category {
        BUILDING_BLOCKS("Building Blocks"),
        ORES_AND_MINERALS("Ores & Minerals"),
        TOOLS("Tools"),
        WEAPONS_AND_ARMOR("Weapons & Armor"),
        FOOD("Food"),
        REDSTONE("Redstone"),
        BREWING("Brewing"),
        NATURE("Nature"),
        DECORATION("Decoration"),
        SHULKER_BOXES("Shulker Boxes"),
        MISCELLANEOUS("Miscellaneous");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    // Keywords in registry paths that map to categories
    private static final Map<String, Category> PATH_KEYWORDS = new LinkedHashMap<>();

    static {
        // Ores & minerals
        for (String s : new String[]{
                "ore", "raw_iron", "raw_gold", "raw_copper", "ingot", "nugget",
                "diamond", "emerald", "lapis", "quartz", "amethyst", "coal",
                "netherite", "ancient_debris"}) {
            PATH_KEYWORDS.put(s, Category.ORES_AND_MINERALS);
        }

        // Redstone
        for (String s : new String[]{
                "redstone", "piston", "observer", "repeater", "comparator",
                "lever", "button", "pressure_plate", "tripwire", "hopper",
                "dropper", "dispenser", "daylight_detector", "target",
                "sculk_sensor", "lightning_rod", "tnt"}) {
            PATH_KEYWORDS.put(s, Category.REDSTONE);
        }

        // Brewing
        for (String s : new String[]{
                "potion", "blaze", "nether_wart", "ghast_tear", "magma_cream",
                "fermented", "spider_eye", "glistering", "rabbit_foot",
                "phantom_membrane", "dragon_breath", "brewing", "cauldron",
                "glass_bottle"}) {
            PATH_KEYWORDS.put(s, Category.BREWING);
        }

        // Food
        for (String s : new String[]{
                "bread", "steak", "porkchop", "chicken", "mutton", "rabbit",
                "cod", "salmon", "apple", "carrot", "potato", "beetroot",
                "melon_slice", "sweet_berries", "glow_berries", "cookie",
                "cake", "pie", "soup", "stew", "honey_bottle", "dried_kelp",
                "golden_apple", "enchanted_golden_apple", "chorus_fruit",
                "tropical_fish", "cooked_"}) {
            PATH_KEYWORDS.put(s, Category.FOOD);
        }

        // Nature
        for (String s : new String[]{
                "sapling", "seed", "flower", "grass", "fern", "vine",
                "lily_pad", "kelp", "seagrass", "bamboo", "cactus",
                "sugar_cane", "mushroom", "azalea", "moss", "dripleaf",
                "spore_blossom", "hanging_roots", "mangrove_propagule",
                "dirt", "sand", "gravel", "clay", "snow", "ice",
                "log", "wood", "leaves", "bee"}) {
            PATH_KEYWORDS.put(s, Category.NATURE);
        }

        // Decoration
        for (String s : new String[]{
                "painting", "item_frame", "armor_stand", "banner", "candle",
                "lantern", "campfire", "bell", "flower_pot", "head", "skull",
                "carpet", "bed", "sign", "torch", "chain", "end_rod",
                "sea_pickle", "glow_lichen", "coral"}) {
            PATH_KEYWORDS.put(s, Category.DECORATION);
        }

        // Building blocks
        for (String s : new String[]{
                "stone", "brick", "planks", "slab", "stairs", "wall",
                "fence", "gate", "door", "trapdoor", "glass", "concrete",
                "terracotta", "wool", "copper", "deepslate", "tuff",
                "calcite", "dripstone", "basalt", "blackstone", "prismarine",
                "purpur", "end_stone", "sandstone", "cobblestone", "andesite",
                "diorite", "granite", "obsidian", "crying_obsidian",
                "nether_bricks", "quartz_block", "smooth_"}) {
            PATH_KEYWORDS.put(s, Category.BUILDING_BLOCKS);
        }
    }

    /**
     * Categorize an item stack.
     */
    public static Category categorize(ItemStack stack) {
        if (stack.isEmpty()) return Category.MISCELLANEOUS;

        Item item = stack.getItem();

        // Shulker boxes are their own category
        if (item instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return Category.SHULKER_BOXES;
        }

        // Food (check component before keyword matching)
        if (item.getComponents().contains(DataComponentTypes.FOOD)) {
            return Category.FOOD;
        }

        // Use registry path for all classification (classes like ToolItem,
        // SwordItem, ArmorItem were removed in 1.21.10)
        Identifier id = Registries.ITEM.getId(item);
        String path = id.getPath();

        // Tools - check before general keywords
        if (path.contains("pickaxe") || path.contains("axe") || path.contains("shovel")
                || path.contains("hoe") || path.contains("shears")
                || path.contains("fishing_rod") || path.contains("flint_and_steel")
                || path.contains("compass") || path.contains("spyglass")
                || path.contains("brush") || path.contains("bucket")) {
            return Category.TOOLS;
        }

        // Weapons & armor
        if (path.contains("sword") || path.contains("bow") || path.contains("crossbow")
                || path.contains("trident") || path.contains("shield") || path.contains("mace")
                || path.contains("arrow") || path.contains("helmet") || path.contains("chestplate")
                || path.contains("leggings") || path.contains("boots")
                || path.contains("horse_armor") || path.contains("wolf_armor")) {
            return Category.WEAPONS_AND_ARMOR;
        }

        for (Map.Entry<String, Category> entry : PATH_KEYWORDS.entrySet()) {
            if (path.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return Category.MISCELLANEOUS;
    }

    /**
     * Get a sort key for a shulker box based on its contents.
     * Uses a fuzzy approach: groups shulkers by what TYPES of items they contain
     * (ignoring counts), so a shulker with 2 cauldrons and one with 1 cauldron
     * sort next to each other. Within the same item-type group, sorts by total count.
     */
    public static String getShulkerSortKey(ItemStack shulkerStack) {
        List<ItemStack> contents = getShulkerContents(shulkerStack);
        if (contents.isEmpty()) {
            return "zzz_empty_shulker";
        }

        // Build a fingerprint of item TYPES (ignoring counts) using TreeMap for stable ordering
        Map<String, Integer> itemCounts = new TreeMap<>();
        Map<Category, Integer> categoryCounts = new EnumMap<>(Category.class);
        int totalItems = 0;

        for (ItemStack stack : contents) {
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            itemCounts.merge(id, stack.getCount(), Integer::sum);
            categoryCounts.merge(categorize(stack), stack.getCount(), Integer::sum);
            totalItems += stack.getCount();
        }

        // Primary sort: dominant category
        Category primaryCategory = categoryCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Category.MISCELLANEOUS);

        // Fuzzy fingerprint: just the item types (no counts), so shulkers with
        // the same items but different amounts sort together
        String typeFingerprint = String.join("|", itemCounts.keySet());

        // Secondary sort by total item count (more items = earlier) using inverted count
        String countPadded = String.format("%06d", 999999 - totalItems);

        return primaryCategory.ordinal() + "_" + primaryCategory.displayName
                + "_" + typeFingerprint + "_" + countPadded;
    }

    /**
     * Extract items from a shulker box item stack.
     */
    public static List<ItemStack> getShulkerContents(ItemStack shulkerStack) {
        List<ItemStack> result = new ArrayList<>();
        ContainerComponent container = shulkerStack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            container.stream().forEach(result::add);
        }
        return result;
    }
}
