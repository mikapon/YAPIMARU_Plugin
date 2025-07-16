package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.LinkedGroup;
import com.yapimaru.plugin.managers.LinkManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class LinkListener implements Listener {

    private final LinkManager linkManager;
    private final BukkitAudiences adventure;

    public LinkListener(YAPIMARU_Plugin plugin) {
        this.linkManager = plugin.getLinkManager();
        this.adventure = plugin.getAdventure();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (clickedBlock == null || !(clickedBlock.getState() instanceof Chest)) {
            return;
        }
        Location loc = clickedBlock.getLocation();

        // リンク追加/削除処理
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (linkManager.handleLinkProcess(player, loc)) {
                event.setCancelled(true);
            }
        }

        // 読み取り専用モード切替処理 (スニーク + 素手右クリック)
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && player.isSneaking()) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (mainHand.getType() == Material.AIR) {
                if (linkManager.toggleReadOnly(player, loc)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof Chest) {
            Location loc = inv.getLocation();
            if (loc == null) return;

            LinkedGroup group = linkManager.getGroupFromChestLocation(loc);
            if (group != null) {
                event.setCancelled(true);
                linkManager.openLinkedChest(player, loc, group);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inv = event.getInventory();
        linkManager.handleVirtualInventoryClose(player, inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getState() instanceof Chest) {
            LinkedGroup group = linkManager.getGroupFromChestLocation(block.getLocation());
            if (group != null) {
                Player player = event.getPlayer();
                if (!player.isOp() && !linkManager.isModerator(player.getUniqueId(), group.getName())) {
                    adventure.player(player).sendMessage(Component.text("リンクされたチェストを破壊する権限がありません。", NamedTextColor.RED));
                    event.setCancelled(true);
                } else {
                    linkManager.handleChestBreak(player, block.getLocation());
                }
            }
        }
    }

    @EventHandler
    public void onHopperMove(InventoryMoveItemEvent event) {
        linkManager.handleHopperMove(event);
    }
}