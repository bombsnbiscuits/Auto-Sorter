package com.autosort;

import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Scans chest contents when they are opened (client-side).
 * Stores scan results for the category report.
 */
public class ChestScanner {

    // Cached scan results per chest position
    private static final Map<BlockPos, ChestContents> scanCache = new LinkedHashMap<>();

    public static class ChestContents {
        public final BlockPos pos;
        public final List<ItemStack> items;
        public final int slotCount;
        public final long scanTime;

        public ChestContents(BlockPos pos, List<ItemStack> items, int slotCount) {
            this.pos = pos;
            this.items = items;
            this.slotCount = slotCount;
            this.scanTime = System.currentTimeMillis();
        }

        public int usedSlots() {
            return (int) items.stream().filter(s -> !s.isEmpty()).count();
        }

        /**
         * Get a summary of categories in this chest.
         */
        public Map<ItemCategoryFilter.Category, Integer> getCategorySummary() {
            Map<ItemCategoryFilter.Category, Integer> summary = new EnumMap<>(ItemCategoryFilter.Category.class);
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    ItemCategoryFilter.Category cat = ItemCategoryFilter.categorize(stack);
                    summary.merge(cat, stack.getCount(), Integer::sum);
                }
            }
            return summary;
        }
    }

    /**
     * Scan the currently open chest screen and cache the results.
     */
    public static ChestContents scanOpenChest(GenericContainerScreen screen, BlockPos pos) {
        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9;

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < containerSlots; i++) {
            items.add(handler.getSlot(i).getStack().copy());
        }

        ChestContents contents = new ChestContents(pos, items, containerSlots);
        scanCache.put(pos, contents);
        return contents;
    }

    /**
     * Get cached scan for a position.
     */
    public static ChestContents getCached(BlockPos pos) {
        return scanCache.get(pos);
    }

    /**
     * Get all cached scans.
     */
    public static Map<BlockPos, ChestContents> getAllCached() {
        return Collections.unmodifiableMap(scanCache);
    }

    /**
     * Clear all cached scans.
     */
    public static void clearCache() {
        scanCache.clear();
    }

    /**
     * Clear cached scan for a specific chest position.
     */
    public static void clearCacheFor(BlockPos pos) {
        scanCache.remove(pos);
    }

    /**
     * Get a full report of all scanned chests with category breakdowns.
     */
    public static List<String> generateReport() {
        List<String> lines = new ArrayList<>();

        if (scanCache.isEmpty()) {
            lines.add("No chests have been scanned yet.");
            lines.add("Open each chest to scan it, then run /chestsort report");
            return lines;
        }

        // Overall item counts by category
        Map<ItemCategoryFilter.Category, Integer> globalCounts = new EnumMap<>(ItemCategoryFilter.Category.class);

        for (ChestContents contents : scanCache.values()) {
            Map<ItemCategoryFilter.Category, Integer> summary = contents.getCategorySummary();
            summary.forEach((cat, count) -> globalCounts.merge(cat, count, Integer::sum));
        }

        lines.add("=== Chest Scan Report ===");
        lines.add("Scanned " + scanCache.size() + " chests");
        lines.add("");
        lines.add("Items by category:");
        for (ItemCategoryFilter.Category cat : ItemCategoryFilter.Category.values()) {
            int count = globalCounts.getOrDefault(cat, 0);
            if (count > 0) {
                lines.add("  " + cat.displayName + ": " + count + " items");
            }
        }

        lines.add("");
        lines.add("Per-chest breakdown:");
        for (ChestContents contents : scanCache.values()) {
            lines.add("  " + contents.pos.toShortString() + " ("
                    + contents.usedSlots() + "/" + contents.slotCount + " slots):");
            Map<ItemCategoryFilter.Category, Integer> summary = contents.getCategorySummary();
            // Show top category for this chest
            summary.entrySet().stream()
                    .sorted(Map.Entry.<ItemCategoryFilter.Category, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> lines.add("    " + e.getKey().displayName + ": " + e.getValue()));
        }

        return lines;
    }
}
