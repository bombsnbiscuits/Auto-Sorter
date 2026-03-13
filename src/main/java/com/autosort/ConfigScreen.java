package com.autosort;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Simple config screen using vanilla widgets. Compatible with Mod Menu.
 */
public class ConfigScreen extends Screen {

    private final Screen parent;
    private final ModConfig config;

    public ConfigScreen(Screen parent) {
        super(Text.literal("Chest Auto Sorter Settings"));
        this.parent = parent;
        this.config = ModConfig.get();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 40;
        int buttonW = 250;
        int buttonH = 20;
        int spacing = 24;

        // Auto-sort on close
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Auto-Sort on Close: " + (config.autoSortOnClose ? "ON" : "OFF")),
                button -> {
                    config.autoSortOnClose = !config.autoSortOnClose;
                    button.setMessage(Text.literal("Auto-Sort on Close: " + (config.autoSortOnClose ? "ON" : "OFF")));
                }
        ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        y += spacing;

        // Sort template
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Sort Order: " + config.getSortTemplate().displayName),
                button -> {
                    config.cycleSortTemplate();
                    button.setMessage(Text.literal("Sort Order: " + config.getSortTemplate().displayName));
                }
        ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        y += spacing;

        // Highlight fill colors
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Fill-Based Highlight Colors: " + (config.highlightFillColors ? "ON" : "OFF")),
                button -> {
                    config.highlightFillColors = !config.highlightFillColors;
                    button.setMessage(Text.literal("Fill-Based Highlight Colors: " + (config.highlightFillColors ? "ON" : "OFF")));
                }
        ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        y += spacing;

        // Walk speed
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Walk Speed: " + formatSpeed(config.walkSpeed)),
                button -> {
                    if (config.walkSpeed >= 0.30) {
                        config.walkSpeed = 0.15;
                    } else {
                        config.walkSpeed += 0.05;
                    }
                    config.walkSpeed = Math.round(config.walkSpeed * 100.0) / 100.0;
                    button.setMessage(Text.literal("Walk Speed: " + formatSpeed(config.walkSpeed)));
                }
        ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        y += spacing;

        // Click cooldown
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Click Cooldown: " + config.clickCooldown + " ticks"),
                button -> {
                    config.clickCooldown = config.clickCooldown >= 10 ? 2 : config.clickCooldown + 1;
                    button.setMessage(Text.literal("Click Cooldown: " + config.clickCooldown + " ticks"));
                }
        ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        y += spacing;

        // Favorite items count
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Favorite Items: " + config.favoriteItems.size()
                        + " (use /chestsort favorite)"),
                button -> {}
        ).dimensions(centerX - buttonW / 2, y, buttonW, buttonH).build());
        y += spacing + 12;

        // Done button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Done"),
                button -> {
                    config.save();
                    client.setScreen(parent);
                }
        ).dimensions(centerX - 50, y, 100, buttonH).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        config.save();
        client.setScreen(parent);
    }

    private String formatSpeed(double speed) {
        if (speed <= 0.17) return "Slow";
        if (speed <= 0.23) return "Normal";
        if (speed <= 0.27) return "Fast";
        return "Sprint";
    }
}
