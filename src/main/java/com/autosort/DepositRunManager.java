package com.autosort;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Deposit-all: dumps player inventory into categorized chests.
 * Uses saved category assignments to know where items go.
 */
public class DepositRunManager {

    private enum Phase {
        IDLE, WALKING, INTERACTING, WAITING_FOR_SCREEN, DEPOSITING, CLOSING, NEXT_CHEST, DONE
    }

    private Phase phase = Phase.IDLE;
    private boolean paused = false;
    private List<BlockPos> targetChests;
    private Map<BlockPos, ItemCategoryFilter.Category> assignments;
    private int currentIndex;
    private int tickCooldown;
    private int waitTicks;
    private int clickSlotIndex;
    private int clickCooldownTicks;
    private int itemsDeposited;

    // Stuck detection
    private Vec3d lastWalkPos;
    private int stuckTicks;

    private static final double INTERACT_RANGE = 4.5;
    private static final double ARRIVAL_THRESHOLD = 2.8;

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public boolean isPaused() {
        return paused;
    }

    public void togglePause() {
        paused = !paused;
    }

    public String getStatusText() {
        if (phase == Phase.IDLE) return null;
        int total = targetChests != null ? targetChests.size() : 0;
        String phaseName = switch (phase) {
            case WALKING -> "Walking";
            case INTERACTING, WAITING_FOR_SCREEN -> "Opening";
            case DEPOSITING -> "Depositing";
            case CLOSING -> "Closing";
            case NEXT_CHEST -> "Next...";
            case DONE -> "Done!";
            default -> "...";
        };
        String pauseTag = paused ? " [PAUSED]" : "";
        return "[Deposit] " + phaseName + " " + (currentIndex + 1) + "/" + total + pauseTag
                + "\nDeposited: " + itemsDeposited + " stacks";
    }

    public void start(MinecraftClient client) {
        if (client.player == null) return;

        // Load saved assignments
        Map<BlockPos, String> saved = ChestSortClient.registry.loadAssignments();
        if (saved.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] No category assignments saved. Run /chestsort organize first!"), true);
            return;
        }

        // Build assignment map
        assignments = new LinkedHashMap<>();
        for (var e : saved.entrySet()) {
            for (ItemCategoryFilter.Category cat : ItemCategoryFilter.Category.values()) {
                if (cat.displayName.equalsIgnoreCase(e.getValue()) || cat.name().equalsIgnoreCase(e.getValue())) {
                    assignments.put(e.getKey(), cat);
                    break;
                }
            }
        }

        // Figure out which categories player has items for
        ModConfig config = ModConfig.get();
        Set<ItemCategoryFilter.Category> playerCategories = EnumSet.noneOf(ItemCategoryFilter.Category.class);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                if (!config.isFavorite(itemId)) {
                    playerCategories.add(ItemCategoryFilter.categorize(stack));
                }
            }
        }

        // Build list of chests to visit (only those matching player's item categories)
        targetChests = new ArrayList<>();
        for (var e : assignments.entrySet()) {
            if (playerCategories.contains(e.getValue())) {
                targetChests.add(e.getKey());
            }
        }

        if (targetChests.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] No matching chests for your inventory items."), true);
            return;
        }

        this.currentIndex = 0;
        this.itemsDeposited = 0;
        this.phase = Phase.WALKING;
        this.tickCooldown = 0;
        this.lastWalkPos = null;
        this.stuckTicks = 0;

        client.player.sendMessage(Text.literal(
                "[ChestSort] Depositing items into " + targetChests.size() + " chests..."), true);
    }

    public void cancel() {
        phase = Phase.IDLE;
        paused = false;
        targetChests = null;
        assignments = null;
    }

    public void tick(MinecraftClient client) {
        if (phase == Phase.IDLE || paused) return;
        if (client.player == null) { cancel(); return; }
        if (tickCooldown > 0) { tickCooldown--; return; }

        switch (phase) {
            case WALKING -> handleWalking(client);
            case INTERACTING -> handleInteracting(client);
            case WAITING_FOR_SCREEN -> handleWaitForScreen(client);
            case DEPOSITING -> handleDepositing(client);
            case CLOSING -> handleClosing(client);
            case NEXT_CHEST -> handleNextChest(client);
            case DONE -> handleDone(client);
            default -> {}
        }
    }

    /**
     * Check if the player still has items matching the given chest's category.
     */
    private boolean playerHasItemsForChest(MinecraftClient client, BlockPos chestPos) {
        ItemCategoryFilter.Category chestCat = assignments.get(chestPos);
        if (chestCat == null) return false;
        ModConfig config = ModConfig.get();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                if (!config.isFavorite(itemId) && ItemCategoryFilter.categorize(stack) == chestCat) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleWalking(MinecraftClient client) {
        if (currentIndex >= targetChests.size()) {
            phase = Phase.DONE;
            return;
        }

        BlockPos target = targetChests.get(currentIndex);

        // Skip this chest if we no longer have items for its category
        if (!playerHasItemsForChest(client, target)) {
            phase = Phase.NEXT_CHEST;
            tickCooldown = 2;
            return;
        }
        ClientPlayerEntity player = client.player;

        // Skip unloaded/destroyed
        if (!client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) {
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
            return;
        }
        BlockState state = client.world.getBlockState(target);
        if (!ChestDetector.isChestBlock(state.getBlock())) {
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
            return;
        }

        Vec3d targetCenter = Vec3d.ofCenter(target);
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        double distance = playerPos.distanceTo(targetCenter);

        if (distance <= ARRIVAL_THRESHOLD) {
            lookAt(player, targetCenter);
            phase = Phase.INTERACTING;
            tickCooldown = 5;
            lastWalkPos = null;
            stuckTicks = 0;
            return;
        }

        if (distance > 64) {
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
            return;
        }

        // Stuck detection
        if (lastWalkPos != null && playerPos.squaredDistanceTo(lastWalkPos) < 0.01) {
            stuckTicks++;
            if (stuckTicks > 10) { player.jump(); stuckTicks = 0; }
        } else { stuckTicks = 0; }
        lastWalkPos = playerPos;

        Vec3d dir = targetCenter.subtract(playerPos);
        double hDist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (hDist < 0.1) return;
        float yaw = (float) (Math.atan2(dir.z, dir.x) * (180.0 / Math.PI)) - 90.0f;
        player.setYaw(yaw);
        player.setPitch(0);
        double speed = ModConfig.get().walkSpeed;
        player.move(MovementType.SELF, new Vec3d(dir.x / hDist * speed, 0, dir.z / hDist * speed));
    }

    private void handleInteracting(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        BlockPos target = targetChests.get(currentIndex);
        Vec3d center = Vec3d.ofCenter(target);
        double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(center);
        if (dist > INTERACT_RANGE) { phase = Phase.WALKING; return; }

        lookAt(player, center);
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, target, false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        phase = Phase.WAITING_FOR_SCREEN;
        waitTicks = 0;
    }

    private static final int SLOT_SYNC_DELAY = 5;
    private int syncWaitTicks;

    private void handleWaitForScreen(MinecraftClient client) {
        waitTicks++;
        if (client.currentScreen instanceof GenericContainerScreen) {
            // Wait for server to sync all slot data before reading items
            syncWaitTicks++;
            if (syncWaitTicks >= SLOT_SYNC_DELAY) {
                phase = Phase.DEPOSITING;
                clickSlotIndex = 0;
                clickCooldownTicks = 0;
            }
            return;
        }
        syncWaitTicks = 0;
        if (waitTicks > 60) {
            phase = Phase.NEXT_CHEST;
            tickCooldown = 10;
        }
    }

    private void handleDepositing(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            phase = Phase.NEXT_CHEST;
            return;
        }

        if (clickCooldownTicks > 0) { clickCooldownTicks--; return; }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9;
        BlockPos chestPos = targetChests.get(currentIndex);
        ItemCategoryFilter.Category chestCat = assignments.get(chestPos);
        ModConfig config = ModConfig.get();

        while (clickSlotIndex < 36) {
            int slotInHandler = containerSlots + clickSlotIndex;
            ItemStack stack = handler.getSlot(slotInHandler).getStack();
            if (!stack.isEmpty()) {
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                if (!config.isFavorite(itemId) && ItemCategoryFilter.categorize(stack) == chestCat) {
                    // Check chest has room
                    boolean hasRoom = false;
                    for (int i = 0; i < containerSlots; i++) {
                        ItemStack cs = handler.getSlot(i).getStack();
                        if (cs.isEmpty()) { hasRoom = true; break; }
                        if (ItemStack.areItemsAndComponentsEqual(cs, stack)
                                && cs.getCount() < cs.getMaxCount()) { hasRoom = true; break; }
                    }
                    if (!hasRoom) break; // Chest full

                    client.interactionManager.clickSlot(
                            handler.syncId, slotInHandler, 0, SlotActionType.QUICK_MOVE, client.player);
                    itemsDeposited++;
                    clickSlotIndex++;
                    clickCooldownTicks = config.clickCooldown;
                    return;
                }
            }
            clickSlotIndex++;
        }

        phase = Phase.CLOSING;
        tickCooldown = 0;
    }

    private void handleClosing(MinecraftClient client) {
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        phase = Phase.NEXT_CHEST;
        tickCooldown = 10;
    }

    private void handleNextChest(MinecraftClient client) {
        currentIndex++;
        if (currentIndex >= targetChests.size()) {
            phase = Phase.DONE;
        } else {
            phase = Phase.WALKING;
        }
    }

    private void handleDone(MinecraftClient client) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Deposit complete! Stored " + itemsDeposited + " stacks."), true);
        }
        cancel();
    }

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        Vec3d diff = target.subtract(eyes);
        double dist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Math.atan2(diff.y, dist) * (180.0 / Math.PI));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }
}
