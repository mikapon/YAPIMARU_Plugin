package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.commands.YmCommand;
import com.yapimaru.plugin.managers.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class GuiListener implements Listener {

    private final YAPIMARU_Plugin plugin;
    private final TimerManager timerManager;
    private final PvpManager pvpManager;
    private final GuiManager creatorGuiManager;
    private final WhitelistManager whitelistManager;
    private final PlayerRestrictionManager restrictionManager;
    private final SpectatorManager spectatorManager;
    private final YmCommand ymCommand;
    private final Map<UUID, String> playerLastWlGui = new java.util.HashMap<>();

    public enum ActionMode { PROMOTE, DEMOTE }

    public GuiListener(YAPIMARU_Plugin plugin, YmCommand ymCommand) {
        this.plugin = plugin;
        this.timerManager = plugin.getTimerManager();
        this.pvpManager = plugin.getPvpManager();
        this.creatorGuiManager = plugin.getCreatorGuiManager();
        this.whitelistManager = plugin.getWhitelistManager();
        this.restrictionManager = plugin.getRestrictionManager();
        this.spectatorManager = plugin.getSpectatorManager();
        this.ymCommand = ymCommand;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String viewTitle = event.getView().getTitle();
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir() || !clickedItem.hasItemMeta()) return;

        if (viewTitle.startsWith("クリエイターメニュー")) {
            event.setCancelled(true);
            creatorGuiManager.handleInventoryClick(player, viewTitle, clickedItem, event.getInventory());
            return;
        }

        if (viewTitle.startsWith(YmCommand.GUI_PREFIX)) {
            event.setCancelled(true);
            handleYmGuiClick(player, viewTitle, clickedItem, event.getClick());
        }
    }

    private void handleYmGuiClick(Player player, String title, ItemStack item, ClickType clickType) {
        if (title.equals(YmCommand.GUI_TITLE)) {
            handleMainGuiClick(player, item);
        } else if (title.equals(YmCommand.TIMER_SETTINGS_GUI_TITLE)) {
            handleTimerSettingsGuiClick(player, item);
        } else if (title.equals(YmCommand.PVP_MODES_GUI_TITLE)) {
            handlePvpModesGuiClick(player, item);
        } else if (title.equals(YmCommand.PVP_DETAILED_SETTINGS_GUI_TITLE)) {
            handlePvpDetailedSettingsGuiClick(player, item);
        } else if (title.equals(YmCommand.ADMIN_GUI_TITLE)) {
            handleAdminGuiClick(player, item);
        } else if (title.equals(YmCommand.PLAYER_SETTINGS_GUI_TITLE)) {
            handlePlayerSettingsGuiClick(player, item);
        } else if (title.equals(YmCommand.WL_MAIN_GUI_TITLE)) {
            handleWhitelistMainGuiClick(player, item);
        } else if (title.equals(YmCommand.WL_PLAYER_SELECT_GUI_TITLE)) {
            handlePlayerSelectGuiClick(player, item);
        } else if (title.startsWith(YmCommand.WL_ALLOWED_GUI_TITLE)) {
            handleWhitelistListClick(player, title, item, "allowed");
        } else if (title.startsWith(YmCommand.WL_CANDIDATE_GUI_TITLE)) {
            handleWhitelistListClick(player, title, item, "candidate");
        } else if (title.startsWith(YmCommand.WL_SOURCE_GUI_TITLE)) {
            handleWhitelistListClick(player, title, item, "source");
        } else if (title.startsWith(YmCommand.WL_FILTER_GUI_TITLE)) {
            handleFilterGuiClick(player, item, title);
        }
    }

    private void handleMainGuiClick(Player player, ItemStack item) {
        switch (item.getType()) {
            case CLOCK:
                timerManager.setFeatureEnabled(!timerManager.isFeatureEnabled(), player);
                ymCommand.openMainGui(player);
                break;
            case DIAMOND_SWORD:
                pvpManager.setFeatureEnabled(!pvpManager.isFeatureEnabled(), player);
                ymCommand.openMainGui(player);
                break;
            case NETHERITE_SWORD:
                ymCommand.openPvpModesGui(player);
                break;
            case ANVIL:
                ymCommand.openPvpDetailedSettingsGui(player);
                break;
            case WRITABLE_BOOK:
                ymCommand.openTimerSettingsGui(player);
                break;
            case BEACON:
                ymCommand.openAdminGui(player);
                break;
            case BARRIER:
                player.closeInventory();
                break;
        }
    }

    private void handleTimerSettingsGuiClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) {
            ymCommand.openMainGui(player);
            return;
        }
        if (item.getType() == Material.BARRIER && item.getItemMeta().getDisplayName().contains("閉じる")) {
            player.closeInventory();
            return;
        }

        switch (item.getType()) {
            case COMPASS: timerManager.setMode(player, timerManager.getMode() == TimerManager.TimerMode.COUNTDOWN ? "countup" : "countdown"); break;
            case OAK_SIGN:
                TimerManager.DisplayType current = timerManager.getDisplayType();
                TimerManager.DisplayType[] types = TimerManager.DisplayType.values();
                TimerManager.DisplayType next = types[(current.ordinal() + 1) % types.length];
                timerManager.setDisplay(player, next.name());
                break;
            case BOOK: player.closeInventory(); timerManager.listOnEndActions(player, null); return;
            case LIME_DYE: timerManager.setPreStartSeconds(player, String.valueOf(timerManager.getPreStartSeconds() + 1)); break;
            case RED_DYE: timerManager.setPreStartSeconds(player, String.valueOf(Math.max(0, timerManager.getPreStartSeconds() - 1))); break;
            case BARRIER: timerManager.setTime(player, "0s"); break;
            case IRON_NUGGET: timerManager.addTime(player, "1s"); break;
            case GOLD_NUGGET: timerManager.addTime(player, "10s"); break;
            case IRON_INGOT: timerManager.addTime(player, "1m"); break;
            case GOLD_INGOT: timerManager.addTime(player, "10m"); break;
            case DIAMOND_BLOCK: timerManager.addTime(player, "1h"); break;
            default: return;
        }
        ymCommand.openTimerSettingsGui(player);
    }

    private void handlePvpModesGuiClick(Player player, ItemStack item) {
        switch(item.getType()){
            case TOTEM_OF_UNDYING: pvpManager.setLivesFeatureEnabled(!pvpManager.isLivesFeatureEnabled(), player); break;
            case SKELETON_SKULL: pvpManager.setDedFeatureEnabled(!pvpManager.isDedFeatureEnabled(), player); break;
            case GOLDEN_APPLE: pvpManager.setRespawnInvincibleEnabled(!pvpManager.isRespawnInvincibleEnabled(), player); break;
            case SHIELD: pvpManager.setGracePeriodEnabled(!pvpManager.isGracePeriodEnabled(), player); break;
            case ARROW: ymCommand.openMainGui(player); return;
            default: return;
        }
        ymCommand.openPvpModesGui(player);
    }

    private void handlePvpDetailedSettingsGuiClick(Player player, ItemStack item) {
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
        switch (item.getType()) {
            case PLAYER_HEAD: ymCommand.openWhitelistMainGui(player); break;
            case COMMAND_BLOCK: ymCommand.openPlayerSettingsGui(player); break;
            case ENDER_EYE: spectatorManager.setEnabled(!spectatorManager.isEnabled(), player); ymCommand.openAdminGui(player); break;
            case ARROW: ymCommand.openMainGui(player); break;
            case BARRIER: player.closeInventory(); break;
        }
    }

    private void handlePlayerSettingsGuiClick(Player player, ItemStack item) {
        PlayerRestrictionManager.RestrictionMode current = restrictionManager.getCurrentMode();
        PlayerRestrictionManager.RestrictionMode clicked = null;

        switch(item.getType()) {
            case IRON_SWORD: clicked = PlayerRestrictionManager.RestrictionMode.ADVENTURE_ONLY; break;
            case IRON_BARS: clicked = PlayerRestrictionManager.RestrictionMode.NO_INTERACT; break;
            case PISTON: clicked = PlayerRestrictionManager.RestrictionMode.NO_WASD; break;
            case COBWEB: clicked = PlayerRestrictionManager.RestrictionMode.NO_MOVE; break;
            case ARROW:
                ymCommand.openAdminGui(player);
                return;
            case BARRIER:
                player.closeInventory();
                return;
            default:
                return;
        }

        restrictionManager.setMode(current == clicked ? PlayerRestrictionManager.RestrictionMode.NONE : clicked);
        ymCommand.openPlayerSettingsGui(player);
    }

    private void handleWhitelistMainGuiClick(Player player, ItemStack item) {
        switch(item.getType()) {
            case IRON_DOOR: whitelistManager.setMode(WhitelistManager.Mode.OFF, player); break;
            case DIAMOND: whitelistManager.setMode(WhitelistManager.Mode.OWNER_ONLY, player); break;
            case EMERALD: whitelistManager.setMode(WhitelistManager.Mode.WHITELIST_ONLY, player); break;
            case NETHER_STAR: whitelistManager.setMode(WhitelistManager.Mode.LOCKDOWN, player); break;
            case WRITABLE_BOOK:
                ymCommand.openPlayerSelectGui(player);
                return;
            case ARROW:
                ymCommand.openAdminGui(player);
                return;
            case BARRIER:
                player.closeInventory();
                return;
            default:
                return;
        }
        ymCommand.openWhitelistMainGui(player);
    }

    private void handlePlayerSelectGuiClick(Player player, ItemStack item) {
        ymCommand.setPlayerGuiPage(player.getUniqueId(), 0);
        ymCommand.getPlayerFilterState(player.getUniqueId()).setFilter(YmCommand.FilterCategory.ALL, "全て", c -> true);

        switch(item.getType()) {
            case EMERALD_BLOCK: refreshWhitelistGui(player, "allowed"); break;
            case DIAMOND_BLOCK: refreshWhitelistGui(player, "candidate"); break;
            case BOOK: refreshWhitelistGui(player, "source"); break;
            case ARROW: ymCommand.openWhitelistMainGui(player); break;
        }
    }

    private void handleWhitelistListClick(Player player, String viewTitle, ItemStack item, String listType) {
        if (item.getType() == Material.ARROW && item.getItemMeta().getDisplayName().contains("戻る")) {
            ymCommand.openPlayerSelectGui(player);
            return;
        }
        if (item.getType() == Material.COMPASS) {
            playerLastWlGui.put(player.getUniqueId(), viewTitle);
            ymCommand.openFilterGui(player, getBaseTitle(viewTitle), getPlayerList(ListType.valueOf(listType.toUpperCase())));
            return;
        }
        if (item.getType() == Material.LIME_WOOL || item.getType() == Material.RED_WOOL) {
            ymCommand.togglePlayerWlActionMode(player.getUniqueId());
            refreshWhitelistGui(player, listType);
            return;
        }
        if (item.getType() == Material.ARROW) {
            int currentPage = ymCommand.getPlayerGuiPage(player.getUniqueId());
            int newPage = item.getItemMeta().getDisplayName().contains("前") ? currentPage - 1 : currentPage + 1;
            ymCommand.setPlayerGuiPage(player.getUniqueId(), newPage);
            refreshWhitelistGui(player, listType);
            return;
        }

        if (item.getType() == Material.PLAYER_HEAD && item.getItemMeta() instanceof SkullMeta meta) {
            OfflinePlayer target = meta.getOwningPlayer();
            if (target == null) return;

            ActionMode mode = ymCommand.getPlayerWlActionMode(player.getUniqueId());

            switch (listType) {
                case "allowed":
                    whitelistManager.demoteAllowedToCandidate(target.getUniqueId());
                    break;
                case "candidate":
                    if (mode == ActionMode.PROMOTE) {
                        whitelistManager.promoteCandidateToAllowed(target.getUniqueId());
                    } else {
                        whitelistManager.demoteCandidateToSource(target.getUniqueId());
                    }
                    break;
                case "source":
                    whitelistManager.addCandidate(target);
                    break;
            }
            refreshWhitelistGui(player, listType);
        }
    }

    private void handleFilterGuiClick(Player player, ItemStack item, String title) {
        String previousGuiTitle = playerLastWlGui.get(player.getUniqueId());
        if (previousGuiTitle == null) return;

        String listType = getListTypeFromTitle(previousGuiTitle);
        YmCommand.FilterState state = ymCommand.getPlayerFilterState(player.getUniqueId());

        if (item.getType() == Material.ARROW) {
            if (title.contains(" - ")) { // Sub-filter menu
                ymCommand.openFilterGui(player, getBaseTitle(previousGuiTitle), getPlayerListFromTitle(previousGuiTitle));
            } else { // Main filter menu
                refreshWhitelistGui(player, listType);
            }
            return;
        }

        ymCommand.setPlayerGuiPage(player.getUniqueId(), 0);
        String subCategoryName = item.getItemMeta().getDisplayName().replace("§f", "");

        if (title.equals(YmCommand.WL_FILTER_GUI_TITLE)) { // Main filter menu
            YmCommand.FilterCategory category = YmCommand.FilterCategory.ALL;
            switch (item.getType()) {
                case BOOK: category = YmCommand.FilterCategory.ALL; break;
                case OAK_SIGN: category = YmCommand.FilterCategory.NUMERIC; break;
                case NAME_TAG: category = YmCommand.FilterCategory.ALPHABET; break;
                case CHERRY_SAPLING: category = YmCommand.FilterCategory.KANA; break;
                case STRUCTURE_VOID: category = YmCommand.FilterCategory.OTHER; break;
                default: return;
            }

            if (category == YmCommand.FilterCategory.ALL || category == YmCommand.FilterCategory.OTHER) {
                state.setFilter(category, subCategoryName, ymCommand.getPredicateForCategory(category, ""));
                refreshWhitelistGui(player, listType);
            } else {
                ymCommand.openSubFilterGui(player, getBaseTitle(previousGuiTitle), category, getPlayerListFromTitle(previousGuiTitle));
            }
        } else { // Sub-filter menu
            String categoryName = title.substring(title.lastIndexOf(' ') + 1).trim();
            YmCommand.FilterCategory category = YmCommand.FilterCategory.valueOf(categoryName);
            state.setFilter(category, subCategoryName, ymCommand.getPredicateForCategory(category, subCategoryName));
            refreshWhitelistGui(player, listType);
        }
    }

    private void refreshWhitelistGui(Player player, String listType) {
        switch(listType) {
            case "allowed":
                ymCommand.openWhitelistGui(player, YmCommand.WL_ALLOWED_GUI_TITLE, "allowed", getPlayerList(ListType.ALLOWED), "§cクリックで許可候補に降格");
                break;
            case "candidate":
                ymCommand.openWhitelistGui(player, YmCommand.WL_CANDIDATE_GUI_TITLE, "candidate", getPlayerList(ListType.CANDIDATE), "§7クリックでアクション実行");
                break;
            case "source":
                ymCommand.openWhitelistGui(player, YmCommand.WL_SOURCE_GUI_TITLE, "source", getPlayerList(ListType.SOURCE), "§bクリックで許可候補へ");
                break;
        }
    }

    private String getListTypeFromTitle(String title) {
        String baseTitle = getBaseTitle(title);
        if (baseTitle.equals(YmCommand.WL_ALLOWED_GUI_TITLE)) return "allowed";
        if (baseTitle.equals(YmCommand.WL_CANDIDATE_GUI_TITLE)) return "candidate";
        return "source";
    }

    private String getBaseTitle(String fullTitle) {
        if (fullTitle.contains(" §8(")) {
            return fullTitle.substring(0, fullTitle.indexOf(" §8(")).split(" §7\\(")[0].trim();
        }
        return fullTitle;
    }

    private List<OfflinePlayer> getPlayerListFromTitle(String title) {
        return getPlayerList(ListType.valueOf(getListTypeFromTitle(title).toUpperCase()));
    }

    private enum ListType { ALLOWED, CANDIDATE, SOURCE }

    private List<OfflinePlayer> getPlayerList(ListType type) {
        plugin.getWhitelistManager().load();

        return switch (type) {
            case ALLOWED -> whitelistManager.getAllowedPlayers().stream().map(Bukkit::getOfflinePlayer).collect(Collectors.toList());
            case CANDIDATE -> whitelistManager.getCandidatePlayers().stream().map(Bukkit::getOfflinePlayer).collect(Collectors.toList());
            case SOURCE -> Arrays.stream(Bukkit.getOfflinePlayers())
                    .filter(p -> p.getName() != null
                            && !whitelistManager.isAllowed(p.getUniqueId())
                            && !whitelistManager.isCandidate(p.getUniqueId()))
                    .collect(Collectors.toList());
        };
    }
}