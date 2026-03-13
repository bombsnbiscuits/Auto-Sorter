package com.autosort;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class ChestSortCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("chestsort")
                    .then(ClientCommandManager.literal("list")
                            .executes(context -> {
                                List<ChestRegistry.ChestEntry> chests =
                                        ChestSortClient.registry.getChests();
                                if (chests.isEmpty()) {
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] No chests registered."));
                                } else {
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] Registered chests ("
                                                    + chests.size() + "):"));
                                    for (int i = 0; i < chests.size(); i++) {
                                        ChestRegistry.ChestEntry entry = chests.get(i);
                                        StringBuilder flags = new StringBuilder();
                                        if (entry.locked) flags.append(" [LOCKED]");
                                        if (entry.categoryOverride != null) flags.append(" [").append(entry.categoryOverride).append("]");
                                        if (entry.group != null) flags.append(" {").append(entry.group).append("}");
                                        context.getSource().sendFeedback(
                                                Text.literal("  " + (i + 1) + ". " + entry.getDisplayName() + flags));
                                    }
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("remove")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                    .executes(context -> {
                                                        int x = IntegerArgumentType.getInteger(context, "x");
                                                        int y = IntegerArgumentType.getInteger(context, "y");
                                                        int z = IntegerArgumentType.getInteger(context, "z");
                                                        boolean removed = ChestSortClient.registry.removeChest(x, y, z);
                                                        if (removed) {
                                                            ChestSortClient.registry.save();
                                                            context.getSource().sendFeedback(
                                                                    Text.literal("[ChestSort] Removed chest at ("
                                                                            + x + ", " + y + ", " + z + ")"));
                                                        } else {
                                                            context.getSource().sendFeedback(
                                                                    Text.literal("[ChestSort] No chest found at ("
                                                                            + x + ", " + y + ", " + z + ")"));
                                                        }
                                                        return removed ? 1 : 0;
                                                    })))))
                    .then(ClientCommandManager.literal("clear")
                            .executes(context -> {
                                int count = ChestSortClient.registry.size();
                                ChestSortClient.registry.clear();
                                ChestSortClient.registry.save();
                                ChestScanner.clearCache();
                                context.getSource().sendFeedback(
                                        Text.literal("[ChestSort] Cleared " + count + " chests from registry."));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("run")
                            .executes(context -> {
                                ChestSortClient.sortRunManager.start(
                                        MinecraftClient.getInstance());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("report")
                            .executes(context -> {
                                List<String> report = ChestScanner.generateReport();
                                for (String line : report) {
                                    context.getSource().sendFeedback(Text.literal(line));
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("organize")
                            .executes(context -> {
                                if (ChestSortClient.organizeRunManager.isRunning()) {
                                    ChestSortClient.organizeRunManager.cancel();
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] Organize run cancelled."));
                                } else if (ChestSortClient.isAutoOperating()) {
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] Another run is active! Cancel it first."));
                                } else {
                                    ChestSortClient.organizeRunManager.start(
                                            MinecraftClient.getInstance());
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("deposit")
                            .executes(context -> {
                                if (ChestSortClient.depositRunManager.isRunning()) {
                                    ChestSortClient.depositRunManager.cancel();
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] Deposit run cancelled."));
                                } else if (ChestSortClient.isAutoOperating()) {
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] Another run is active! Cancel it first."));
                                } else {
                                    ChestSortClient.depositRunManager.start(
                                            MinecraftClient.getInstance());
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("categories")
                            .executes(context -> {
                                context.getSource().sendFeedback(
                                        Text.literal("[ChestSort] Item categories:"));
                                for (ItemCategoryFilter.Category cat : ItemCategoryFilter.Category.values()) {
                                    context.getSource().sendFeedback(
                                            Text.literal("  - " + cat.displayName));
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("lock")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                    .executes(context -> {
                                                        int x = IntegerArgumentType.getInteger(context, "x");
                                                        int y = IntegerArgumentType.getInteger(context, "y");
                                                        int z = IntegerArgumentType.getInteger(context, "z");
                                                        ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(x, y, z);
                                                        if (entry == null) {
                                                            context.getSource().sendFeedback(
                                                                    Text.literal("[ChestSort] No chest at ("
                                                                            + x + ", " + y + ", " + z + ")"));
                                                            return 0;
                                                        }
                                                        entry.locked = !entry.locked;
                                                        ChestSortClient.registry.save();
                                                        context.getSource().sendFeedback(
                                                                Text.literal("[ChestSort] " + entry.getDisplayName()
                                                                        + " is now " + (entry.locked ? "LOCKED" : "UNLOCKED")));
                                                        return 1;
                                                    })))))
                    .then(ClientCommandManager.literal("label")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                            .executes(context -> {
                                                                int x = IntegerArgumentType.getInteger(context, "x");
                                                                int y = IntegerArgumentType.getInteger(context, "y");
                                                                int z = IntegerArgumentType.getInteger(context, "z");
                                                                String name = StringArgumentType.getString(context, "name");
                                                                ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(x, y, z);
                                                                if (entry == null) {
                                                                    context.getSource().sendFeedback(
                                                                            Text.literal("[ChestSort] No chest at ("
                                                                                    + x + ", " + y + ", " + z + ")"));
                                                                    return 0;
                                                                }
                                                                entry.label = name;
                                                                ChestSortClient.registry.save();
                                                                context.getSource().sendFeedback(
                                                                        Text.literal("[ChestSort] Labeled: " + entry.getDisplayName()));
                                                                return 1;
                                                            }))))))
                    .then(ClientCommandManager.literal("assign")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                    .then(ClientCommandManager.argument("category", StringArgumentType.greedyString())
                                                            .executes(context -> {
                                                                int x = IntegerArgumentType.getInteger(context, "x");
                                                                int y = IntegerArgumentType.getInteger(context, "y");
                                                                int z = IntegerArgumentType.getInteger(context, "z");
                                                                String catName = StringArgumentType.getString(context, "category");
                                                                ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(x, y, z);
                                                                if (entry == null) {
                                                                    context.getSource().sendFeedback(
                                                                            Text.literal("[ChestSort] No chest at ("
                                                                                    + x + ", " + y + ", " + z + ")"));
                                                                    return 0;
                                                                }
                                                                boolean valid = false;
                                                                for (ItemCategoryFilter.Category cat : ItemCategoryFilter.Category.values()) {
                                                                    if (cat.displayName.equalsIgnoreCase(catName)
                                                                            || cat.name().equalsIgnoreCase(catName)) {
                                                                        entry.categoryOverride = cat.displayName;
                                                                        valid = true;
                                                                        break;
                                                                    }
                                                                }
                                                                if (!valid) {
                                                                    context.getSource().sendFeedback(
                                                                            Text.literal("[ChestSort] Unknown category: " + catName
                                                                                    + ". Use /chestsort categories"));
                                                                    return 0;
                                                                }
                                                                ChestSortClient.registry.save();
                                                                context.getSource().sendFeedback(
                                                                        Text.literal("[ChestSort] " + entry.getDisplayName()
                                                                                + " -> " + entry.categoryOverride));
                                                                return 1;
                                                            }))
                                                    .executes(context -> {
                                                        int x = IntegerArgumentType.getInteger(context, "x");
                                                        int y = IntegerArgumentType.getInteger(context, "y");
                                                        int z = IntegerArgumentType.getInteger(context, "z");
                                                        ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(x, y, z);
                                                        if (entry == null) {
                                                            context.getSource().sendFeedback(
                                                                    Text.literal("[ChestSort] No chest at ("
                                                                            + x + ", " + y + ", " + z + ")"));
                                                            return 0;
                                                        }
                                                        entry.categoryOverride = null;
                                                        ChestSortClient.registry.save();
                                                        context.getSource().sendFeedback(
                                                                Text.literal("[ChestSort] Cleared override for " + entry.getDisplayName()));
                                                        return 1;
                                                    })))))
                    .then(ClientCommandManager.literal("forget")
                            .executes(context -> {
                                ChestSortClient.registry.clearAssignments();
                                context.getSource().sendFeedback(
                                        Text.literal("[ChestSort] Saved assignments cleared. Next organize will re-plan."));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("find")
                            .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        String query = StringArgumentType.getString(context, "item").toLowerCase();
                                        return handleFind(context.getSource(), query);
                                    })))
                    .then(ClientCommandManager.literal("scan")
                            .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, 32))
                                    .executes(context -> {
                                        int radius = IntegerArgumentType.getInteger(context, "radius");
                                        return handleScan(context.getSource(), radius);
                                    })))
                    .then(ClientCommandManager.literal("group")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                    .then(ClientCommandManager.argument("groupname", StringArgumentType.greedyString())
                                                            .executes(context -> {
                                                                int x = IntegerArgumentType.getInteger(context, "x");
                                                                int y = IntegerArgumentType.getInteger(context, "y");
                                                                int z = IntegerArgumentType.getInteger(context, "z");
                                                                String group = StringArgumentType.getString(context, "groupname");
                                                                ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(x, y, z);
                                                                if (entry == null) {
                                                                    context.getSource().sendFeedback(
                                                                            Text.literal("[ChestSort] No chest at ("
                                                                                    + x + ", " + y + ", " + z + ")"));
                                                                    return 0;
                                                                }
                                                                entry.group = group;
                                                                ChestSortClient.registry.save();
                                                                context.getSource().sendFeedback(
                                                                        Text.literal("[ChestSort] " + entry.getDisplayName()
                                                                                + " added to group: " + group));
                                                                return 1;
                                                            }))
                                                    .executes(context -> {
                                                        int x = IntegerArgumentType.getInteger(context, "x");
                                                        int y = IntegerArgumentType.getInteger(context, "y");
                                                        int z = IntegerArgumentType.getInteger(context, "z");
                                                        ChestRegistry.ChestEntry entry = ChestSortClient.registry.getEntry(x, y, z);
                                                        if (entry == null) {
                                                            context.getSource().sendFeedback(
                                                                    Text.literal("[ChestSort] No chest at ("
                                                                            + x + ", " + y + ", " + z + ")"));
                                                            return 0;
                                                        }
                                                        entry.group = null;
                                                        ChestSortClient.registry.save();
                                                        context.getSource().sendFeedback(
                                                                Text.literal("[ChestSort] " + entry.getDisplayName()
                                                                        + " removed from group"));
                                                        return 1;
                                                    })))))
                    .then(ClientCommandManager.literal("favorite")
                            .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        String itemId = StringArgumentType.getString(context, "item").toLowerCase();
                                        // Auto-prepend minecraft: if no namespace
                                        if (!itemId.contains(":")) {
                                            itemId = "minecraft:" + itemId;
                                        }
                                        ModConfig config = ModConfig.get();
                                        boolean added = config.toggleFavorite(itemId);
                                        config.save();
                                        if (added) {
                                            context.getSource().sendFeedback(
                                                    Text.literal("[ChestSort] Added favorite: " + itemId));
                                        } else {
                                            context.getSource().sendFeedback(
                                                    Text.literal("[ChestSort] Removed favorite: " + itemId));
                                        }
                                        return 1;
                                    }))
                            .executes(context -> {
                                ModConfig config = ModConfig.get();
                                if (config.favoriteItems.isEmpty()) {
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] No favorite items set."));
                                } else {
                                    context.getSource().sendFeedback(
                                            Text.literal("[ChestSort] Favorite items (" + config.favoriteItems.size() + "):"));
                                    for (String item : config.favoriteItems) {
                                        context.getSource().sendFeedback(
                                                Text.literal("  - " + item));
                                    }
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("compact")
                            .executes(context -> {
                                context.getSource().sendFeedback(
                                        Text.literal("[ChestSort] Running organize with compaction..."));
                                ChestSortClient.organizeRunManager.start(
                                        MinecraftClient.getInstance());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("menu")
                            .executes(context -> {
                                MinecraftClient client = MinecraftClient.getInstance();
                                client.send(() -> client.setScreen(new ChestCategoryScreen(null)));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("config")
                            .executes(context -> {
                                MinecraftClient client = MinecraftClient.getInstance();
                                client.send(() -> client.setScreen(new ConfigScreen(null)));
                                return 1;
                            }))
            );
        });
    }

    private static int handleFind(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String query) {
        Map<BlockPos, ChestScanner.ChestContents> allCached = ChestScanner.getAllCached();
        if (allCached.isEmpty()) {
            source.sendFeedback(Text.literal("[ChestSort] No chests scanned. Open chests or run /chestsort organize first."));
            return 0;
        }

        Map<BlockPos, List<String>> results = new LinkedHashMap<>();
        for (var entry : allCached.entrySet()) {
            for (ItemStack stack : entry.getValue().items) {
                if (stack.isEmpty()) continue;
                String itemName = Registries.ITEM.getId(stack.getItem()).getPath();
                String displayName = stack.getName().getString().toLowerCase();
                if (itemName.contains(query) || displayName.contains(query)) {
                    results.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                            .add(stack.getName().getString() + " x" + stack.getCount());
                }
            }
        }

        if (results.isEmpty()) {
            source.sendFeedback(Text.literal("[ChestSort] No items matching \"" + query + "\" found."));
            return 0;
        }

        source.sendFeedback(Text.literal("[ChestSort] Found \"" + query + "\" in " + results.size() + " chests:"));
        ChestSortClient.searchHighlights.clear();
        for (var entry : results.entrySet()) {
            BlockPos pos = entry.getKey();
            ChestSortClient.searchHighlights.add(pos);
            ChestRegistry.ChestEntry regEntry = ChestSortClient.registry.getEntry(pos);
            String name = regEntry != null ? regEntry.getDisplayName() : pos.toShortString();
            // Show first 3 matching items
            List<String> items = entry.getValue();
            String itemList = String.join(", ", items.subList(0, Math.min(3, items.size())));
            if (items.size() > 3) itemList += " (+" + (items.size() - 3) + " more)";
            source.sendFeedback(Text.literal("  " + name + ": " + itemList));
        }
        // Highlight for 10 seconds (200 ticks)
        ChestSortClient.searchHighlightTicks = 200;
        source.sendFeedback(Text.literal("[ChestSort] Highlighted for 10 seconds."));
        return 1;
    }

    private static int handleScan(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, int radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return 0;

        BlockPos playerPos = client.player.getBlockPos();
        int found = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos checkPos = playerPos.add(dx, dy, dz);
                    BlockState state = client.world.getBlockState(checkPos);

                    if (ChestDetector.isChestBlock(state.getBlock())) {
                        // For double chests, get canonical position
                        BlockPos canonical = checkPos;
                        if (state.getBlock() instanceof ChestBlock && state.contains(Properties.CHEST_TYPE)) {
                            if (state.get(Properties.CHEST_TYPE) != ChestType.SINGLE) {
                                BlockPos other = checkPos.offset(ChestBlock.getFacing(state));
                                if (other.getX() < checkPos.getX()
                                        || (other.getX() == checkPos.getX() && other.getY() < checkPos.getY())
                                        || (other.getX() == checkPos.getX() && other.getY() == checkPos.getY() && other.getZ() < checkPos.getZ())) {
                                    canonical = other;
                                }
                            }
                        }

                        if (ChestSortClient.registry.getEntry(canonical) == null) {
                            ChestSortClient.registry.addChest(canonical);
                            found++;
                        }
                    }
                }
            }
        }

        if (found > 0) {
            ChestSortClient.registry.save();
        }
        source.sendFeedback(Text.literal(
                "[ChestSort] Scanned radius " + radius + ": found " + found + " new chests. Total: "
                        + ChestSortClient.registry.size()));
        return 1;
    }
}
