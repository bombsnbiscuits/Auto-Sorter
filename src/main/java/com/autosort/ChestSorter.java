package com.autosort;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts items within an open chest via simulated clicks.
 * Supports multiple sort templates configurable via Mod Menu.
 */
public class ChestSorter {

    private final GenericContainerScreen screen;
    private List<SortAction> actions;
    private int actionIndex;
    private int cooldown;
    private boolean initialized;

    public ChestSorter(GenericContainerScreen screen) {
        this.screen = screen;
        this.actionIndex = 0;
        this.cooldown = 0;
        this.initialized = false;
    }

    public boolean tickSort(MinecraftClient client) {
        if (!initialized) {
            actions = computeSortActions();
            initialized = true;
            if (actions.isEmpty()) {
                return true;
            }
        }

        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        if (actionIndex >= actions.size()) {
            return true;
        }

        SortAction action = actions.get(actionIndex);
        executeAction(client, action);
        actionIndex++;
        cooldown = ModConfig.get().clickCooldown;

        return actionIndex >= actions.size();
    }

    private List<SortAction> computeSortActions() {
        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9;

        List<ItemStack> currentItems = new ArrayList<>();
        for (int i = 0; i < containerSlots; i++) {
            currentItems.add(handler.getSlot(i).getStack().copy());
        }

        List<ItemStack> sorted = buildSortedInventory(currentItems, containerSlots);
        return computeSwapSequence(currentItems, sorted, containerSlots);
    }

    private List<ItemStack> buildSortedInventory(List<ItemStack> items, int totalSlots) {
        List<ItemStack> merged = mergeStacks(items);

        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack stack : merged) {
            if (!stack.isEmpty()) {
                nonEmpty.add(stack);
            }
        }

        // Sort based on configured template
        ModConfig.SortTemplate template = ModConfig.get().getSortTemplate();
        nonEmpty.sort(getComparator(template));

        List<ItemStack> result = new ArrayList<>(nonEmpty);
        while (result.size() < totalSlots) {
            result.add(ItemStack.EMPTY);
        }
        return result;
    }

    private Comparator<ItemStack> getComparator(ModConfig.SortTemplate template) {
        return switch (template) {
            case ALPHABETICAL -> Comparator.comparing(this::getAlphabeticalKey);
            case STACK_SIZE -> Comparator.<ItemStack, Integer>comparing(s -> s.getCount(), Comparator.reverseOrder())
                    .thenComparing(this::getAlphabeticalKey);
            case CATEGORY -> Comparator.<ItemStack, Integer>comparing(s -> ItemCategoryFilter.categorize(s).ordinal())
                    .thenComparing(this::getAlphabeticalKey);
            case VALUABLES_FIRST -> Comparator.<ItemStack, Integer>comparing(this::getValueRank)
                    .thenComparing(this::getAlphabeticalKey);
        };
    }

    private String getAlphabeticalKey(ItemStack stack) {
        if (stack.isEmpty()) return "zzzzz";

        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return ItemCategoryFilter.getShulkerSortKey(stack);
        }

        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private int getValueRank(ItemStack stack) {
        if (stack.isEmpty()) return 999;
        String path = Registries.ITEM.getId(stack.getItem()).getPath();

        // Most valuable first (lower number = higher priority)
        if (path.contains("netherite")) return 0;
        if (path.contains("diamond")) return 1;
        if (path.contains("emerald")) return 2;
        if (path.contains("gold") || path.contains("golden")) return 3;
        if (path.contains("iron")) return 4;
        if (path.contains("copper")) return 5;
        if (path.contains("lapis")) return 6;
        if (path.contains("redstone")) return 7;
        if (path.contains("coal")) return 8;

        // Enchanted items
        if (stack.hasGlint()) return 10;

        // Tools and weapons
        ItemCategoryFilter.Category cat = ItemCategoryFilter.categorize(stack);
        if (cat == ItemCategoryFilter.Category.WEAPONS_AND_ARMOR) return 20;
        if (cat == ItemCategoryFilter.Category.TOOLS) return 21;

        return 50;
    }

    private List<ItemStack> mergeStacks(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();

        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;

            ItemStack remaining = stack.copy();

            for (ItemStack existing : result) {
                if (remaining.isEmpty()) break;
                if (ItemStack.areItemsAndComponentsEqual(existing, remaining)
                        && existing.getCount() < existing.getMaxCount()) {
                    int transfer = Math.min(remaining.getCount(),
                            existing.getMaxCount() - existing.getCount());
                    existing.increment(transfer);
                    remaining.decrement(transfer);
                }
            }

            if (!remaining.isEmpty()) {
                result.add(remaining);
            }
        }

        return result;
    }

    private List<SortAction> computeSwapSequence(
            List<ItemStack> current, List<ItemStack> sorted, int containerSlots) {

        List<SortAction> actions = new ArrayList<>();

        ItemStack[] working = new ItemStack[containerSlots];
        for (int i = 0; i < containerSlots; i++) {
            working[i] = i < current.size() ? current.get(i).copy() : ItemStack.EMPTY;
        }

        for (int target = 0; target < containerSlots; target++) {
            ItemStack desired = target < sorted.size() ? sorted.get(target) : ItemStack.EMPTY;

            if (stacksMatch(working[target], desired)) {
                continue;
            }

            int sourceSlot = -1;
            if (!desired.isEmpty()) {
                for (int j = target + 1; j < containerSlots; j++) {
                    if (stacksMatch(working[j], desired)) {
                        sourceSlot = j;
                        break;
                    }
                }
            } else {
                for (int j = target + 1; j < containerSlots; j++) {
                    if (working[j].isEmpty()) {
                        sourceSlot = j;
                        break;
                    }
                }
            }

            if (sourceSlot == -1) continue;

            actions.add(new SortAction(sourceSlot));
            actions.add(new SortAction(target));
            actions.add(new SortAction(sourceSlot));

            ItemStack temp = working[sourceSlot];
            working[sourceSlot] = working[target];
            working[target] = temp;
        }

        return actions;
    }

    private boolean stacksMatch(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.isEmpty() || b.isEmpty()) return false;
        return ItemStack.areItemsAndComponentsEqual(a, b) && a.getCount() == b.getCount();
    }

    private void executeAction(MinecraftClient client, SortAction action) {
        if (client.interactionManager == null || client.player == null) return;

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        client.interactionManager.clickSlot(
                handler.syncId, action.slot, 0, SlotActionType.PICKUP, client.player);
    }

    private record SortAction(int slot) {}
}
