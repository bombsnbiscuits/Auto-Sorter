package com.autosort;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Manages the sort run: walks to each chest, opens it, sorts it via simulated clicks.
 * Works on both singleplayer and multiplayer servers.
 */
public class SortRunManager {

    private enum Phase {
        IDLE,
        WALKING,
        INTERACTING,
        WAITING_FOR_SCREEN,
        SORTING,
        CLOSING,
        NEXT_CHEST
    }

    private Phase phase = Phase.IDLE;
    private boolean paused = false;
    private List<ChestRegistry.ChestEntry> chestList;
    private int currentIndex;
    private int tickCooldown;
    private ChestSorter activeSorter;
    private int waitTicks;

    // Stuck detection
    private Vec3d lastWalkPos;
    private int stuckTicks;

    private static final double INTERACT_RANGE = 4.5;
    private static final int INTERACT_DELAY = 5;
    private static final int SCREEN_WAIT_TIMEOUT = 60;
    private static final int CLOSE_DELAY = 5;
    private static final int BETWEEN_CHEST_DELAY = 10;
    private static final double WALK_SPEED = 0.23;
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
        int total = chestList != null ? chestList.size() : 0;
        String phaseName = switch (phase) {
            case WALKING -> "Walking";
            case INTERACTING, WAITING_FOR_SCREEN -> "Opening";
            case SORTING -> "Sorting";
            case CLOSING -> "Closing";
            case NEXT_CHEST -> "Next...";
            default -> "...";
        };
        String target = "";
        if (chestList != null && currentIndex < chestList.size()) {
            target = chestList.get(currentIndex).getDisplayName();
        }
        String pauseTag = paused ? " [PAUSED]" : "";
        return "[Sort] " + phaseName + " " + (currentIndex + 1) + "/" + total + pauseTag
                + "\n" + target;
    }

    public void start(MinecraftClient client) {
        if (client.player == null) return;

        List<ChestRegistry.ChestEntry> chests = ChestSortClient.registry.getChests();
        if (chests.isEmpty()) {
            client.player.sendMessage(
                    Text.literal("[ChestSort] No chests registered! Open some chests first."), true);
            return;
        }

        this.chestList = chests;
        this.currentIndex = 0;
        this.phase = Phase.WALKING;
        this.tickCooldown = 0;
        this.activeSorter = null;
        this.waitTicks = 0;
        this.lastWalkPos = null;
        this.stuckTicks = 0;
        this.syncWaitTicks = 0;

        client.player.sendMessage(Text.literal(
                "[ChestSort] Starting sort run for " + chests.size() + " chests..."), true);
    }

    /** Start a sort run for a single chest the player is looking at. */
    public void startSingle(MinecraftClient client, BlockPos pos) {
        if (client.player == null) return;

        ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(pos);
        if (entry == null) {
            client.player.sendMessage(
                    Text.literal("[ChestSort] That chest is not registered."), true);
            return;
        }

        this.chestList = List.of(entry);
        this.currentIndex = 0;
        this.phase = Phase.WALKING;
        this.tickCooldown = 0;
        this.activeSorter = null;
        this.waitTicks = 0;
        this.lastWalkPos = null;
        this.stuckTicks = 0;

        client.player.sendMessage(Text.literal(
                "[ChestSort] Sorting chest at " + entry.getDisplayName() + "..."), true);
    }

    public void cancel() {
        if (phase != Phase.IDLE) {
            ChestSortClient.LOGGER.info("Sort run cancelled");
        }
        phase = Phase.IDLE;
        paused = false;
        chestList = null;
        activeSorter = null;
        tickCooldown = 0;
    }

    public void tick(MinecraftClient client) {
        if (phase == Phase.IDLE || paused) return;
        if (client.player == null) {
            cancel();
            return;
        }

        if (tickCooldown > 0) {
            tickCooldown--;
            return;
        }

        switch (phase) {
            case WALKING -> handleWalking(client);
            case INTERACTING -> handleInteracting(client);
            case WAITING_FOR_SCREEN -> handleWaitForScreen(client);
            case SORTING -> handleSorting(client);
            case CLOSING -> handleClosing(client);
            case NEXT_CHEST -> handleNextChest(client);
            default -> {}
        }
    }

    private void handleWalking(MinecraftClient client) {
        if (currentIndex >= chestList.size()) {
            finishRun(client);
            return;
        }

        ChestRegistry.ChestEntry entry = chestList.get(currentIndex);
        BlockPos chestPos = entry.toBlockPos();

        // Skip locked chests
        if (entry.locked) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Skipping locked chest at " + entry.getDisplayName()), true);
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
            return;
        }

        // Skip unloaded chunks
        if (!client.world.isChunkLoaded(chestPos.getX() >> 4, chestPos.getZ() >> 4)) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Chunk not loaded at " + entry.getDisplayName() + " - skipping"), true);
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
            return;
        }

        // Skip destroyed chests
        BlockState state = client.world.getBlockState(chestPos);
        if (!ChestDetector.isChestBlock(state.getBlock())) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] No chest found at " + entry.getDisplayName() + " - skipping"), true);
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
            return;
        }

        ClientPlayerEntity player = client.player;
        player.sendMessage(Text.literal(
                "[ChestSort] Walking to chest " + (currentIndex + 1) + "/"
                        + chestList.size() + " at " + entry.getDisplayName() + "..."), true);

        Vec3d chestCenter = Vec3d.ofCenter(chestPos);
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        double distance = playerPos.distanceTo(chestCenter);

        if (distance <= ARRIVAL_THRESHOLD) {
            lookAt(player, chestCenter);
            phase = Phase.INTERACTING;
            tickCooldown = INTERACT_DELAY;
            lastWalkPos = null;
            stuckTicks = 0;
            return;
        }

        if (distance > 64) {
            player.sendMessage(Text.literal(
                    "[ChestSort] Chest at " + entry.getDisplayName() + " is too far away ("
                            + (int) distance + " blocks) - skipping"), true);
            phase = Phase.NEXT_CHEST;
            tickCooldown = 5;
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

        walkToward(player, chestCenter);
    }

    private void walkToward(ClientPlayerEntity player, Vec3d target) {
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d direction = target.subtract(playerPos);
        double horizontalDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);

        if (horizontalDist < 0.1) return;

        float yaw = (float) (Math.atan2(direction.z, direction.x) * (180.0 / Math.PI)) - 90.0f;
        player.setYaw(yaw);
        player.setPitch(0);

        double nx = direction.x / horizontalDist;
        double nz = direction.z / horizontalDist;
        player.move(MovementType.SELF, new Vec3d(nx * WALK_SPEED, 0, nz * WALK_SPEED));
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

    private void handleInteracting(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        ChestRegistry.ChestEntry entry = chestList.get(currentIndex);
        BlockPos chestPos = entry.toBlockPos();

        double distance = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(Vec3d.ofCenter(chestPos));
        if (distance > INTERACT_RANGE) {
            phase = Phase.WALKING;
            return;
        }

        lookAt(player, Vec3d.ofCenter(chestPos));

        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(chestPos),
                Direction.UP,
                chestPos,
                false
        );
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);

        phase = Phase.WAITING_FOR_SCREEN;
        waitTicks = 0;
    }

    private static final int SLOT_SYNC_DELAY = 5; // ticks to wait for server to send item data
    private int syncWaitTicks;

    private void handleWaitForScreen(MinecraftClient client) {
        waitTicks++;

        if (client.currentScreen instanceof GenericContainerScreen screen) {
            // Wait a few ticks for the server to send all slot data before reading items.
            // Without this, shulker component data and items may not be synced yet.
            syncWaitTicks++;
            if (syncWaitTicks >= SLOT_SYNC_DELAY) {
                activeSorter = new ChestSorter(screen);
                phase = Phase.SORTING;
            }
            return;
        }
        syncWaitTicks = 0;

        if (waitTicks > SCREEN_WAIT_TIMEOUT) {
            ChestSortClient.LOGGER.warn("Timed out waiting for chest screen at {}",
                    chestList.get(currentIndex));
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Could not open chest at "
                            + chestList.get(currentIndex).getDisplayName() + " - skipping"), true);
            phase = Phase.NEXT_CHEST;
            tickCooldown = BETWEEN_CHEST_DELAY;
        }
    }

    private void handleSorting(MinecraftClient client) {
        if (activeSorter == null) {
            phase = Phase.CLOSING;
            tickCooldown = CLOSE_DELAY;
            return;
        }

        if (!(client.currentScreen instanceof GenericContainerScreen)) {
            ChestSortClient.LOGGER.warn("Chest screen closed during sorting");
            activeSorter = null;
            phase = Phase.NEXT_CHEST;
            tickCooldown = BETWEEN_CHEST_DELAY;
            return;
        }

        boolean done = activeSorter.tickSort(client);
        if (done) {
            activeSorter = null;
            phase = Phase.CLOSING;
            tickCooldown = CLOSE_DELAY;
        }
    }

    private void handleClosing(MinecraftClient client) {
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        phase = Phase.NEXT_CHEST;
        tickCooldown = BETWEEN_CHEST_DELAY;
    }

    private void handleNextChest(MinecraftClient client) {
        currentIndex++;
        if (currentIndex >= chestList.size()) {
            finishRun(client);
        } else {
            phase = Phase.WALKING;
        }
    }

    private void finishRun(MinecraftClient client) {
        int total = chestList != null ? chestList.size() : 0;
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "[ChestSort] Sort run complete! Sorted " + total + " chests."), true);
        }
        cancel();
    }
}
