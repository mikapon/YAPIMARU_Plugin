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
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class LinkListener implements Listener {

    private final YAPIMARU_Plugin plugin;
    private final LinkManager linkManager;
    private final BukkitAudiences adventure;

    public LinkListener(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.linkManager = plugin.getLinkManager();
        this.adventure = plugin.getAdventure();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        if (clickedBlock == null || !(clickedBlock.getState() instanceof Chest)) {
            return;
        }
        Location loc = clickedBlock.getLocation();

        // リンク編集モードの処理
        if (linkManager.isInLinkEditMode(player)) {
            event.setCancelled(true);
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                linkManager.toggleBreakable(player, loc);
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                linkManager.toggleReadOnly(player, loc);
            }
            return;
        }

        // リンク追加/削除処理
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (linkManager.handleLinkProcess(player, loc)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        Location loc = null;

        if (holder instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) holder;
            loc = ((Chest) dc.getLeftSide()).getLocation();
        } else if (holder instanceof Chest) {
            loc = ((Chest) holder).getLocation();
        }

        if (loc != null) {
            final Location finalLoc = loc; // for use in lambda
            LinkedGroup group = linkManager.getGroupFromChestLocation(finalLoc);
            if (group != null) {
                event.setCancelled(true);
                // Schedule the virtual inventory opening for the next tick
                // This allows the client to properly close the physical chest GUI first
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        linkManager.openLinkedChest(player, finalLoc, group);
                    }
                }.runTask(plugin);
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

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (linkManager.isVirtualInventory(topInventory)) {
            // 遅延させて更新をかけることで、クライアント側の描画と同期させる
            new BukkitRunnable() {
                @Override
                public void run() {
                    linkManager.updateAllVirtualInventories(topInventory);
                }
            }.runTask(plugin);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (linkManager.isVirtualInventory(topInventory)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    linkManager.updateAllVirtualInventories(topInventory);
                }
            }.runTask(plugin);
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getState() instanceof Chest) {
            LinkedGroup group = linkManager.getGroupFromChestLocation(block.getLocation());
            if (group != null) {
                Player player = event.getPlayer();
                if (!group.isBreakable(block.getLocation()) && !player.isOp() && !linkManager.isModerator(player.getUniqueId(), group.getName())) {
                    adventure.player(player).sendMessage(Component.text("このチェストは破壊できません。", NamedTextColor.RED));
                    event.setCancelled(true);
                    return;
                }
                linkManager.handleChestBreak(player, block.getLocation(), event.isCancelled());
            }
        }
    }

    @EventHandler
    public void onHopperMove(InventoryMoveItemEvent event) {
        linkManager.handleHopperMove(event);
    }
}