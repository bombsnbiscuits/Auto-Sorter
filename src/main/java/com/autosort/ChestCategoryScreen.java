package com.autosort;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Two-level GUI for assigning categories to registered chests.
 *
 * Level 1: Pick a category (Building Blocks, Tools, Food, etc.)
 * Level 2: See all chests — toggle which ones belong to that category.
 *
 * Or alternatively: show all chests, click one to cycle/pick its category.
 *
 * This implementation shows all chests in a scrollable list.  Each chest has
 * a button that opens a category picker sub-screen.
 */
public class ChestCategoryScreen extends Screen {

    private final Screen parent;
    private List<ChestRegistry.ChestEntry> chests;
    private int scrollOffset = 0;
    private static final int ROWS_VISIBLE = 8;
    private static final int ROW_HEIGHT = 24;

    public ChestCategoryScreen(Screen parent) {
        super(Text.literal("Assign Chest Categories"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        chests = ChestSortClient.registry.getChests();
        scrollOffset = 0;
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearChildren();

        int centerX = this.width / 2;
        int startY = 40;
        int buttonW = 340;
        int buttonH = 20;

        int end = Math.min(scrollOffset + ROWS_VISIBLE, chests.size());
        for (int i = scrollOffset; i < end; i++) {
            ChestRegistry.ChestEntry entry = chests.get(i);
            int y = startY + (i - scrollOffset) * ROW_HEIGHT;

            String catDisplay = entry.categoryOverride != null ? entry.categoryOverride : "None";
            String label = entry.getDisplayName() + "  ->  [" + catDisplay + "]";

            final int index = i;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(label),
                    button -> client.setScreen(new CategoryPickerScreen(this, chests.get(index)))
            ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        }

        // Scroll buttons
        if (scrollOffset > 0) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("^ Up"),
                    button -> { scrollOffset = Math.max(0, scrollOffset - ROWS_VISIBLE); rebuildButtons(); }
            ).dimensions(centerX - 50, startY - 22, 100, 18).build());
        }
        if (end < chests.size()) {
            int scrollBtnY = startY + ROWS_VISIBLE * ROW_HEIGHT + 4;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("v Down"),
                    button -> { scrollOffset = Math.min(chests.size() - 1, scrollOffset + ROWS_VISIBLE); rebuildButtons(); }
            ).dimensions(centerX - 50, scrollBtnY, 100, 18).build());
        }

        // Clear All Assignments button
        int bottomY = startY + ROWS_VISIBLE * ROW_HEIGHT + 28;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Clear All Assignments"),
                button -> {
                    for (ChestRegistry.ChestEntry entry : chests) {
                        entry.categoryOverride = null;
                    }
                    ChestSortClient.registry.save();
                    ChestSortClient.registry.clearAssignments();
                    rebuildButtons();
                }
        ).dimensions(centerX - 160, bottomY, 150, 20).build());

        // Save & Close
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Save & Close"),
                button -> {
                    saveAssignmentsFromOverrides();
                    close();
                }
        ).dimensions(centerX + 10, bottomY, 150, 20).build());
    }

    /**
     * Sync the categoryOverride fields on ChestEntry into the assignments file
     * that DepositRunManager reads.
     */
    private void saveAssignmentsFromOverrides() {
        ChestSortClient.registry.save();

        Map<BlockPos, String> assignments = new LinkedHashMap<>();
        for (ChestRegistry.ChestEntry entry : chests) {
            if (entry.categoryOverride != null && !entry.categoryOverride.isEmpty()) {
                assignments.put(entry.toBlockPos(), entry.categoryOverride);
            }
        }
        ChestSortClient.registry.saveAssignments(assignments);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount > 0 && scrollOffset > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            rebuildButtons();
        } else if (verticalAmount < 0 && scrollOffset + ROWS_VISIBLE < chests.size()) {
            scrollOffset = Math.min(chests.size() - ROWS_VISIBLE, scrollOffset + 1);
            rebuildButtons();
        }
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(chests.size() + " registered chests  |  Click to assign category"),
                this.width / 2, 26, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        saveAssignmentsFromOverrides();
        client.setScreen(parent);
    }

    // =====================================================================
    // Inner screen: pick a category for a specific chest
    // =====================================================================
    private static class CategoryPickerScreen extends Screen {

        private final ChestCategoryScreen parentScreen;
        private final ChestRegistry.ChestEntry entry;

        protected CategoryPickerScreen(ChestCategoryScreen parentScreen, ChestRegistry.ChestEntry entry) {
            super(Text.literal("Pick Category for " + entry.getDisplayName()));
            this.parentScreen = parentScreen;
            this.entry = entry;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int y = 44;
            int buttonW = 200;
            int buttonH = 20;
            int spacing = 22;

            // "None" option to clear
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("None (clear)"),
                    button -> {
                        entry.categoryOverride = null;
                        ChestSortClient.registry.save();
                        client.setScreen(parentScreen);
                        parentScreen.rebuildButtons();
                    }
            ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
            y += spacing;

            // One button per category
            for (ItemCategoryFilter.Category cat : ItemCategoryFilter.Category.values()) {
                String prefix = cat.displayName.equals(entry.categoryOverride) ? "> " : "  ";
                addDrawableChild(ButtonWidget.builder(
                        Text.literal(prefix + cat.displayName),
                        button -> {
                            entry.categoryOverride = cat.displayName;
                            ChestSortClient.registry.save();
                            client.setScreen(parentScreen);
                            parentScreen.rebuildButtons();
                        }
                ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
                y += spacing;
            }

            // Back button
            y += 6;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("Back"),
                    button -> client.setScreen(parentScreen)
            ).dimensions(centerX - 40, y, 80, buttonH).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
            String current = entry.categoryOverride != null ? entry.categoryOverride : "None";
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Current: " + current),
                    this.width / 2, 28, 0xFF55FF55);
        }

        @Override
        public void close() {
            client.setScreen(parentScreen);
        }
    }
}
