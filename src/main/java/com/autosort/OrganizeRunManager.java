package com.autosort;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-chest organization: scans all chests, assigns categories,
 * shuttles items between chests via player inventory, then sorts each chest.
 * Works entirely client-side via simulated interactions.
 */
public class OrganizeRunManager {

    private enum Phase {
        IDLE,
        // Scan: walk to each chest, open, read contents, close
        SCAN_WALK, SCAN_INTERACT, SCAN_WAIT_SCREEN, SCAN_READ, SCAN_NEXT,
        // Plan (single tick)
        PLANNING,
        // Shuttle pickup: open source chest, shift-click wrong items to player inv
        PICKUP_WALK, PICKUP_INTERACT, PICKUP_WAIT_SCREEN, PICKUP_EXECUTE, PICKUP_CLOSE,
        // Shuttle deposit: walk to dest chest, open, shift-click matching items in
        DEPOSIT_WALK, DEPOSIT_INTERACT, DEPOSIT_WAIT_SCREEN, DEPOSIT_EXECUTE, DEPOSIT_CLOSE,
        DEPOSIT_NEXT, SHUTTLE_NEXT,
        // Final sort: sort each chest internally
        SORT_WALK, SORT_INTERACT, SORT_WAIT_SCREEN, SORT_EXECUTE, SORT_CLOSE, SORT_NEXT,
        DONE
    }

    private static final double INTERACT_RANGE = 4.5;
    private static final int INTERACT_DELAY = 5;
    private static final int SCREEN_WAIT_TIMEOUT = 60;
    private static final int CLOSE_DELAY = 5;
    private static final int BETWEEN_DELAY = 10;
    private static final double WALK_SPEED = 0.23;
    private static final double ARRIVAL_THRESHOLD = 2.8;
    private static final int CLICK_COOLDOWN = 5;

    private Phase phase = Phase.IDLE;
    private boolean paused = false;
    private int tickCooldown;
    private int waitTicks;

    // Chest list from registry
    private List<ChestRegistry.ChestEntry> chestList;
    private int currentIndex;

    // Scan data
    private final Map<BlockPos, ScanData> scannedData = new LinkedHashMap<>();

    // Plan data
    private final Map<BlockPos, ItemCategoryFilter.Category> chestAssignments = new LinkedHashMap<>();

    // Shuttle state
    private List<BlockPos> shuttleOrder;
    private int shuttleIndex;
    private BlockPos currentPickupChest;
    private List<BlockPos> depositDestinations;
    private int depositIndex;
    private int clickSlotIndex;
    private int clickCooldownTicks;
    private boolean pickupInterrupted;

    // Overflow: unassigned chests used as temp dump when category chests are full
    private List<BlockPos> overflowChests;
    private boolean depositingToOverflow;

    // Sort state
    private ChestSorter activeSorter;

    // Stuck detection
    private Vec3d lastWalkPos;
    private int stuckTicks;

    // Stats
    private int itemsMoved;

    static class ScanData {
        final BlockPos pos;
        final List<ItemStack> items;
        final int slotCount;

        ScanData(BlockPos pos, List<ItemStack> items, int slotCount) {
            this.pos = pos;
            this.items = items;
            this.slotCount = slotCount;
        }
    }

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
        int total = chestList != null ? chestList.size() : 0;
        String phaseName = switch (phase) {
            case SCAN_WALK, SCAN_INTERACT, SCAN_WAIT_SCREEN, SCAN_READ, SCAN_NEXT ->
                    "Scanning " + (currentIndex + 1) + "/" + total;
            case PLANNING -> "Planning...";
            case PICKUP_WALK, PICKUP_INTERACT, PICKUP_WAIT_SCREEN, PICKUP_EXECUTE, PICKUP_CLOSE ->
                    "Picking up " + (shuttleIndex + 1) + "/" + (shuttleOrder != null ? shuttleOrder.size() : 0);
            case DEPOSIT_WALK, DEPOSIT_INTERACT, DEPOSIT_WAIT_SCREEN, DEPOSIT_EXECUTE, DEPOSIT_CLOSE, DEPOSIT_NEXT ->
                    "Depositing " + (depositIndex + 1) + "/" + (depositDestinations != null ? depositDestinations.size() : 0);
            case SHUTTLE_NEXT -> "Next shuttle...";
            case SORT_WALK, SORT_INTERACT, SORT_WAIT_SCREEN, SORT_EXECUTE, SORT_CLOSE, SORT_NEXT ->
                    "Sorting " + (currentIndex + 1) + "/" + total;
            case DONE -> "Done!";
            default -> "...";
        };
        String pauseTag = paused ? " [PAUSED]" : "";
        return "[Organize] " + phaseName + pauseTag + "\nMoved: " + itemsMoved + " stacks";
    }

    public void start(MinecraftClient client) {
        if (client.player == null) return;

        List<ChestRegistry.ChestEntry> chests = ChestSortClient.registry.getChests();
        if (chests.size() < 2) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Need at least 2 registered chests to organize!"), true);
            return;
        }

        this.chestList = chests;
        this.currentIndex = 0;
        this.scannedData.clear();
        this.chestAssignments.clear();
        this.itemsMoved = 0;
        this.phase = Phase.SCAN_WALK;
        this.tickCooldown = 0;
        this.lastWalkPos = null;
        this.stuckTicks = 0;

        client.player.sendMessage(Text.literal(
                "[ChestSort] Starting organize run - scanning " + chests.size() + " chests..."), true);
        client.player.sendMessage(Text.literal(
                "[ChestSort] Tip: empty your inventory first to avoid mixing items!"), false);
    }

    public void cancel() {
        if (phase != Phase.IDLE) {
            ChestSortClient.LOGGER.info("Organize run cancelled");
        }
        phase = Phase.IDLE;
        paused = false;
        chestList = null;
        activeSorter = null;
        scannedData.clear();
        chestAssignments.clear();
        shuttleOrder = null;
        depositDestinations = null;
        overflowChests = null;
        depositingToOverflow = false;
    }

    public void tick(MinecraftClient client) {
        if (phase == Phase.IDLE || paused) return;
        if (client.player == null) { cancel(); return; }

        if (tickCooldown > 0) { tickCooldown--; return; }

        switch (phase) {
            // Scan
            case SCAN_WALK -> walkTo(client, chestList.get(currentIndex).toBlockPos(), Phase.SCAN_INTERACT);
            case SCAN_INTERACT -> interact(client, chestList.get(currentIndex).toBlockPos(), Phase.SCAN_WAIT_SCREEN);
            case SCAN_WAIT_SCREEN -> waitForScreen(client, Phase.SCAN_READ, Phase.SCAN_NEXT);
            case SCAN_READ -> scanAndClose(client);
            case SCAN_NEXT -> advanceScan(client);
            // Plan
            case PLANNING -> computePlan(client);
            // Shuttle pickup
            case PICKUP_WALK -> walkTo(client, currentPickupChest, Phase.PICKUP_INTERACT);
            case PICKUP_INTERACT -> interact(client, currentPickupChest, Phase.PICKUP_WAIT_SCREEN);
            case PICKUP_WAIT_SCREEN -> waitForScreen(client, Phase.PICKUP_EXECUTE, Phase.SHUTTLE_NEXT);
            case PICKUP_EXECUTE -> executePickup(client);
            case PICKUP_CLOSE -> closePickup(client);
            // Shuttle deposit
            case DEPOSIT_WALK -> walkTo(client, depositDestinations.get(depositIndex), Phase.DEPOSIT_INTERACT);
            case DEPOSIT_INTERACT -> interact(client, depositDestinations.get(depositIndex), Phase.DEPOSIT_WAIT_SCREEN);
            case DEPOSIT_WAIT_SCREEN -> waitForScreen(client, Phase.DEPOSIT_EXECUTE, Phase.DEPOSIT_NEXT);
            case DEPOSIT_EXECUTE -> executeDeposit(client);
            case DEPOSIT_CLOSE -> closeDeposit(client);
            case DEPOSIT_NEXT -> advanceDeposit(client);
            case SHUTTLE_NEXT -> advanceShuttle(client);
            // Final sort
            case SORT_WALK -> walkTo(client, chestList.get(currentIndex).toBlockPos(), Phase.SORT_INTERACT);
            case SORT_INTERACT -> interact(client, chestList.get(currentIndex).toBlockPos(), Phase.SORT_WAIT_SCREEN);
            case SORT_WAIT_SCREEN -> waitForScreen(client, Phase.SORT_EXECUTE, Phase.SORT_NEXT);
            case SORT_EXECUTE -> executeSort(client);
            case SORT_CLOSE -> closeSort(client);
            case SORT_NEXT -> advanceSort(client);
            case DONE -> finishRun(client);
            default -> {}
        }
    }

    // ==================== Common helpers ====================

    private void walkTo(MinecraftClient client, BlockPos target, Phase nextPhase) {
        ClientPlayerEntity player = client.player;
        Vec3d targetCenter = Vec3d.ofCenter(target);
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        double distance = playerPos.distanceTo(targetCenter);

        // Skip unloaded chunks
        if (!client.world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) {
            player.sendMessage(Text.literal(
                    "[ChestSort] Chunk not loaded at " + target.toShortString() + " - skipping"), true);
            skipCurrent();
            return;
        }

        // Skip destroyed chests
        BlockState state = client.world.getBlockState(target);
        if (!ChestDetector.isChestBlock(state.getBlock())) {
            player.sendMessage(Text.literal(
                    "[ChestSort] No chest at " + target.toShortString() + " - skipping"), true);
            skipCurrent();
            return;
        }

        if (distance <= ARRIVAL_THRESHOLD) {
            lookAt(player, targetCenter);
            phase = nextPhase;
            tickCooldown = INTERACT_DELAY;
            lastWalkPos = null;
            stuckTicks = 0;
            return;
        }

        if (distance > 64) {
            player.sendMessage(Text.literal(
                    "[ChestSort] Chest too far (" + (int) distance + " blocks) - skipping"), true);
            skipCurrent();
            return;
        }

        // Stuck detection - jump if not moving
        if (lastWalkPos != null && playerPos.squaredDistanceTo(lastWalkPos) < 0.01) {
            stuckTicks++;
            if (stuckTicks > 10) {
                player.jump();
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastWalkPos = playerPos;

        Vec3d direction = targetCenter.subtract(playerPos);
        double hDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (hDist < 0.1) return;

        float yaw = (float) (Math.atan2(direction.z, direction.x) * (180.0 / Math.PI)) - 90.0f;
        player.setYaw(yaw);
        player.setPitch(0);
        double nx = direction.x / hDist;
        double nz = direction.z / hDist;
        player.move(MovementType.SELF, new Vec3d(nx * WALK_SPEED, 0, nz * WALK_SPEED));
    }

    private void interact(MinecraftClient client, BlockPos target, Phase nextPhase) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        Vec3d center = Vec3d.ofCenter(target);
        double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(center);
        if (dist > INTERACT_RANGE) {
            phase = walkPhaseFor(phase);
            return;
        }

        lookAt(player, center);
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, target, false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        phase = nextPhase;
        waitTicks = 0;
    }

    private static final int SLOT_SYNC_DELAY = 5; // ticks to wait for server to send item data
    private int syncWaitTicks;

    private void waitForScreen(MinecraftClient client, Phase successPhase, Phase failPhase) {
        waitTicks++;
        if (client.currentScreen instanceof GenericContainerScreen) {
            // Wait for server to sync all slot data (shulker contents, enchantments, etc.)
            syncWaitTicks++;
            if (syncWaitTicks >= SLOT_SYNC_DELAY) {
                phase = successPhase;
                clickSlotIndex = 0;
                clickCooldownTicks = 0;
            }
            return;
        }
        syncWaitTicks = 0;
        if (waitTicks > SCREEN_WAIT_TIMEOUT) {
            client.player.sendMessage(Text.literal("[ChestSort] Could not open chest - skipping"), true);
            phase = failPhase;
            tickCooldown = BETWEEN_DELAY;
        }
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

    private Phase walkPhaseFor(Phase current) {
        if (current.name().startsWith("SCAN")) return Phase.SCAN_WALK;
        if (current.name().startsWith("PICKUP")) return Phase.PICKUP_WALK;
        if (current.name().startsWith("DEPOSIT")) return Phase.DEPOSIT_WALK;
        if (current.name().startsWith("SORT")) return Phase.SORT_WALK;
        return Phase.IDLE;
    }

    private void skipCurrent() {
        if (phase.name().startsWith("SCAN")) { phase = Phase.SCAN_NEXT; }
        else if (phase.name().startsWith("PICKUP") || phase.name().startsWith("SHUTTLE")) { phase = Phase.SHUTTLE_NEXT; }
        else if (phase.name().startsWith("DEPOSIT")) { phase = Phase.DEPOSIT_NEXT; }
        else if (phase.name().startsWith("SORT")) { phase = Phase.SORT_NEXT; }
        tickCooldown = 5;
    }

    // ==================== Scan phase ====================

    private void scanAndClose(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            phase = Phase.SCAN_NEXT;
            return;
        }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9;
        BlockPos pos = chestList.get(currentIndex).toBlockPos();

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < containerSlots; i++) {
            items.add(handler.getSlot(i).getStack().copy());
        }
        scannedData.put(pos, new ScanData(pos, items, containerSlots));

        client.player.sendMessage(Text.literal(
                "[ChestSort] Scanned " + (currentIndex + 1) + "/" + chestList.size()
                        + " at " + chestList.get(currentIndex).getDisplayName()
                        + " (" + containerSlots + " slots)"), true);

        client.player.closeHandledScreen();
        phase = Phase.SCAN_NEXT;
        tickCooldown = BETWEEN_DELAY;
    }

    private void advanceScan(MinecraftClient client) {
        currentIndex++;
        // Skip locked chests during scan
        while (currentIndex < chestList.size() && chestList.get(currentIndex).locked) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Skipping locked chest at " + chestList.get(currentIndex).getDisplayName()), true);
            currentIndex++;
        }
        if (currentIndex >= chestList.size()) {
            phase = Phase.PLANNING;
        } else {
            phase = Phase.SCAN_WALK;
        }
    }

    // ==================== Planning phase ====================

    private void computePlan(MinecraftClient client) {
        client.player.sendMessage(Text.literal("[ChestSort] Planning categories..."), true);

        // Check for saved assignments first
        Map<BlockPos, String> saved = ChestSortClient.registry.loadAssignments();
        if (!saved.isEmpty()) {
            boolean allValid = true;
            for (BlockPos pos : saved.keySet()) {
                if (!scannedData.containsKey(pos)) {
                    allValid = false;
                    break;
                }
            }
            if (allValid && saved.size() == scannedData.size()) {
                // Use saved assignments
                for (var e : saved.entrySet()) {
                    ItemCategoryFilter.Category cat = categoryFromName(e.getValue());
                    if (cat != null) {
                        chestAssignments.put(e.getKey(), cat);
                    }
                }
                if (chestAssignments.size() == scannedData.size()) {
                    client.player.sendMessage(Text.literal(
                            "[ChestSort] Using saved category assignments. Use /chestsort forget to reset."), false);
                    applyOverridesAndBuildShuttle(client);
                    return;
                }
                chestAssignments.clear();
            }
        }

        // Fresh planning
        // Count stacks per category globally
        Map<ItemCategoryFilter.Category, Integer> globalStacks = new EnumMap<>(ItemCategoryFilter.Category.class);
        for (ScanData data : scannedData.values()) {
            for (ItemStack stack : data.items) {
                if (!stack.isEmpty()) {
                    globalStacks.merge(ItemCategoryFilter.categorize(stack), 1, Integer::sum);
                }
            }
        }

        // Sort categories by total stacks (biggest first)
        List<ItemCategoryFilter.Category> sortedCats = globalStacks.entrySet().stream()
                .sorted(Map.Entry.<ItemCategoryFilter.Category, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // For each chest, count stacks per category
        Map<BlockPos, Map<ItemCategoryFilter.Category, Integer>> chestCatCounts = new LinkedHashMap<>();
        for (ScanData data : scannedData.values()) {
            Map<ItemCategoryFilter.Category, Integer> counts = new EnumMap<>(ItemCategoryFilter.Category.class);
            for (ItemStack stack : data.items) {
                if (!stack.isEmpty()) {
                    counts.merge(ItemCategoryFilter.categorize(stack), 1, Integer::sum);
                }
            }
            chestCatCounts.put(data.pos, counts);
        }

        // Apply manual category overrides first
        Set<BlockPos> assigned = new HashSet<>();
        for (ScanData data : scannedData.values()) {
            ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(data.pos);
            if (entry != null && entry.categoryOverride != null) {
                ItemCategoryFilter.Category cat = categoryFromName(entry.categoryOverride);
                if (cat != null) {
                    chestAssignments.put(data.pos, cat);
                    assigned.add(data.pos);
                }
            }
        }

        // Assign remaining chests: for each category (biggest first), pick the chest
        // that already has the most items of that category
        for (ItemCategoryFilter.Category cat : sortedCats) {
            BlockPos bestChest = null;
            int bestCount = 0;

            for (ScanData data : scannedData.values()) {
                if (assigned.contains(data.pos)) continue;
                int count = chestCatCounts.getOrDefault(data.pos, Collections.emptyMap())
                        .getOrDefault(cat, 0);
                if (count > bestCount) {
                    bestCount = count;
                    bestChest = data.pos;
                }
            }

            if (bestChest != null) {
                chestAssignments.put(bestChest, cat);
                assigned.add(bestChest);
            }
        }

        // Assign remaining chests to categories that need overflow space
        for (ScanData data : scannedData.values()) {
            if (!assigned.contains(data.pos)) {
                ItemCategoryFilter.Category best = ItemCategoryFilter.Category.MISCELLANEOUS;
                int worstDeficit = 0;

                for (ItemCategoryFilter.Category cat : sortedCats) {
                    int totalStacks = globalStacks.getOrDefault(cat, 0);
                    int assignedCapacity = 0;
                    for (var e : chestAssignments.entrySet()) {
                        if (e.getValue() == cat) {
                            ScanData sd = scannedData.get(e.getKey());
                            if (sd != null) assignedCapacity += sd.slotCount;
                        }
                    }
                    int deficit = totalStacks - assignedCapacity;
                    if (deficit > worstDeficit) {
                        worstDeficit = deficit;
                        best = cat;
                    }
                }

                chestAssignments.put(data.pos, best);
                assigned.add(data.pos);
            }
        }

        // Save assignments for future runs
        Map<BlockPos, String> toSave = new LinkedHashMap<>();
        for (var e : chestAssignments.entrySet()) {
            toSave.put(e.getKey(), e.getValue().displayName);
        }
        ChestSortClient.registry.saveAssignments(toSave);

        applyOverridesAndBuildShuttle(client);
    }

    private void applyOverridesAndBuildShuttle(MinecraftClient client) {
        // Report assignments
        client.player.sendMessage(Text.literal("[ChestSort] Category assignments:"), false);
        for (var entry : chestAssignments.entrySet()) {
            ChestRegistry.ChestEntry regEntry = ChestSortClient.registry.getEntry(entry.getKey());
            String name = regEntry != null ? regEntry.getDisplayName() : entry.getKey().toShortString();
            ScanData sd = scannedData.get(entry.getKey());
            int slots = sd != null ? sd.slotCount : 27;
            client.player.sendMessage(Text.literal(
                    "  " + name + " -> " + entry.getValue().displayName + " (" + slots + " slots)"), false);
        }

        // Build shuttle order: chests that have items in the wrong category
        shuttleOrder = new ArrayList<>();
        for (ScanData data : scannedData.values()) {
            ItemCategoryFilter.Category assignedCat = chestAssignments.get(data.pos);
            if (assignedCat == null) continue;
            for (ItemStack stack : data.items) {
                if (!stack.isEmpty() && ItemCategoryFilter.categorize(stack) != assignedCat) {
                    shuttleOrder.add(data.pos);
                    break;
                }
            }
        }

        if (shuttleOrder.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Items already organized! Running final sort..."), true);
            currentIndex = 0;
            phase = Phase.SORT_WALK;
        } else {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Shuttling items from " + shuttleOrder.size() + " chests..."), true);
            shuttleIndex = 0;
            currentPickupChest = shuttleOrder.get(0);
            phase = Phase.PICKUP_WALK;
        }
    }

    private static ItemCategoryFilter.Category categoryFromName(String name) {
        for (ItemCategoryFilter.Category cat : ItemCategoryFilter.Category.values()) {
            if (cat.displayName.equalsIgnoreCase(name) || cat.name().equalsIgnoreCase(name)) {
                return cat;
            }
        }
        return null;
    }

    // ==================== Shuttle pickup ====================

    private void executePickup(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            phase = Phase.SHUTTLE_NEXT;
            return;
        }

        if (clickCooldownTicks > 0) {
            clickCooldownTicks--;
            return;
        }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9;
        ItemCategoryFilter.Category assignedCat = chestAssignments.get(currentPickupChest);

        pickupInterrupted = false;
        while (clickSlotIndex < containerSlots) {
            ItemStack stack = handler.getSlot(clickSlotIndex).getStack();
            if (!stack.isEmpty() && ItemCategoryFilter.categorize(stack) != assignedCat) {
                if (!playerInvHasRoom(handler, containerSlots, stack)) {
                    pickupInterrupted = true;
                    client.player.sendMessage(Text.literal(
                            "[ChestSort] Inventory full - depositing before continuing..."), true);
                    break;
                }
                client.interactionManager.clickSlot(
                        handler.syncId, clickSlotIndex, 0, SlotActionType.QUICK_MOVE, client.player);
                itemsMoved++;
                clickSlotIndex++;
                clickCooldownTicks = CLICK_COOLDOWN;
                return;
            }
            clickSlotIndex++;
        }

        buildDepositDestinations(handler, containerSlots);
        phase = Phase.PICKUP_CLOSE;
        tickCooldown = 0;
    }

    private boolean playerInvHasRoom(GenericContainerScreenHandler handler, int containerSlots, ItemStack forStack) {
        for (int i = containerSlots; i < containerSlots + 36; i++) {
            ItemStack slot = handler.getSlot(i).getStack();
            if (slot.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(slot, forStack)
                    && slot.getCount() < slot.getMaxCount()) return true;
        }
        return false;
    }

    private void buildDepositDestinations(GenericContainerScreenHandler handler, int containerSlots) {
        depositDestinations = new ArrayList<>();
        depositingToOverflow = false;
        ItemCategoryFilter.Category sourceCat = chestAssignments.get(currentPickupChest);

        Set<ItemCategoryFilter.Category> needsDeposit = EnumSet.noneOf(ItemCategoryFilter.Category.class);
        for (int i = containerSlots; i < containerSlots + 36; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty()) {
                ItemCategoryFilter.Category cat = ItemCategoryFilter.categorize(stack);
                if (cat != sourceCat) {
                    needsDeposit.add(cat);
                }
            }
        }

        // Add assigned category chests first
        for (ItemCategoryFilter.Category cat : needsDeposit) {
            for (var entry : chestAssignments.entrySet()) {
                if (entry.getValue() == cat && !entry.getKey().equals(currentPickupChest)) {
                    depositDestinations.add(entry.getKey());
                }
            }
        }

        // Build overflow list: registered chests that have no category assignment
        overflowChests = new ArrayList<>();
        Set<BlockPos> assignedPositions = new HashSet<>(chestAssignments.keySet());
        for (ChestRegistry.ChestEntry entry : chestList) {
            if (!entry.locked && !assignedPositions.contains(entry.toBlockPos())
                    && !entry.toBlockPos().equals(currentPickupChest)) {
                overflowChests.add(entry.toBlockPos());
            }
        }

        depositIndex = 0;
    }

    private void closePickup(MinecraftClient client) {
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        if (depositDestinations != null && !depositDestinations.isEmpty()) {
            phase = Phase.DEPOSIT_WALK;
            tickCooldown = BETWEEN_DELAY;
        } else {
            phase = Phase.SHUTTLE_NEXT;
            tickCooldown = BETWEEN_DELAY;
        }
    }

    // ==================== Shuttle deposit ====================

    private void executeDeposit(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            phase = Phase.DEPOSIT_NEXT;
            return;
        }

        if (clickCooldownTicks > 0) {
            clickCooldownTicks--;
            return;
        }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9;
        BlockPos destPos = depositDestinations.get(depositIndex);
        ItemCategoryFilter.Category destCat = chestAssignments.get(destPos);

        while (clickSlotIndex < 36) {
            int slotInHandler = containerSlots + clickSlotIndex;
            ItemStack stack = handler.getSlot(slotInHandler).getStack();

            // In overflow mode, deposit ANY item. In normal mode, only matching category.
            boolean shouldDeposit;
            if (depositingToOverflow) {
                shouldDeposit = !stack.isEmpty();
            } else {
                shouldDeposit = !stack.isEmpty() && ItemCategoryFilter.categorize(stack) == destCat;
            }

            if (shouldDeposit) {
                boolean chestHasRoom = false;
                for (int i = 0; i < containerSlots; i++) {
                    ItemStack cs = handler.getSlot(i).getStack();
                    if (cs.isEmpty()) { chestHasRoom = true; break; }
                    if (ItemStack.areItemsAndComponentsEqual(cs, stack)
                            && cs.getCount() < cs.getMaxCount()) { chestHasRoom = true; break; }
                }
                if (!chestHasRoom) {
                    client.player.sendMessage(Text.literal(
                            "[ChestSort] Chest full at " + destPos.toShortString() + " - trying next..."), true);
                    phase = Phase.DEPOSIT_CLOSE;
                    tickCooldown = 0;
                    return;
                }

                client.interactionManager.clickSlot(
                        handler.syncId, slotInHandler, 0, SlotActionType.QUICK_MOVE, client.player);
                clickSlotIndex++;
                clickCooldownTicks = CLICK_COOLDOWN;
                return;
            }
            clickSlotIndex++;
        }

        phase = Phase.DEPOSIT_CLOSE;
        tickCooldown = 0;
    }

    private void closeDeposit(MinecraftClient client) {
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        phase = Phase.DEPOSIT_NEXT;
        tickCooldown = BETWEEN_DELAY;
    }

    private void advanceDeposit(MinecraftClient client) {
        depositIndex++;
        if (depositIndex >= depositDestinations.size()) {
            // Check if player still has items that need depositing
            boolean hasLeftover = false;
            if (client.player != null) {
                for (int i = 0; i < 36; i++) {
                    if (!client.player.getInventory().getStack(i).isEmpty()) {
                        hasLeftover = true;
                        break;
                    }
                }
            }

            // If we still have items and haven't tried overflow yet, use overflow chests
            if (hasLeftover && !depositingToOverflow && overflowChests != null && !overflowChests.isEmpty()) {
                depositingToOverflow = true;
                depositDestinations = new ArrayList<>(overflowChests);
                depositIndex = 0;
                clickSlotIndex = 0;
                clickCooldownTicks = 0;
                client.player.sendMessage(Text.literal(
                        "[ChestSort] Category chests full - dumping to overflow chests..."), true);
                phase = Phase.DEPOSIT_WALK;
                return;
            }

            depositingToOverflow = false;

            if (pickupInterrupted) {
                pickupInterrupted = false;
                clickSlotIndex = 0;
                clickCooldownTicks = 0;
                client.player.sendMessage(Text.literal(
                        "[ChestSort] Returning to continue pickup..."), true);
                phase = Phase.PICKUP_WALK;
            } else {
                phase = Phase.SHUTTLE_NEXT;
            }
            tickCooldown = BETWEEN_DELAY;
        } else {
            clickSlotIndex = 0;
            clickCooldownTicks = 0;
            phase = Phase.DEPOSIT_WALK;
        }
    }

    private void advanceShuttle(MinecraftClient client) {
        shuttleIndex++;
        if (shuttleIndex >= shuttleOrder.size()) {
            int leftover = countPlayerInventoryItems(client);
            if (leftover > 0) {
                client.player.sendMessage(Text.literal(
                        "[ChestSort] Warning: " + leftover
                                + " item stacks left in your inventory (chests were full)."), false);
            }
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Shuttle done! Moved " + itemsMoved + " stacks. Final sort..."), true);
            currentIndex = 0;
            // Skip locked chests in sort phase
            while (currentIndex < chestList.size() && chestList.get(currentIndex).locked) {
                currentIndex++;
            }
            if (currentIndex >= chestList.size()) {
                phase = Phase.DONE;
            } else {
                phase = Phase.SORT_WALK;
            }
        } else {
            currentPickupChest = shuttleOrder.get(shuttleIndex);
            clickSlotIndex = 0;
            clickCooldownTicks = 0;
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Picking up from " + currentPickupChest.toShortString()
                            + " (" + (shuttleIndex + 1) + "/" + shuttleOrder.size() + ")"), true);
            phase = Phase.PICKUP_WALK;
        }
    }

    private int countPlayerInventoryItems(MinecraftClient client) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (!client.player.getInventory().getStack(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ==================== Final sort ====================

    private void executeSort(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            phase = Phase.SORT_NEXT;
            return;
        }

        if (activeSorter == null) {
            activeSorter = new ChestSorter(screen);
        }

        boolean done = activeSorter.tickSort(client);
        if (done) {
            activeSorter = null;
            phase = Phase.SORT_CLOSE;
            tickCooldown = 0;
        }
    }

    private void closeSort(MinecraftClient client) {
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        phase = Phase.SORT_NEXT;
        tickCooldown = BETWEEN_DELAY;
    }

    private void advanceSort(MinecraftClient client) {
        currentIndex++;
        // Skip locked chests
        while (currentIndex < chestList.size() && chestList.get(currentIndex).locked) {
            currentIndex++;
        }
        if (currentIndex >= chestList.size()) {
            phase = Phase.DONE;
        } else {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Sorting " + (currentIndex + 1) + "/" + chestList.size()), true);
            phase = Phase.SORT_WALK;
        }
    }

    private void finishRun(MinecraftClient client) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Organization complete! Moved " + itemsMoved
                            + " stacks across " + chestList.size() + " chests."), true);
        }
        cancel();
    }
}
