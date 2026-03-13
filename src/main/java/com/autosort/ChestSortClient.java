package com.autosort;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ChestSortClient implements ClientModInitializer {

    public static final String MOD_ID = "chestsort";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static KeyBinding sortKeybind;
    public static KeyBinding deselectKeybind;
    public static KeyBinding highlightKeybind;
    public static KeyBinding organizeKeybind;
    public static KeyBinding depositKeybind;
    public static KeyBinding menuKeybind;
    public static ChestRegistry registry;
    public static SortRunManager sortRunManager;
    public static OrganizeRunManager organizeRunManager;
    public static DepositRunManager depositRunManager;

    public static KeyBinding assignKeybind;

    private static boolean highlightEnabled = false;
    private static int highlightTick = 0;

    // Search highlight - chests found by /chestsort find
    public static Set<BlockPos> searchHighlights = new HashSet<>();
    public static int searchHighlightTicks = 0;

    // Auto-sort on close tracking
    private static boolean wasChestOpen = false;
    private static BlockPos lastOpenChestPos = null;

    // Category assignment mode
    private static int selectedCategoryIndex = -1; // -1 = off, 0..N = category index
    public static boolean assignModeActive = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Chest Auto Sorter initializing...");

        // Load config
        ModConfig.get();

        registry = new ChestRegistry();
        sortRunManager = new SortRunManager();
        organizeRunManager = new OrganizeRunManager();
        depositRunManager = new DepositRunManager();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "category"));

        sortKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.sort_all",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category
        ));

        deselectKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.deselect",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                category
        ));

        highlightKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.highlight",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                category
        ));

        organizeKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.organize",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                category
        ));

        depositKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.deposit",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                category
        ));

        menuKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                category
        ));

        assignKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestsort.assign",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                category
        ));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String worldName = getWorldIdentifier(client);
            registry.load(worldName);
            LOGGER.info("Loaded chest registry for world: {}", worldName);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            cancelAllRuns();
            registry.save();
            highlightEnabled = false;
            searchHighlights.clear();
            LOGGER.info("Saved chest registry on disconnect.");
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GenericContainerScreen containerScreen) {
                BlockPos chestPos = ChestDetector.getOpenChestPos(client);
                if (chestPos != null) {
                    registry.addChest(chestPos);
                    registry.save();
                    ChestScanner.scanOpenChest(containerScreen, chestPos);
                    wasChestOpen = true;
                    lastOpenChestPos = chestPos;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Auto-sort on close
            if (wasChestOpen && !(client.currentScreen instanceof GenericContainerScreen)) {
                wasChestOpen = false;
                if (ModConfig.get().autoSortOnClose && lastOpenChestPos != null && !isAutoOperating()) {
                    // Re-open and sort this chest
                    sortRunManager.startSingle(client, lastOpenChestPos);
                }
                lastOpenChestPos = null;
            }

            // K key: pause/resume if running, cancel if already paused, otherwise start sort
            if (sortKeybind.wasPressed()) {
                if (depositRunManager.isRunning()) {
                    if (depositRunManager.isPaused()) {
                        depositRunManager.cancel();
                        if (client.player != null)
                            client.player.sendMessage(Text.literal("[ChestSort] Deposit run cancelled."), true);
                    } else {
                        depositRunManager.togglePause();
                        if (client.player != null)
                            client.player.sendMessage(Text.literal("[ChestSort] Deposit run paused. K again to cancel."), true);
                    }
                } else if (organizeRunManager.isRunning()) {
                    if (organizeRunManager.isPaused()) {
                        organizeRunManager.cancel();
                        if (client.player != null)
                            client.player.sendMessage(Text.literal("[ChestSort] Organize run cancelled."), true);
                    } else {
                        organizeRunManager.togglePause();
                        if (client.player != null)
                            client.player.sendMessage(Text.literal("[ChestSort] Organize run paused. K again to cancel."), true);
                    }
                } else if (sortRunManager.isRunning()) {
                    if (sortRunManager.isPaused()) {
                        sortRunManager.cancel();
                        if (client.player != null)
                            client.player.sendMessage(Text.literal("[ChestSort] Sort run cancelled."), true);
                    } else {
                        sortRunManager.togglePause();
                        if (client.player != null)
                            client.player.sendMessage(Text.literal("[ChestSort] Sort run paused. K again to cancel."), true);
                    }
                } else {
                    BlockPos lookPos = ChestDetector.getOpenChestPos(client);
                    if (lookPos != null && registry.getEntry(lookPos) != null) {
                        sortRunManager.startSingle(client, lookPos);
                    } else {
                        sortRunManager.start(client);
                    }
                }
            }

            // L key: toggle organize run (resume if paused, or start new)
            if (organizeKeybind.wasPressed()) {
                if (organizeRunManager.isRunning() && organizeRunManager.isPaused()) {
                    organizeRunManager.togglePause();
                    if (client.player != null)
                        client.player.sendMessage(Text.literal("[ChestSort] Organize run resumed."), true);
                } else if (organizeRunManager.isRunning()) {
                    // Not paused, pressing L does nothing (use K to pause)
                } else if (isAutoOperating()) {
                    if (client.player != null)
                        client.player.sendMessage(Text.literal("[ChestSort] Another run is active! Pause it first (K)."), true);
                } else {
                    organizeRunManager.start(client);
                }
            }

            // V key: toggle deposit run (resume if paused, or start new)
            if (depositKeybind.wasPressed()) {
                if (depositRunManager.isRunning() && depositRunManager.isPaused()) {
                    depositRunManager.togglePause();
                    if (client.player != null)
                        client.player.sendMessage(Text.literal("[ChestSort] Deposit run resumed."), true);
                } else if (depositRunManager.isRunning()) {
                    // Not paused, pressing V does nothing (use K to pause)
                } else if (isAutoOperating()) {
                    if (client.player != null)
                        client.player.sendMessage(Text.literal("[ChestSort] Another run is active! Pause it first (K)."), true);
                } else {
                    depositRunManager.start(client);
                }
            }

            // U key: deselect chest you're looking at
            if (deselectKeybind.wasPressed()) {
                handleDeselect(client);
            }

            // M key: cycle through categories (or open full menu with shift)
            if (menuKeybind.wasPressed()) {
                if (hasShiftDown()) {
                    // Shift+M opens the full GUI screen
                    client.setScreen(new ChestCategoryScreen(null));
                } else {
                    // Cycle: Off -> Building Blocks -> Ores -> ... -> Misc -> Off
                    ItemCategoryFilter.Category[] cats = ItemCategoryFilter.Category.values();
                    selectedCategoryIndex++;
                    if (selectedCategoryIndex >= cats.length) {
                        selectedCategoryIndex = -1;
                        assignModeActive = false;
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.literal("[ChestSort] Assign mode OFF"), true);
                        }
                    } else {
                        assignModeActive = true;
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.literal("[ChestSort] Category: " + cats[selectedCategoryIndex].displayName
                                            + "  (N = assign to chest you're looking at)"), true);
                        }
                    }
                }
            }

            // N key: assign selected category to the chest you're looking at
            if (assignKeybind.wasPressed()) {
                handleAssignToLookedChest(client);
            }

            // J key: toggle highlight particles
            if (highlightKeybind.wasPressed()) {
                highlightEnabled = !highlightEnabled;
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            "[ChestSort] Chest highlights " + (highlightEnabled ? "ON" : "OFF")), true);
                }
            }

            // Spawn highlight particles
            if (highlightEnabled) {
                tickHighlights(client);
            }

            // Search highlight decay
            if (searchHighlightTicks > 0) {
                searchHighlightTicks--;
                tickSearchHighlights(client);
                if (searchHighlightTicks <= 0) {
                    searchHighlights.clear();
                }
            }

            sortRunManager.tick(client);
            organizeRunManager.tick(client);
            depositRunManager.tick(client);
        });

        HudOverlay.register();
        ChestSortCommands.register();

        LOGGER.info("Chest Auto Sorter initialized!");
    }

    private void handleDeselect(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        BlockPos pos = ChestDetector.getOpenChestPos(client);
        if (pos == null) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Look at a chest to deselect it."), true);
            return;
        }

        boolean removed = registry.removeChest(pos.getX(), pos.getY(), pos.getZ());
        if (removed) {
            registry.save();
            ChestScanner.clearCacheFor(pos);
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Removed chest at " + pos.toShortString()), true);
        } else {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Chest at " + pos.toShortString() + " is not registered."), true);
        }
    }

    private void tickHighlights(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        highlightTick++;
        if (highlightTick % 10 != 0) return;

        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        List<ChestRegistry.ChestEntry> chests = registry.getChests();
        ModConfig config = ModConfig.get();

        for (ChestRegistry.ChestEntry entry : chests) {
            BlockPos pos = entry.toBlockPos();
            double dist = playerPos.distanceTo(Vec3d.ofCenter(pos));
            if (dist > 48) continue;

            if (entry.locked) {
                client.world.addParticleClient(
                        ParticleTypes.FLAME,
                        pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                        0.0, 0.02, 0.0
                );
            } else if (config.highlightFillColors) {
                // Fill-based colors from scan cache
                ChestScanner.ChestContents contents = ChestScanner.getCached(pos);
                if (contents != null) {
                    double fillPercent = (double) contents.usedSlots() / contents.slotCount;
                    if (fillPercent > 0.8) {
                        // Red - nearly full
                        client.world.addParticleClient(
                                ParticleTypes.FLAME,
                                pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                                0.0, 0.02, 0.0
                        );
                    } else if (fillPercent > 0.5) {
                        // Yellow/orange - half full
                        client.world.addParticleClient(
                                ParticleTypes.WAX_ON,
                                pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                                0.0, 0.02, 0.0
                        );
                    } else {
                        // Green - plenty of space
                        client.world.addParticleClient(
                                ParticleTypes.HAPPY_VILLAGER,
                                pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                                0.0, 0.02, 0.0
                        );
                    }
                } else {
                    // Not scanned yet - white
                    client.world.addParticleClient(
                            ParticleTypes.END_ROD,
                            pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                            0.0, 0.02, 0.0
                    );
                }
            } else {
                client.world.addParticleClient(
                        ParticleTypes.HAPPY_VILLAGER,
                        pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                        0.0, 0.02, 0.0
                );
            }
        }
    }

    private void tickSearchHighlights(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        highlightTick++;
        if (highlightTick % 5 != 0) return; // Faster pulse for search results

        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        for (BlockPos pos : searchHighlights) {
            double dist = playerPos.distanceTo(Vec3d.ofCenter(pos));
            if (dist > 64) continue;

            // Bright blue/purple particles for search results
            client.world.addParticleClient(
                    ParticleTypes.WITCH,
                    pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5,
                    0.0, 0.05, 0.0
            );
        }
    }

    private void handleAssignToLookedChest(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (!assignModeActive || selectedCategoryIndex < 0) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Press M first to pick a category."), true);
            return;
        }

        BlockPos pos = ChestDetector.getOpenChestPos(client);
        if (pos == null) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Look at a chest to assign it."), true);
            return;
        }

        // Auto-register if not already
        if (registry.getEntry(pos) == null) {
            registry.addChest(pos);
        }

        ChestRegistry.ChestEntry entry = registry.getEntry(pos);
        ItemCategoryFilter.Category cat = ItemCategoryFilter.Category.values()[selectedCategoryIndex];
        entry.categoryOverride = cat.displayName;
        registry.save();

        // Also save to the assignments file for deposit
        Map<BlockPos, String> assignments = new java.util.LinkedHashMap<>(registry.loadAssignments());
        assignments.put(pos, cat.displayName);
        registry.saveAssignments(assignments);

        client.player.sendMessage(Text.literal(
                "[ChestSort] " + entry.getDisplayName() + " -> " + cat.displayName), true);
    }

    /** Check if shift is held. */
    private static boolean hasShiftDown() {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    /** Gets the currently selected category name for HUD display, or null if assign mode is off. */
    public static String getSelectedCategoryName() {
        if (!assignModeActive || selectedCategoryIndex < 0) return null;
        return ItemCategoryFilter.Category.values()[selectedCategoryIndex].displayName;
    }

    /** Returns true if any run is actively operating. */
    public static boolean isAutoOperating() {
        return (sortRunManager != null && sortRunManager.isRunning())
                || (organizeRunManager != null && organizeRunManager.isRunning())
                || (depositRunManager != null && depositRunManager.isRunning());
    }

    /** Cancels all running operations. */
    public static void cancelAllRuns() {
        if (sortRunManager != null) sortRunManager.cancel();
        if (organizeRunManager != null) organizeRunManager.cancel();
        if (depositRunManager != null) depositRunManager.cancel();
    }

    public static String getWorldIdentifier(MinecraftClient client) {
        if (client.isIntegratedServerRunning() && client.getServer() != null) {
            return "local_" + client.getServer().getSaveProperties().getLevelName()
                    .replaceAll("[^a-zA-Z0-9_-]", "_");
        }
        if (client.getCurrentServerEntry() != null) {
            return "server_" + client.getCurrentServerEntry().address
                    .replaceAll("[^a-zA-Z0-9_.-]", "_");
        }
        return "unknown";
    }
}
