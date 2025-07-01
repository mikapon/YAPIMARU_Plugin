package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.commands.YmCommand;
import com.yapimaru.plugin.managers.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class GuiListener implements Listener {

    private final TimerManager timerManager;
    private final PvpManager pvpManager;
    private final GuiManager creatorGuiManager;
    private final PlayerRestrictionManager restrictionManager;
    private final SpectatorManager spectatorManager;
    private final YmCommand ymCommand;

    public GuiListener(YAPIMARU_Plugin plugin, YmCommand ymCommand) {
        this.timerManager = plugin.getTimerManager();
        this.pvpManager = plugin.getPvpManager();
        this.creatorGuiManager = plugin.getCreatorGuiManager();
        this.restrictionManager = plugin.getRestrictionManager();
        this.spectatorManager = plugin.getSpectatorManager();
        this.ymCommand = ymCommand;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory clickedInventory = event.getInventory();
        if (clickedInventory == null) return;

        InventoryHolder holder = clickedInventory.getHolder();
        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem == null || clickedItem.getType().isAir()) return;

        // Creator GUI (handled by InventoryHolder)
        if (holder instanceof GuiManager.MainMenuHolder) {
            event.setCancelled(true);
            creatorGuiManager.handleMainMenuClick(player, clickedItem);
        } else if (holder instanceof GuiManager.TeleportMenuHolder) {
            event.setCancelled(true);
            creatorGuiManager.handleTeleportMenuClick(player, clickedItem, clickedInventory);
        } else if (holder instanceof GuiManager.EffectMenuHolder) {
            event.setCancelled(true);
            creatorGuiManager.handleEffectMenuClick(player, clickedItem, clickedInventory);
        } else if (holder instanceof GuiManager.GamemodeMenuHolder) {
            event.setCancelled(true);
            creatorGuiManager.handleGamemodeMenuClick(player, clickedItem);
        }

        // YmCommand GUI (handled by title prefix)
        else if (event.getView().getTitle().startsWith(YmCommand.GUI_PREFIX)) {
            event.setCancelled(true);
            if (!clickedItem.hasItemMeta()) return;
            handleYmGuiClick(player, event.getView().getTitle(), clickedItem);
        }
    }

    private void handleYmGuiClick(Player player, String title, ItemStack item) {
        String baseTitle = title.split(" §8\\(")[0].split(" §7\\(")[0].trim();

        if (baseTitle.equals(YmCommand.GUI_TITLE)) {
            handleMainGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.TIMER_SETTINGS_GUI_TITLE)) {
            handleTimerSettingsGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.PVP_MODES_GUI_TITLE)) {
            handlePvpModesGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.PVP_DETAILED_SETTINGS_GUI_TITLE)) {
            handlePvpDetailedSettingsGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.ADMIN_GUI_TITLE)) {
            handleAdminGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.PLAYER_SETTINGS_GUI_TITLE)) {
            handlePlayerSettingsGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.WL_MAIN_GUI_TITLE)) {
            if(item.getType() == Material.PLAYER_HEAD) {
                player.closeInventory();
                player.sendMessage("§a参加者管理は §e/ym participant <add|remove|list> §aコマンドを使用してください。");
            }
        }
    }

    private void handleMainGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        switch (item.getType()) {
            case CLOCK -> {
                timerManager.setFeatureEnabled(!timerManager.isFeatureEnabled(), player);
                ymCommand.openMainGui(player);
            }
            case DIAMOND_SWORD -> {
                pvpManager.setFeatureEnabled(!pvpManager.isFeatureEnabled(), player);
                ymCommand.openMainGui(player);
            }
            case NETHERITE_SWORD -> ymCommand.openPvpModesGui(player);
            case ANVIL -> ymCommand.openPvpDetailedSettingsGui(player);
            case WRITABLE_BOOK -> ymCommand.openTimerSettingsGui(player);
            case BEACON -> ymCommand.openAdminGui(player);
            case BARRIER -> player.closeInventory();
        }
    }

    private void handleTimerSettingsGuiClick(Player player, ItemStack item) {
        if (item == null || item.getItemMeta() == null) return;
        if (item.getType() == Material.ARROW) {
            ymCommand.openMainGui(player);
            return;
        }
        if (item.getType() == Material.BARRIER && item.getItemMeta().getDisplayName().contains("閉じる")) {
            player.closeInventory();
            return;
        }

        switch (item.getType()) {
            case COMPASS -> timerManager.setMode(player, timerManager.getMode() == TimerManager.TimerMode.COUNTDOWN ? "countup" : "countdown");
            case OAK_SIGN -> {
                TimerManager.DisplayType current = timerManager.getDisplayType();
                TimerManager.DisplayType[] types = TimerManager.DisplayType.values();
                TimerManager.DisplayType next = types[(current.ordinal() + 1) % types.length];
                timerManager.setDisplay(player, next.name());
            }
            case BOOK -> {
                player.closeInventory();
                timerManager.listOnEndActions(player, null);
                return;
            }
            case LIME_DYE -> timerManager.setPreStartSeconds(player, String.valueOf(timerManager.getPreStartSeconds() + 1));
            case RED_DYE -> timerManager.setPreStartSeconds(player, String.valueOf(Math.max(0, timerManager.getPreStartSeconds() - 1)));
            case BARRIER -> timerManager.setTime(player, "0s");
            case IRON_NUGGET -> timerManager.addTime(player, "1s");
            case GOLD_NUGGET -> timerManager.addTime(player, "10s");
            case IRON_INGOT -> timerManager.addTime(player, "1m");
            case GOLD_INGOT -> timerManager.addTime(player, "10m");
            case DIAMOND_BLOCK -> timerManager.addTime(player, "1h");
            default -> {
                return;
            }
        }
        ymCommand.openTimerSettingsGui(player);
    }

    private void handlePvpModesGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        switch(item.getType()){
            case TOTEM_OF_UNDYING -> pvpManager.setLivesFeatureEnabled(!pvpManager.isLivesFeatureEnabled(), player);
            case SKELETON_SKULL -> pvpManager.setDedFeatureEnabled(!pvpManager.isDedFeatureEnabled(), player);
            case GOLDEN_APPLE -> pvpManager.setRespawnInvincibleEnabled(!pvpManager.isRespawnInvincibleEnabled(), player);
            case SHIELD -> pvpManager.setGracePeriodEnabled(!pvpManager.isGracePeriodEnabled(), player);
            case ARROW -> {
                ymCommand.openMainGui(player);
                return;
            }
            default -> {
                return;
            }
        }
        ymCommand.openPvpModesGui(player);
    }

    private void handlePvpDetailedSettingsGuiClick(Player player, ItemStack item) {
        if (item == null || item.getItemMeta() == null) return;
        if (item.getType() == Material.ARROW) {
            ymCommand.openMainGui(player);
            return;
        }
        if (item.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        String itemName = item.getItemMeta().getDisplayName();

        if (itemName.contains("準備時間")) {
            int current = pvpManager.getGracePeriodTime();
            if (item.getType() == Material.LIME_DYE) pvpManager.setGracePeriodTime(current + 1, player);
            else if (item.getType() == Material.RED_DYE) pvpManager.setGracePeriodTime(Math.max(0, current - 1), player);
        } else if (itemName.contains("無敵時間")) {
            int current = pvpManager.getRespawnInvincibleTime();
            if (item.getType() == Material.LIME_DYE) pvpManager.setRespawnInvincibleTime(current + 1, player);
            else if (item.getType() == Material.RED_DYE) pvpManager.setRespawnInvincibleTime(Math.max(0, current - 1), player);
        } else if (itemName.contains("待機時間")) {
            int current = pvpManager.getDedTime();
            if (item.getType() == Material.LIME_DYE) pvpManager.setDedTime(current + 1, player);
            else if (item.getType() == Material.RED_DYE) pvpManager.setDedTime(Math.max(0, current - 1), player);
        } else {
            return;
        }
        ymCommand.openPvpDetailedSettingsGui(player);
    }

    private void handleAdminGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        switch (item.getType()) {
            case PLAYER_HEAD -> player.performCommand("ym participant list participant");
            case COMMAND_BLOCK -> ymCommand.openPlayerSettingsGui(player);
            case ENDER_EYE -> {
                spectatorManager.setEnabled(!spectatorManager.isEnabled(), player);
                ymCommand.openAdminGui(player);
            }
            case ARROW -> ymCommand.openMainGui(player);
            case BARRIER -> player.closeInventory();
        }
    }

    private void handlePlayerSettingsGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        PlayerRestrictionManager.RestrictionMode current = restrictionManager.getCurrentMode();
        PlayerRestrictionManager.RestrictionMode clicked = null;

        switch(item.getType()) {
            case IRON_SWORD -> clicked = PlayerRestrictionManager.RestrictionMode.ADVENTURE_ONLY;
            case IRON_BARS -> clicked = PlayerRestrictionManager.RestrictionMode.NO_INTERACT;
            case PISTON -> clicked = PlayerRestrictionManager.RestrictionMode.NO_WASD;
            case COBWEB -> clicked = PlayerRestrictionManager.RestrictionMode.NO_MOVE;
            case ARROW -> {
                ymCommand.openAdminGui(player);
                return;
            }
            case BARRIER -> {
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }

        restrictionManager.setMode(current == clicked ? PlayerRestrictionManager.RestrictionMode.NONE : clicked);
        ymCommand.openPlayerSettingsGui(player);
    }
}