package ru.levin.modules.misc;

import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import ru.levin.events.Event;
import ru.levin.events.impl.EventUpdate;
import ru.levin.manager.ClientManager;
import ru.levin.modules.Function;
import ru.levin.modules.FunctionAnnotation;
import ru.levin.modules.Type;
import ru.levin.modules.setting.ModeSetting;
import ru.levin.util.player.InventoryUtil;
import ru.levin.util.player.TimerUtil;

import java.util.HashMap;
import java.util.Map;

@FunctionAnnotation(name = "AutoCraft", desc = "Автоматический крафт предметов", type = Type.Misc)
public class AutoCraft extends Function {

    public final ModeSetting itemMode = new ModeSetting("Item", "Золотое зачарованное яблоко",
            "Золотое зачарованное яблоко", "Золотое яблоко", "Пласт", "Пузырьки опыта",
            "Трапка", "Кристал энда", "Явная пыль", "Золотая морковь", "Снежок заморозки",
            "Золотые арбузы", "Дезоринтация"
    );

    private static final long OPEN_DELAY = 250L;
    private static final long ACTION_DELAY = 55L;

    private final TimerUtil actionTimer = new TimerUtil();
    private final TimerUtil openDelayTimer = new TimerUtil();

    private String lastMissingText = "";
    private String lastRecipeName = "";
    private int craftedCount = 0;

    public AutoCraft() {
        addSettings(itemMode);
    }

    @Override
    public void onEvent(Event event) {
        if (!(event instanceof EventUpdate)) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (!state) {
            resetBatchState();
            return;
        }

        Recipe recipe = getRecipeForSelected();
        if (recipe == null) return;

        if (!recipe.name.equals(lastRecipeName)) {
            lastRecipeName = recipe.name;
            craftedCount = 0;
            lastMissingText = "";
            actionTimer.reset();
            openDelayTimer.reset();
        }

        if (!(mc.currentScreen instanceof CraftingScreen)) {
            BlockPos table = findCraftingTable(4.5);
            if (table == null) return;

            String missing = getMissingText(recipe);
            if (missing != null) {
                if (!missing.equals(lastMissingText)) {
                    ClientManager.message("Не хватает: " + missing);
                    lastMissingText = missing;
                }
                return;
            }

            lastMissingText = "";
            if (openDelayTimer.hasTimeElapsed(OPEN_DELAY)) {
                openCraftingTable(table);
                openDelayTimer.reset();
            }
            return;
        }

        var handler = mc.player.currentScreenHandler;

        if (!handler.getSlot(0).getStack().isEmpty()) {
            mc.interactionManager.clickSlot(
                    handler.syncId,
                    0, 0, SlotActionType.QUICK_MOVE, mc.player
            );
            craftedCount++;
            actionTimer.reset();
            return;
        }

        if (clearOneWrongItem(recipe)) {
            actionTimer.reset();
            return;
        }

        if (isGridEmpty(handler)) {
            String missing = getMissingText(recipe);
            if (missing != null) {
                if (craftedCount > 0) {
                    ClientManager.message("Скрафчено: " + craftedCount + " × " + recipe.name);
                    craftedCount = 0;
                } else if (!missing.equals(lastMissingText)) {
                    ClientManager.message("Не хватает: " + missing);
                    lastMissingText = missing;
                }
                return;
            }
        }

        if (actionTimer.hasTimeElapsed(ACTION_DELAY)) {
            fillGrid(recipe);
            actionTimer.reset();
        }
    }

    private void resetBatchState() {
        craftedCount = 0;
        lastMissingText = "";
        lastRecipeName = "";
    }

    private boolean hasEnoughItems(Recipe recipe) {
        Map<Item, Integer> counts = countInventoryItems();

        for (var entry : recipe.requiredItems.entrySet()) {
            if (counts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private String getMissingText(Recipe recipe) {
        Map<Item, Integer> counts = countInventoryItems();
        StringBuilder sb = new StringBuilder();

        for (var entry : recipe.requiredItems.entrySet()) {
            int have = counts.getOrDefault(entry.getKey(), 0);
            int need = entry.getValue();
            if (have < need) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(need - have).append(" ").append(getItemName(entry.getKey()));
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private Map<Item, Integer> countInventoryItems() {
        Map<Item, Integer> counts = new HashMap<>();

        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        return counts;
    }

    private void fillGrid(Recipe recipe) {
    if (mc.player == null || mc.interactionManager == null) return;
    if (!(mc.currentScreen instanceof CraftingScreen)) return;

    var handler = mc.player.currentScreenHandler;
    int syncId = handler.syncId;

    Map<Item, Integer> neededCounts = new HashMap<>();

    // считаем сколько ещё нужно предметов
    for (int slot = 1; slot <= 9; slot++) {
        Item need = recipe.grid[slot - 1];
        if (need == Items.AIR) continue;

        ItemStack current = handler.getSlot(slot).getStack();

        if (!current.isOf(need)) {
            neededCounts.merge(need, 1, Integer::sum);
        }
    }

    // раскладываем
    for (int slot = 1; slot <= 9; slot++) {
        Item need = recipe.grid[slot - 1];
        if (need == Items.AIR) continue;

        ItemStack current = handler.getSlot(slot).getStack();

        if (!current.isEmpty()) continue;

        int invSlot = findItemInInventory(need);
        if (invSlot == -1) continue;

        ItemStack invStack = handler.getSlot(invSlot).getStack();

        int placeAmount = 1;

        // если хватает минимум на 4 крафта — кладём по 4
        if (invStack.getCount() >= neededCounts.getOrDefault(need, 0) * 4) {
            placeAmount = 4;
        }

        mc.interactionManager.clickSlot(syncId, invSlot, 0, SlotActionType.PICKUP, mc.player);

        for (int i = 0; i < placeAmount; i++) {
            mc.interactionManager.clickSlot(syncId, slot, 1, SlotActionType.PICKUP, mc.player);
        }

        mc.interactionManager.clickSlot(syncId, invSlot, 0, SlotActionType.PICKUP, mc.player);
      }

   }

    private boolean clearOneWrongItem(Recipe recipe) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!(mc.currentScreen instanceof CraftingScreen)) return false;

        var handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;

        for (int slot = 1; slot <= 9; slot++) {
            Item need = recipe.grid[slot - 1];
            ItemStack current = handler.getSlot(slot).getStack();

            if (need == Items.AIR) continue;
            if (!current.isEmpty() && !current.isOf(need)) {
                mc.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                return true;
            }
        }

        return false;
    }

    private boolean isGridEmpty(net.minecraft.screen.ScreenHandler handler) {
        for (int i = 1; i <= 9; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int findItemInInventory(Item item) {
        for (int i = 10; i <= 45; i++) {
            if (mc.player.currentScreenHandler.getSlot(i).getStack().isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private BlockPos findCraftingTable(double radius) {
        return InventoryUtil.TotemUtil.getBlock((float) radius, Blocks.CRAFTING_TABLE);
    }

    private void openCraftingTable(BlockPos pos) {
        Vec3d hitVec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private String getItemName(Item item) {
        return item.getName().getString();
    }

    private Recipe getRecipeForSelected() {
        return switch (itemMode.get()) {
            case "Золотое зачарованное яблоко" -> new Recipe("Золотое зачарованное яблоко", new Item[]{
                    Items.GOLD_BLOCK, Items.GOLD_BLOCK, Items.GOLD_BLOCK,
                    Items.GOLD_BLOCK, Items.APPLE,      Items.GOLD_BLOCK,
                    Items.GOLD_BLOCK, Items.GOLD_BLOCK, Items.GOLD_BLOCK
            });

            case "Золотое яблоко" -> new Recipe("Золотое яблоко", new Item[]{
                    Items.GOLD_INGOT, Items.GOLD_INGOT, Items.GOLD_INGOT,
                    Items.GOLD_INGOT, Items.APPLE,      Items.GOLD_INGOT,
                    Items.GOLD_INGOT, Items.GOLD_INGOT, Items.GOLD_INGOT
            });

            case "Золотая морковь" -> new Recipe("Золотая морковь", new Item[]{
                    Items.GOLD_NUGGET, Items.GOLD_NUGGET, Items.GOLD_NUGGET,
                    Items.GOLD_NUGGET, Items.CARROT,      Items.GOLD_NUGGET,
                    Items.GOLD_NUGGET, Items.GOLD_NUGGET, Items.GOLD_NUGGET
            });

            case "Золотые арбузы" -> new Recipe("Золотые арбузы", new Item[]{
                    Items.GOLD_NUGGET, Items.GOLD_NUGGET, Items.GOLD_NUGGET,
                    Items.GOLD_NUGGET, Items.MELON_SLICE, Items.GOLD_NUGGET,
                    Items.GOLD_NUGGET, Items.GOLD_NUGGET, Items.GOLD_NUGGET
            });

            case "Пласт" -> new Recipe("Пласт", new Item[]{
                    Items.OBSIDIAN, Items.OBSIDIAN, Items.OBSIDIAN,
                    Items.OBSIDIAN, Items.DIAMOND,   Items.OBSIDIAN,
                    Items.OBSIDIAN, Items.OBSIDIAN, Items.OBSIDIAN
            });

          case "Пузырьки опыта" -> new Recipe("Пузырьки опыта", new Item[]{
                   Items.AIR,         Items.GOLD_NUGGET, Items.AIR,
                   Items.GOLD_NUGGET, Items.DIAMOND,    Items.GOLD_NUGGET,
                   Items.AIR,         Items.GOLD_NUGGET, Items.AIR
           });

            case "Трапка" -> new Recipe("Трапка", new Item[]{
                    Items.OBSIDIAN, Items.OBSIDIAN,       Items.OBSIDIAN,
                    Items.OBSIDIAN, Items.NETHERITE_INGOT, Items.OBSIDIAN,
                    Items.OBSIDIAN, Items.OBSIDIAN,       Items.OBSIDIAN
            });

            case "Кристал энда" -> new Recipe("Кристал энда", new Item[]{
                    Items.GLASS,    Items.GLASS,    Items.GLASS,
                    Items.GLASS,    Items.GHAST_TEAR, Items.GLASS,
                    Items.TNT,      Items.GLASS,    Items.TNT
            });

            case "Явная пыль" -> new Recipe("Явная пыль", new Item[]{
                    Items.SUGAR,     Items.GUNPOWDER, Items.SUGAR,
                    Items.GUNPOWDER,  Items.ENDER_EYE, Items.GUNPOWDER,
                    Items.SUGAR,      Items.GUNPOWDER, Items.SUGAR
            });

            case "Дезоринтация" -> new Recipe("Дезоринтация", new Item[]{
                    Items.MAGMA_CREAM, Items.GUNPOWDER, Items.MAGMA_CREAM,
                    Items.GUNPOWDER,   Items.ENDER_EYE,  Items.GUNPOWDER,
                    Items.MAGMA_CREAM, Items.GUNPOWDER, Items.MAGMA_CREAM
            });

            default -> null;
        };
    }

    private static class Recipe {
        final String name;
        final Item[] grid;
        final Map<Item, Integer> requiredItems;

        Recipe(String name, Item[] grid) {
            this.name = name;
            this.grid = grid;
            this.requiredItems = new HashMap<>();
            for (Item item : grid) {
                if (item != Items.AIR) {
                    requiredItems.merge(item, 1, Integer::sum);
                }
            }
        }
    }
}