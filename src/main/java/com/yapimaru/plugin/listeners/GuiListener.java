package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.commands.YmCommand;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.*;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuiListener implements Listener {

    private final YAPIMARU_Plugin plugin;
    private final TimerManager timerManager;
    private final PvpManager pvpManager;
    private final GuiManager creatorGuiManager;
    private final WhitelistManager whitelistManager;
    private final PlayerRestrictionManager restrictionManager;
    private final SpectatorManager spectatorManager;
    private final ParticipantManager participantManager;
    private final YmCommand ymCommand;
    private final Map<UUID, String> playerLastParticipantGui = new HashMap<>();

    public enum ActionMode { PROMOTE, DEMOTE }

    public GuiListener(YAPIMARU_Plugin plugin, YmCommand ymCommand) {
        this.plugin = plugin;
        this.timerManager = plugin.getTimerManager();
        this.pvpManager = plugin.getPvpManager();
        this.creatorGuiManager = plugin.getCreatorGuiManager();
        this.whitelistManager = plugin.getWhitelistManager();
        this.restrictionManager = plugin.getRestrictionManager();
        this.spectatorManager = plugin.getSpectatorManager();
        this.participantManager = plugin.getParticipantManager();
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
        } else if (event.getView().getTitle().startsWith(YmCommand.GUI_PREFIX)) {
            event.setCancelled(true);
            if (!clickedItem.hasItemMeta()) return;
            handleYmGuiClick(player, event.getView().getTitle(), clickedItem, event.getClick());
        }
    }

    private void handleYmGuiClick(Player player, String title, ItemStack item, ClickType clickType) {
        String baseTitle = getBaseTitle(title);

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
        } else if (baseTitle.equals(YmCommand.PARTICIPANT_MAIN_GUI_TITLE)) {
            handleParticipantMainGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.PARTICIPANT_SELECT_GUI_TITLE)) {
            handleParticipantSelectGuiClick(player, item);
        } else if (baseTitle.equals(YmCommand.ACTIVE_PARTICIPANTS_GUI_TITLE)) {
            handleParticipantListClick(player, title, item, "active", clickType);
        } else if (baseTitle.equals(YmCommand.DISCHARGED_PARTICIPANTS_GUI_TITLE)) {
            handleParticipantListClick(player, title, item, "discharged", clickType);
        } else if (title.startsWith(YmCommand.FILTER_GUI_TITLE)) {
            handleFilterGuiClick(player, item, title);
        }
    }

    private void handleMainGuiClick(Player player, ItemStack item) {
        if (item == null) return;
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

    private void handleAdminGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        switch (item.getType()) {
            case PLAYER_HEAD: ymCommand.openParticipantListSelectGui(player); break;
            case IRON_DOOR: ymCommand.openParticipantMainGui(player); break;
            case COMMAND_BLOCK: ymCommand.openPlayerSettingsGui(player); break;
            case ENDER_EYE: spectatorManager.setEnabled(!spectatorManager.isEnabled(), player); ymCommand.openAdminGui(player); break;
            case ARROW: ymCommand.openMainGui(player); break;
            case BARRIER: player.closeInventory(); break;
        }
    }

    private void handleParticipantMainGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        switch(item.getType()) {
            case IRON_DOOR: whitelistManager.setMode(WhitelistManager.Mode.OFF, player); break;
            case DIAMOND: whitelistManager.setMode(WhitelistManager.Mode.OWNER_ONLY, player); break;
            case EMERALD: whitelistManager.setMode(WhitelistManager.Mode.WHITELIST_ONLY, player); break;
            case NETHER_STAR: whitelistManager.setMode(WhitelistManager.Mode.LOCKDOWN, player); break;
            case ARROW:
                ymCommand.openAdminGui(player);
                return;
            case BARRIER:
                player.closeInventory();
                return;
            default:
                return;
        }
        ymCommand.openParticipantMainGui(player);
    }

    private void handleParticipantSelectGuiClick(Player player, ItemStack item) {
        if (item == null) return;
        ymCommand.setPlayerGuiPage(player.getUniqueId(), 0);
        ymCommand.getPlayerFilterState(player.getUniqueId()).setFilter(YmCommand.FilterCategory.ALL, "全て", c -> true);

        switch(item.getType()) {
            case EMERALD_BLOCK: refreshParticipantGui(player, "active"); break;
            case REDSTONE_BLOCK: refreshParticipantGui(player, "discharged"); break;
            case ARROW: ymCommand.openAdminGui(player); break;
        }
    }

    private void handleParticipantListClick(Player player, String viewTitle, ItemStack item, String listType, ClickType clickType) {
        if (item == null) return;
        if (item.getType() == Material.ARROW && item.getItemMeta().getDisplayName().contains("戻る")) {
            ymCommand.openParticipantListSelectGui(player);
            return;
        }
        if (item.getType() == Material.COMPASS) {
            playerLastParticipantGui.put(player.getUniqueId(), viewTitle);
            ymCommand.openFilterGui(player, getBaseTitle(viewTitle), getParticipantList(ListType.valueOf(listType.toUpperCase())));
            return;
        }
        if (item.getType() == Material.LIME_WOOL || item.getType() == Material.RED_WOOL) {
            ymCommand.togglePlayerActionMode(player.getUniqueId());
            refreshParticipantGui(player, listType);
            return;
        }
        if (item.getType() == Material.ARROW) {
            int currentPage = ymCommand.getPlayerGuiPage(player.getUniqueId());
            int newPage = item.getItemMeta().getDisplayName().contains("前") ? currentPage - 1 : currentPage + 1;
            ymCommand.setPlayerGuiPage(player.getUniqueId(), newPage);
            refreshParticipantGui(player, listType);
            return;
        }

        if (item.getType() == Material.PLAYER_HEAD) {
            String participantId = getParticipantIdFromLore(item);
            if (participantId == null) return;

            if (clickType.isRightClick()) {
                player.performCommand("stats player " + participantId);
                player.closeInventory();
                return;
            }

            boolean success = false;
            switch (listType) {
                case "active":
                    success = participantManager.moveParticipantToDischarged(participantId);
                    break;
                case "discharged":
                    success = participantManager.moveParticipantToActive(participantId);
                    break;
            }
            if (!success) {
                player.sendMessage(ChatColor.RED + "プレイヤーの移動に失敗しました。");
            }
            refreshParticipantGui(player, listType);
        }
    }

    private String getParticipantIdFromLore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return null;
        for (String line : item.getItemMeta().getLore()) {
            if (line.startsWith("§8ID: ")) {
                return ChatColor.stripColor(line.substring(4));
            }
        }
        return null;
    }

    private void handleFilterGuiClick(Player player, ItemStack item, String title) {
        if (item == null) return;
        String previousGuiTitle = playerLastParticipantGui.get(player.getUniqueId());
        if (previousGuiTitle == null) return;

        String listType = getListTypeFromTitle(previousGuiTitle);
        YmCommand.FilterState state = ymCommand.getPlayerFilterState(player.getUniqueId());

        if (item.getType() == Material.ARROW) {
            if (title.contains(" - ")) {
                ymCommand.openFilterGui(player, getBaseTitle(previousGuiTitle), getParticipantListFromTitle(previousGuiTitle));
            } else {
                refreshParticipantGui(player, listType);
            }
            return;
        }

        ymCommand.setPlayerGuiPage(player.getUniqueId(), 0);
        String subCategoryName = ChatColor.stripColor(item.getItemMeta().getDisplayName());

        if (title.equals(YmCommand.FILTER_GUI_TITLE)) {
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
                refreshParticipantGui(player, listType);
            } else {
                ymCommand.openSubFilterGui(player, getBaseTitle(previousGuiTitle), category, getParticipantListFromTitle(previousGuiTitle));
            }
        } else {
            String categoryName = title.substring(title.lastIndexOf(' ') + 1).trim();
            YmCommand.FilterCategory category = YmCommand.FilterCategory.valueOf(categoryName);
            state.setFilter(category, subCategoryName, ymCommand.getPredicateForCategory(category, subCategoryName));
            refreshParticipantGui(player, listType);
        }
    }

    private void refreshParticipantGui(Player player, String listType) {
        switch(listType) {
            case "active":
                ymCommand.openParticipantListGui(player, YmCommand.ACTIVE_PARTICIPANTS_GUI_TITLE, "active", getParticipantList(ListType.ACTIVE), "§cクリックで除隊リストへ", "§8右クリックで統計情報を表示");
                break;
            case "discharged":
                ymCommand.openParticipantListGui(player, YmCommand.DISCHARGED_PARTICIPANTS_GUI_TITLE, "discharged", getParticipantList(ListType.DISCHARGED), "§aクリックで現役リストへ", "§8右クリックで統計情報を表示");
                break;
        }
    }

    private String getListTypeFromTitle(String title) {
        String baseTitle = getBaseTitle(title);
        if (baseTitle.equals(YmCommand.ACTIVE_PARTICIPANTS_GUI_TITLE)) return "active";
        return "discharged";
    }

    private String getBaseTitle(String fullTitle) {
        Pattern pattern = Pattern.compile("^(.*?)( §[0-9a-fA-F][\\(\\(])");
        Matcher matcher = pattern.matcher(fullTitle);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return fullTitle;
    }

    private Collection<ParticipantData> getParticipantListFromTitle(String title) {
        return getParticipantList(ListType.valueOf(getListTypeFromTitle(title).toUpperCase()));
    }

    private enum ListType { ACTIVE, DISCHARGED }

    private Collection<ParticipantData> getParticipantList(ListType type) {
        return switch (type) {
            case ACTIVE -> participantManager.getActiveParticipants();
            case DISCHARGED -> participantManager.getDischargedParticipants();
        };
    }

    private void handlePlayerSettingsGuiClick(Player player, ItemStack item) {
        if (item == null) return;
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

    private void handleTimerSettingsGuiClick(Player player, ItemStack item) {
        if (item == null) return;
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
        if (item == null) return;
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
        if (item == null) return;
        if (item.getType() == Material.ARROW) {
            ymCommand.openMainGui(player);
            return;
        }
        if (item.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        String itemName = ChatColor.stripColor(item.getItemMeta().getDisplayName());

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
}