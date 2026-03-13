package com.autosort;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod configuration stored as JSON. Integrates with Mod Menu for a settings screen.
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("chestsort/config.json");

    private static ModConfig INSTANCE;

    // Settings
    public boolean autoSortOnClose = false;
    public String sortTemplate = "ALPHABETICAL";
    public boolean highlightFillColors = true;
    public double walkSpeed = 0.23;
    public int clickCooldown = 5;
    public List<String> favoriteItems = new ArrayList<>();

    public enum SortTemplate {
        ALPHABETICAL("Alphabetical"),
        STACK_SIZE("Stack Size (largest first)"),
        CATEGORY("By Category"),
        VALUABLES_FIRST("Valuables First");

        public final String displayName;

        SortTemplate(String displayName) {
            this.displayName = displayName;
        }
    }

    public static ModConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public SortTemplate getSortTemplate() {
        try {
            return SortTemplate.valueOf(sortTemplate);
        } catch (IllegalArgumentException e) {
            return SortTemplate.ALPHABETICAL;
        }
    }

    public void cycleSortTemplate() {
        SortTemplate[] values = SortTemplate.values();
        SortTemplate current = getSortTemplate();
        int next = (current.ordinal() + 1) % values.length;
        sortTemplate = values[next].name();
    }

    public boolean isFavorite(String itemId) {
        return favoriteItems.contains(itemId);
    }

    public boolean toggleFavorite(String itemId) {
        if (favoriteItems.contains(itemId)) {
            favoriteItems.remove(itemId);
            return false;
        } else {
            favoriteItems.add(itemId);
            return true;
        }
    }

    public static ModConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                String json = Files.readString(CONFIG_FILE);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                if (config != null) return config;
            } catch (IOException e) {
                ChestSortClient.LOGGER.error("Failed to load config", e);
            }
        }
        return new ModConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(this));
        } catch (IOException e) {
            ChestSortClient.LOGGER.error("Failed to save config", e);
        }
    }
}
