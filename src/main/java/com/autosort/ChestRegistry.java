package com.autosort;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ChestRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<ChestEntry> chests = new ArrayList<>();
    private String currentWorld = "";
    private Path currentFile;

    public void load(String worldId) {
        this.currentWorld = worldId;
        this.currentFile = getRegistryFile(worldId);
        this.chests.clear();

        if (Files.exists(currentFile)) {
            try {
                String json = Files.readString(currentFile);
                Type listType = new TypeToken<List<ChestEntry>>() {}.getType();
                List<ChestEntry> loaded = GSON.fromJson(json, listType);
                if (loaded != null) {
                    chests.addAll(loaded);
                }
            } catch (IOException e) {
                ChestSortClient.LOGGER.error("Failed to load chest registry", e);
            }
        }
    }

    public void save() {
        if (currentFile == null) return;

        try {
            Files.createDirectories(currentFile.getParent());
            String json = GSON.toJson(chests);
            Files.writeString(currentFile, json);
        } catch (IOException e) {
            ChestSortClient.LOGGER.error("Failed to save chest registry", e);
        }
    }

    public void addChest(BlockPos pos) {
        ChestEntry entry = new ChestEntry(pos.getX(), pos.getY(), pos.getZ());
        if (!chests.contains(entry)) {
            chests.add(entry);
            ChestSortClient.LOGGER.info("Registered chest at {}", pos.toShortString());
        }
    }

    public boolean removeChest(int x, int y, int z) {
        return chests.remove(new ChestEntry(x, y, z));
    }

    public void clear() {
        chests.clear();
    }

    public List<ChestEntry> getChests() {
        return new ArrayList<>(chests);
    }

    public int size() {
        return chests.size();
    }

    public ChestEntry getEntry(int x, int y, int z) {
        ChestEntry search = new ChestEntry(x, y, z);
        for (ChestEntry entry : chests) {
            if (entry.equals(search)) return entry;
        }
        return null;
    }

    public ChestEntry getEntry(BlockPos pos) {
        return getEntry(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean isLocked(BlockPos pos) {
        ChestEntry entry = getEntry(pos);
        return entry != null && entry.locked;
    }

    // ==================== Saved category assignments ====================

    public void saveAssignments(Map<BlockPos, String> assignments) {
        Path file = getAssignmentsFile();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> serialized = new LinkedHashMap<>();
            for (var e : assignments.entrySet()) {
                serialized.put(posToKey(e.getKey()), e.getValue());
            }
            Files.writeString(file, GSON.toJson(serialized));
        } catch (IOException e) {
            ChestSortClient.LOGGER.error("Failed to save assignments", e);
        }
    }

    public Map<BlockPos, String> loadAssignments() {
        Path file = getAssignmentsFile();
        if (file == null || !Files.exists(file)) return Collections.emptyMap();
        try {
            String json = Files.readString(file);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> raw = GSON.fromJson(json, type);
            if (raw == null) return Collections.emptyMap();
            Map<BlockPos, String> result = new LinkedHashMap<>();
            for (var e : raw.entrySet()) {
                BlockPos pos = keyToPos(e.getKey());
                if (pos != null) result.put(pos, e.getValue());
            }
            return result;
        } catch (IOException e) {
            ChestSortClient.LOGGER.error("Failed to load assignments", e);
            return Collections.emptyMap();
        }
    }

    public void clearAssignments() {
        Path file = getAssignmentsFile();
        if (file != null) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    private Path getAssignmentsFile() {
        if (currentWorld.isEmpty()) return null;
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("chestsort");
        return configDir.resolve("assignments_" + currentWorld + ".json");
    }

    private static String posToKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos keyToPos(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Path getRegistryFile(String worldId) {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("chestsort");
        return configDir.resolve("registry_" + worldId + ".json");
    }

    public static class ChestEntry {
        public int x, y, z;
        public String label;
        public boolean locked;
        public String categoryOverride;
        public String group;

        public ChestEntry() {}

        public ChestEntry(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        public String getDisplayName() {
            if (label != null && !label.isEmpty()) {
                return "\"" + label + "\" (" + x + ", " + y + ", " + z + ")";
            }
            return "(" + x + ", " + y + ", " + z + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChestEntry that)) return false;
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }

        @Override
        public String toString() {
            return getDisplayName();
        }
    }
}
