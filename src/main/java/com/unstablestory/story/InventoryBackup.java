package com.unstablestory.story;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** The player's own items and XP, saved before an episode and given back after it. */
public final class InventoryBackup {
    private final ItemStack[] items;
    private final int xpLevel;
    private final float xpProgress;

    private InventoryBackup(ItemStack[] items, int xpLevel, float xpProgress) {
        this.items = items;
        this.xpLevel = xpLevel;
        this.xpProgress = xpProgress;
    }

    public static InventoryBackup take(ServerPlayer player) {
        Inventory inv = player.getInventory();
        ItemStack[] items = new ItemStack[inv.getContainerSize()];
        for (int i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i).copy();
        }
        return new InventoryBackup(items, player.experienceLevel, player.experienceProgress);
    }

    public void restore(ServerPlayer player) {
        Inventory inv = player.getInventory();
        inv.clearContent();
        for (int i = 0; i < items.length && i < inv.getContainerSize(); i++) {
            inv.setItem(i, items[i].copy());
        }
        player.experienceLevel = xpLevel;
        player.experienceProgress = xpProgress;
        player.inventoryMenu.broadcastChanges();
    }
}
