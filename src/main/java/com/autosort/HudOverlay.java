package com.autosort;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/**
 * Renders a small HUD overlay showing sort/organize/deposit progress.
 */
public class HudOverlay {

    public static void register() {
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            render(drawContext);
        });
    }

    private static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        TextRenderer textRenderer = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();

        // Run status (top-right)
        String status = null;
        if (ChestSortClient.sortRunManager != null && ChestSortClient.sortRunManager.isRunning()) {
            status = ChestSortClient.sortRunManager.getStatusText();
        } else if (ChestSortClient.organizeRunManager != null && ChestSortClient.organizeRunManager.isRunning()) {
            status = ChestSortClient.organizeRunManager.getStatusText();
        } else if (ChestSortClient.depositRunManager != null && ChestSortClient.depositRunManager.isRunning()) {
            status = ChestSortClient.depositRunManager.getStatusText();
        }

        if (status != null) {
            String[] lines = status.split("\n");
            int y = 4;
            for (String line : lines) {
                int width = textRenderer.getWidth(line);
                int x = screenWidth - width - 4;
                context.fill(x - 2, y - 1, x + width + 2, y + 9, 0x80000000);
                context.drawTextWithShadow(textRenderer, Text.literal(line), x, y, 0xFF55FF55);
                y += 11;
            }
        }

        // Assign mode indicator (top-center)
        String catName = ChestSortClient.getSelectedCategoryName();
        if (catName != null) {
            int[] colors = {0xFFFFFFFF, 0xFFFFFF55, 0xFFFFFFFF};
            String[] lines = {"[Assign Mode]", catName, "M = cycle | N = assign | Shift+M = menu"};

            int y = 4;
            for (int i = 0; i < lines.length; i++) {
                int width = textRenderer.getWidth(lines[i]);
                int x = screenWidth / 2 - width / 2;
                drawLine(context, textRenderer, lines[i], x, y, colors[i]);
                y += 11;
            }
        }

        // Chest tooltip: when looking at a registered chest, show category + item breakdown
        renderChestTooltip(context, client, textRenderer, screenWidth);
    }

    /** Draw a single HUD line with a dark background behind it, then text on top. */
    private static void drawLine(DrawContext context, TextRenderer textRenderer,
                                  String text, int x, int y, int color) {
        int width = textRenderer.getWidth(text);
        context.fill(x - 2, y - 1, x + width + 2, y + 9, 0x80000000);
        context.drawTextWithShadow(textRenderer, Text.literal(text), x, y, color);
    }

    private static void renderChestTooltip(DrawContext context, MinecraftClient client,
                                            TextRenderer textRenderer, int screenWidth) {
        BlockPos lookPos = ChestDetector.getOpenChestPos(client);
        if (lookPos == null) return;

        ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(lookPos);
        if (entry == null) return;

        // Rescan live if the chest is currently open so fill data stays fresh
        if (client.currentScreen instanceof GenericContainerScreen containerScreen) {
            ChestScanner.scanOpenChest(containerScreen, lookPos);
        }
        ChestScanner.ChestContents contents = ChestScanner.getCached(lookPos);

        int screenHeight = client.getWindow().getScaledHeight();
        int tooltipX = screenWidth / 2 + 12;
        int y = screenHeight / 2 - 10;

        // Header: chest name + assigned category
        String assignedCat = entry.categoryOverride != null ? entry.categoryOverride : "Unassigned";

        drawLine(context, textRenderer, entry.getDisplayName(), tooltipX, y, 0xFFFFFFFF);
        y += 11;
        drawLine(context, textRenderer, "Category: " + assignedCat, tooltipX, y, 0xFFFFFF55);
        y += 11;

        if (contents != null) {
            drawLine(context, textRenderer,
                    "Fill: " + contents.usedSlots() + "/" + contents.slotCount + " slots",
                    tooltipX, y, 0xFFAAAAAA);
            y += 11;

            // Category breakdown (top 5 by count)
            var sorted = contents.getCategorySummary().entrySet().stream()
                    .sorted(Map.Entry.<ItemCategoryFilter.Category, Integer>comparingByValue().reversed())
                    .limit(5)
                    .toList();
            for (var e : sorted) {
                drawLine(context, textRenderer,
                        "  " + e.getKey().displayName + ": " + e.getValue(),
                        tooltipX, y, 0xFF88FF88);
                y += 11;
            }
        } else {
            drawLine(context, textRenderer, "(Open chest to scan)", tooltipX, y, 0xFF888888);
        }
    }
}
