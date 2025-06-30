package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.listeners.GuiListener;
import com.yapimaru.plugin.managers.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.Collator;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class YmCommand implements CommandExecutor {
    // GUI Titles
    public static final String GUI_PREFIX = "§b§lYAPIMARU §7- ";
    public static final String GUI_TITLE = GUI_PREFIX + "§8設定パネル";
    public static final String PVP_MODES_GUI_TITLE = GUI_PREFIX + "§cPVP各モード";
    public static final String PVP_DETAILED_SETTINGS_GUI_TITLE = GUI_PREFIX + "§cPVP詳細設定";
    public static final String ADMIN_GUI_TITLE = GUI_PREFIX + "§6管理者機能";
    public static final String PLAYER_SETTINGS_GUI_TITLE = GUI_PREFIX + "§6プレイヤー設定";
    public static final String WL_MAIN_GUI_TITLE = GUI_PREFIX + "§6WL管理";
    public static final String WL_PLAYER_SELECT_GUI_TITLE = GUI_PREFIX + "§6プレイヤー管理";
    public static final String WL_ALLOWED_GUI_TITLE = GUI_PREFIX + "§a許可済みプレイヤー";
    public static final String WL_CANDIDATE_GUI_TITLE = GUI_PREFIX + "§b許可候補プレイヤー";
    public static final String WL_SOURCE_GUI_TITLE = GUI_PREFIX + "§7ホワイトリスト(全員)";
    public static final String TIMER_SETTINGS_GUI_TITLE = GUI_PREFIX + "§aタイマー詳細設定";
    public static final String WL_FILTER_GUI_TITLE = GUI_PREFIX + "§8フィルター選択";

    private final YAPIMARU_Plugin plugin;
    private final TimerManager timerManager;
    private final PvpManager pvpManager;
    private final WhitelistManager whitelistManager;
    private final NameManager nameManager;
    private final PlayerRestrictionManager restrictionManager;

    private final Map<UUID, FilterState> playerFilterStates = new HashMap<>();
    private final Map<UUID, Integer> playerGuiPages = new HashMap<>();
    private final Map<UUID, GuiListener.ActionMode> playerWlActionModes = new HashMap<>();

    public static class FilterState {
        public FilterCategory category = FilterCategory.ALL;
        public Predicate<Character> predicate = c -> true;
        public String subCategoryName = "全て";

        public void setFilter(FilterCategory cat, String subName, Predicate<Character> pred) {
            this.category = cat;
            this.subCategoryName = subName;
            this.predicate = pred;
        }
    }

    public enum FilterCategory { ALL, NUMERIC, ALPHABET, KANA, OTHER }


    public YmCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.timerManager = plugin.getTimerManager();
        this.pvpManager = plugin.getPvpManager();
        this.whitelistManager = plugin.getWhitelistManager();
        this.nameManager = plugin.getNameManager();
        this.restrictionManager = plugin.getRestrictionManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            switch(subCommand) {
                case "reload":
                    plugin.loadConfigAndManual();
                    sender.sendMessage("§aYAPIMARU_Pluginの設定ファイルをリロードしました。");
                    return true;
                case "list":
                    List<String> manual = plugin.getCommandManual();
                    if (manual.isEmpty()) {
                        sender.sendMessage("§cマニュアルファイル(commands.txt)が読み込めませんでした。");
                    } else {
                        manual.forEach(sender::sendMessage);
                    }
                    return true;
                case "cmlist":
                    sender.sendMessage("§6--- Command List ---");
                    sender.sendMessage("§e/hub §7- hubにテレポート");
                    sender.sendMessage("§e/skinlist §7- スキンリストを表示");
                    sender.sendMessage("§e/name §7- 名前変更");
                    sender.sendMessage("§e/creator [c] §7- クリエイターGUI");
                    sender.sendMessage("§e/yapimaru [ym] §7- 管理者GUI");
                    sender.sendMessage("§e/server §7- サーバー再起動関連");
                    sender.sendMessage("§e/spectator §7- スペクテイター設定");
                    sender.sendMessage("§e/timer [tm] §7- timer設定");
                    sender.sendMessage("§e/pvp §7- pvp設定");
                    sender.sendMessage("§e/pvp ded §7- ded地点設定");
                    sender.sendMessage("§e/pvp lives §7- 残機設定");
                    sender.sendMessage("§e/pvp invincible §7- リスポーン時無敵時間");
                    sender.sendMessage("§e/pvp grace §7- 準備時間");
                    sender.sendMessage("§e/voting [vote] §7- 投票機能");
                    return true;
            }
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("GUIはプレイヤーのみが開けます。");
            return true;
        }
        openMainGui(p);
        return true;
    }

    public void openMainGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 36, GUI_TITLE);
        fillBackground(gui);

        gui.setItem(10, createItem(Material.CLOCK, timerManager.isFeatureEnabled() ? "§aタイマー機能: 有効" : "§cタイマー機能: 無効", timerManager.isFeatureEnabled(), "§7クリックで有効/無効を切り替え"));
        gui.setItem(11, createItem(Material.DIAMOND_SWORD, pvpManager.isFeatureEnabled() ? "§aPvPモード: 有効" : "§cPvPモード: 無効", pvpManager.isFeatureEnabled(), "§7クリックで有効/無効を切り替え"));
        gui.setItem(12, createItem(Material.NETHERITE_SWORD, "§cPVP各モード", false, "§7PvPのサブ機能の有効/無効を切替"));

        gui.setItem(19, createItem(Material.WRITABLE_BOOK, "§aタイマー詳細設定", false, "§7タイマーの詳細設定と確認"));
        gui.setItem(20, createItem(Material.ANVIL, "§cPVP詳細設定", false, "§7PvPの各時間などを細かく調整します"));

        gui.setItem(15, createItem(Material.BEACON, "§6管理者機能", false, "§7管理者向けの機能を開きます。"));

        gui.setItem(35, createItem(Material.BARRIER, "§c閉じる"));
        player.openInventory(gui);
    }

    public void openTimerSettingsGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, TIMER_SETTINGS_GUI_TITLE);
        fillBackground(gui);

        String modeName = timerManager.getMode() == TimerManager.TimerMode.COUNTDOWN ? "タイマー" : "ストップウォッチ";
        gui.setItem(11, createItem(Material.COMPASS, "§eモード: §f" + modeName, false, "§7クリックで切替"));

        String displayName;
        switch (timerManager.getDisplayType()) {
            case ACTIONBAR -> displayName = "アクションバー";
            case BOSSBAR -> displayName = "ボスバー";
            case TITLE -> displayName = "画面中央";
            default -> displayName = "バックグラウンド";
        }
        gui.setItem(13, createItem(Material.OAK_SIGN, "§e表示: §f" + displayName, false, "§7クリックで切替"));
        gui.setItem(15, createItem(Material.BOOK, "§e終了時アクション", false, "§7クリックでチャットに一覧表示"));

        gui.setItem(20, createItem(Material.LIME_DYE, "§a+1秒", false, "§7開始前カウントを1秒増やします"));
        gui.setItem(22, createItem(Material.REDSTONE_TORCH, "§e開始前カウント: §f" + timerManager.getPreStartSeconds() + "秒", false));
        gui.setItem(24, createItem(Material.RED_DYE, "§c-1秒", false, "§7開始前カウントを1秒減らします"));

        gui.setItem(31, createItem(Material.CLOCK, "§e設定時間: §f" + timerManager.getInitialTimeFormatted(), false));

        gui.setItem(37, createItem(Material.IRON_NUGGET, "§a+1秒", false));
        gui.setItem(38, createItem(Material.GOLD_NUGGET, "§a+10秒", false));
        gui.setItem(39, createItem(Material.IRON_INGOT, "§a+1分", false));
        gui.setItem(40, createItem(Material.GOLD_INGOT, "§a+10分", false));
        gui.setItem(41, createItem(Material.DIAMOND_BLOCK, "§a+1時間", false));
        gui.setItem(43, createItem(Material.BARRIER, "§c0秒に設定", false));

        gui.setItem(45, createItem(Material.ARROW, "§eメインメニューに戻る"));
        gui.setItem(53, createItem(Material.BARRIER, "§c閉じる"));

        player.openInventory(gui);
    }

    public void openPvpModesGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, PVP_MODES_GUI_TITLE);
        fillBackground(gui);

        gui.setItem(10, createItem(Material.TOTEM_OF_UNDYING, "§a残機システム", pvpManager.isLivesFeatureEnabled(), "§7状態: " + (pvpManager.isLivesFeatureEnabled() ? "§a有効" : "§c無効"), "§7クリックでON/OFF切替"));
        gui.setItem(11, createItem(Material.SKELETON_SKULL, "§aデススポーン", pvpManager.isDedFeatureEnabled(), "§7状態: " + (pvpManager.isDedFeatureEnabled() ? "§a有効" : "§c無効"), "§7クリックでON/OFF切替"));
        gui.setItem(12, createItem(Material.GOLDEN_APPLE, "§aリスポーン無敵", pvpManager.isRespawnInvincibleEnabled(), "§7状態: " + (pvpManager.isRespawnInvincibleEnabled() ? "§a有効" : "§c無効"), "§7クリックでON/OFF切替"));
        gui.setItem(13, createItem(Material.SHIELD, "§a準備時間 (Grace)", pvpManager.isGracePeriodEnabled(), "§7状態: " + (pvpManager.isGracePeriodEnabled() ? "§a有効" : "§c無効"), "§7クリックでON/OFF切替"));

        gui.setItem(18, createItem(Material.ARROW, "§eメインメニューに戻る"));
        player.openInventory(gui);
    }

    public void openPvpDetailedSettingsGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 45, PVP_DETAILED_SETTINGS_GUI_TITLE);
        fillBackground(gui);

        gui.setItem(11, createItem(Material.SHIELD, "§e準備時間: §f" + pvpManager.getGracePeriodTime() + "秒", false));
        gui.setItem(2, createItem(Material.LIME_DYE, "§a準備時間 +1秒", false));
        gui.setItem(20, createItem(Material.RED_DYE, "§c準備時間 -1秒", false));

        gui.setItem(13, createItem(Material.GOLDEN_APPLE, "§eリスポーン無敵: §f" + pvpManager.getRespawnInvincibleTime() + "秒", false));
        gui.setItem(4, createItem(Material.LIME_DYE, "§a無敵時間 +1秒", false));
        gui.setItem(22, createItem(Material.RED_DYE, "§c無敵時間 -1秒", false));

        gui.setItem(15, createItem(Material.SKELETON_SKULL, "§eデス待機: §f" + pvpManager.getDedTime() + "秒", false));
        gui.setItem(6, createItem(Material.LIME_DYE, "§a待機時間 +1秒", false));
        gui.setItem(24, createItem(Material.RED_DYE, "§c待機時間 -1秒", false));

        gui.setItem(36, createItem(Material.ARROW, "§eメインメニューに戻る"));
        gui.setItem(44, createItem(Material.BARRIER, "§c閉じる"));

        player.openInventory(gui);
    }

    public void openAdminGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 36, ADMIN_GUI_TITLE);
        fillBackground(gui);

        gui.setItem(11, createItem(Material.PLAYER_HEAD, "§eカスタムホワイトリスト管理", false, "§7特定のプレイヤーのみが入れるように", "§7サーバーへの接続を制限します。"));
        gui.setItem(13, createItem(Material.COMMAND_BLOCK, "§eプレイヤー設定", false, "§7全プレイヤーの行動を制限します。"));
        gui.setItem(15, createItem(Material.ENDER_EYE, "§eスペクテイター非表示", plugin.getSpectatorManager().isEnabled(), "§7状態: " + (plugin.getSpectatorManager().isEnabled() ? "§a有効" : "§c無効"), "§7クリックでON/OFF切替"));

        gui.setItem(27, createItem(Material.ARROW, "§eメインメニューに戻る"));
        gui.setItem(35, createItem(Material.BARRIER, "§c閉じる"));
        player.openInventory(gui);
    }

    public void openPlayerSettingsGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, PLAYER_SETTINGS_GUI_TITLE);
        fillBackground(gui);
        PlayerRestrictionManager.RestrictionMode current = restrictionManager.getCurrentMode();

        gui.setItem(10, createItem(Material.IRON_SWORD, "§a1. 全員アドベンチャーモード", current == PlayerRestrictionManager.RestrictionMode.ADVENTURE_ONLY, "§7全員をアドベンチャーにし、破壊設置を禁止"));
        gui.setItem(12, createItem(Material.IRON_BARS, "§e2. クリック等禁止", current == PlayerRestrictionManager.RestrictionMode.NO_INTERACT, "§7アドベンチャーに加え、", "§7クリックや額縁剥がし等も禁止"));
        gui.setItem(14, createItem(Material.PISTON, "§63. WASD移動禁止", current == PlayerRestrictionManager.RestrictionMode.NO_WASD, "§7アドベンチャー、クリック禁止に加え、", "§7WASD移動のみ禁止（ジャンプや視点移動は可）"));
        gui.setItem(16, createItem(Material.COBWEB, "§c4. 移動完全禁止", current == PlayerRestrictionManager.RestrictionMode.NO_MOVE, "§7アドベンチャー、クリック禁止に加え、", "§7全ての移動を禁止（視点のみ可）"));

        gui.setItem(18, createItem(Material.ARROW, "§e管理者機能メニューに戻る"));
        gui.setItem(26, createItem(Material.BARRIER, "§c閉じる"));
        player.openInventory(gui);
    }

    public void openWhitelistMainGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, WL_MAIN_GUI_TITLE);
        fillBackground(gui);
        WhitelistManager.Mode mode = whitelistManager.getMode();

        gui.setItem(10, createItem(Material.IRON_DOOR, "§fモード: §cOFF", mode == WhitelistManager.Mode.OFF, "§7誰でもサーバーに参加できます。"));
        gui.setItem(11, createItem(Material.DIAMOND, "§fモード: §bオーナーのみ", mode == WhitelistManager.Mode.OWNER_ONLY, "§7指定のオーナーのみ参加できます。"));
        gui.setItem(12, createItem(Material.EMERALD, "§fモード: §aリストのみ", mode == WhitelistManager.Mode.WHITELIST_ONLY, "§7オーナーと許可リストの", "§7プレイヤーのみ参加できます。"));
        gui.setItem(13, createItem(Material.NETHER_STAR, "§fモード: §6撮影中(途中参加不可)", mode == WhitelistManager.Mode.LOCKDOWN, "§7このモードを有効化した時点での", "§7オンラインプレイヤーのみ参加できます。"));

        gui.setItem(31, createItem(Material.WRITABLE_BOOK, "§aプレイヤーリストを管理する", false, "§7参加を許可するプレイヤーの", "§7リストを編集します。"));

        gui.setItem(45, createItem(Material.ARROW, "§e管理者機能メニューに戻る"));
        gui.setItem(49, createItem(Material.BARRIER, "§c閉じる"));
        player.openInventory(gui);
    }

    public void openPlayerSelectGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, WL_PLAYER_SELECT_GUI_TITLE);
        fillBackground(gui);
        gui.setItem(10, createItem(Material.EMERALD_BLOCK, "§a許可済みプレイヤー", false, "§7現在サーバーに参加できる", "§7プレイヤーの一覧を確認・編集します。"));
        gui.setItem(12, createItem(Material.DIAMOND_BLOCK, "§b許可候補プレイヤー", false, "§7許可リストへの追加を保留している", "§7プレイヤーの一覧を確認・編集します。"));
        gui.setItem(14, createItem(Material.BOOK, "§7ホワイトリスト(全員)", false, "§7サーバーの全プレイヤーから", "§7許可候補リストに追加します。"));

        gui.setItem(18, createItem(Material.ARROW, "§e戻る"));
        player.openInventory(gui);
    }

    public void openWhitelistGui(Player player, String title, String listType, List<OfflinePlayer> sourceList, String... lore) {
        FilterState state = playerFilterStates.computeIfAbsent(player.getUniqueId(), k -> new FilterState());
        int page = playerGuiPages.getOrDefault(player.getUniqueId(), 0);

        List<OfflinePlayer> filteredList = sourceList.stream()
                .filter(p -> {
                    String name = getSortableName(p);
                    if (name.isEmpty()) return state.category == FilterCategory.OTHER;
                    return state.predicate.test(name.charAt(0));
                })
                .sorted(getCustomComparator())
                .collect(Collectors.toList());

        int totalPages = (int) Math.ceil((double) filteredList.size() / 45.0);
        page = Math.min(page, Math.max(0, totalPages - 1));
        playerGuiPages.put(player.getUniqueId(), page);

        String titleWithFilter = title;
        if(state.category != FilterCategory.ALL) {
            titleWithFilter += " §7(" + state.subCategoryName + ")";
        }
        titleWithFilter += " §8(" + (page + 1) + "/" + Math.max(1, totalPages) + ")";

        Inventory gui = Bukkit.createInventory(null, 54, titleWithFilter);
        fillBackground(gui);

        populatePlayerListGui(gui, filteredList, page, lore);

        if (page > 0) gui.setItem(45, createItem(Material.ARROW, "§e前のページ"));
        if ((page + 1) * 45 < filteredList.size()) gui.setItem(53, createItem(Material.ARROW, "§e次のページ"));

        if (listType.equals("candidate")) {
            GuiListener.ActionMode actionMode = getPlayerWlActionMode(player.getUniqueId());
            Material mat = actionMode == GuiListener.ActionMode.PROMOTE ? Material.LIME_WOOL : Material.RED_WOOL;
            String modeName = actionMode == GuiListener.ActionMode.PROMOTE ? "§a許可リストへ移動" : "§cリストから削除";
            String modeDesc = actionMode == GuiListener.ActionMode.PROMOTE ? "§7クリックでプレイヤーを許可リストに移動" : "§7クリックでプレイヤーを候補から削除";
            gui.setItem(47, createItem(mat, "§eアクション: " + modeName, false, modeDesc, "§7クリックでモード切替"));
        }

        gui.setItem(48, createItem(Material.COMPASS, "§eフィルター", false, "§7表示するプレイヤーを分類します。", "§7現在: " + state.subCategoryName));
        gui.setItem(49, createItem(Material.ARROW, "§e戻る"));
        player.openInventory(gui);
    }

    public void openFilterGui(Player player, String previousTitle, List<OfflinePlayer> sourceList) {
        FilterState state = playerFilterStates.computeIfAbsent(player.getUniqueId(), k -> new FilterState());
        Inventory gui = Bukkit.createInventory(null, 36, WL_FILTER_GUI_TITLE);
        fillBackground(gui);

        gui.setItem(4, createItem(Material.PAPER, "§f§l" + previousTitle, false, "§7のフィルターを設定します"));

        gui.setItem(10, createItem(Material.BOOK, "§f全て表示", state.category == FilterCategory.ALL, "§7全てのプレイヤーを表示します"));

        if (sourceList.stream().anyMatch(p -> getSortCategory(getSortableName(p)) == 0)) {
            gui.setItem(12, createItem(Material.OAK_SIGN, "§f数字", state.category == FilterCategory.NUMERIC, "§7名前が数字で始まるプレイヤー"));
        }
        if (sourceList.stream().anyMatch(p -> getSortCategory(getSortableName(p)) == 1)) {
            gui.setItem(13, createItem(Material.NAME_TAG, "§fA-Z", state.category == FilterCategory.ALPHABET, "§7名前がアルファベットで始まるプレイヤー"));
        }
        if (sourceList.stream().anyMatch(p -> getSortCategory(getSortableName(p)) == 2)) {
            gui.setItem(14, createItem(Material.CHERRY_SAPLING, "§fあ-ん", state.category == FilterCategory.KANA, "§7名前がかな/カナで始まるプレイヤー"));
        }
        if (sourceList.stream().anyMatch(p -> getSortCategory(getSortableName(p)) == 3)) {
            gui.setItem(16, createItem(Material.STRUCTURE_VOID, "§fその他", state.category == FilterCategory.OTHER, "§7上記以外の文字で始まるプレイヤー"));
        }

        gui.setItem(31, createItem(Material.ARROW, "§eリストに戻る"));
        player.openInventory(gui);
    }

    public void openSubFilterGui(Player player, String previousTitle, FilterCategory category, List<OfflinePlayer> sourceList) {
        Inventory gui = Bukkit.createInventory(null, 27, WL_FILTER_GUI_TITLE + " - " + category.name());
        fillBackground(gui);
        gui.setItem(4, createItem(Material.PAPER, "§f§l" + previousTitle, false, "§7のフィルターを設定します"));

        switch (category) {
            case NUMERIC:
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "0-4").test(getSortableName(p).charAt(0))))
                    gui.setItem(11, createItem(Material.OAK_SIGN, "0-4"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "5-9").test(getSortableName(p).charAt(0))))
                    gui.setItem(15, createItem(Material.OAK_SIGN, "5-9"));
                break;
            case ALPHABET:
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "A-F").test(getSortableName(p).charAt(0))))
                    gui.setItem(11, createItem(Material.NAME_TAG, "A-F"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "G-O").test(getSortableName(p).charAt(0))))
                    gui.setItem(13, createItem(Material.NAME_TAG, "G-O"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "P-Z").test(getSortableName(p).charAt(0))))
                    gui.setItem(15, createItem(Material.NAME_TAG, "P-Z"));
                break;
            case KANA:
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "あ行・か行").test(getSortableName(p).charAt(0))))
                    gui.setItem(10, createItem(Material.CHERRY_SAPLING, "あ行・か行"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "さ行・た行").test(getSortableName(p).charAt(0))))
                    gui.setItem(11, createItem(Material.CHERRY_SAPLING, "さ行・た行"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "な行・は行").test(getSortableName(p).charAt(0))))
                    gui.setItem(12, createItem(Material.CHERRY_SAPLING, "な行・は行"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "ま行・や行").test(getSortableName(p).charAt(0))))
                    gui.setItem(14, createItem(Material.CHERRY_SAPLING, "ま行・や行"));
                if (sourceList.stream().anyMatch(p -> getPredicateForCategory(category, "ら行・わ行").test(getSortableName(p).charAt(0))))
                    gui.setItem(15, createItem(Material.CHERRY_SAPLING, "ら行・わ行"));
                break;
        }

        gui.setItem(22, createItem(Material.ARROW, "§eカテゴリー選択に戻る"));
        player.openInventory(gui);
    }

    public int getPlayerGuiPage(UUID uuid) {
        return playerGuiPages.getOrDefault(uuid, 0);
    }

    public void setPlayerGuiPage(UUID uuid, int page) {
        playerGuiPages.put(uuid, page);
    }

    public GuiListener.ActionMode getPlayerWlActionMode(UUID uuid) {
        return playerWlActionModes.getOrDefault(uuid, GuiListener.ActionMode.PROMOTE);
    }

    public void togglePlayerWlActionMode(UUID uuid) {
        GuiListener.ActionMode current = getPlayerWlActionMode(uuid);
        playerWlActionModes.put(uuid, current == GuiListener.ActionMode.PROMOTE ? GuiListener.ActionMode.DEMOTE : GuiListener.ActionMode.PROMOTE);
    }

    public FilterState getPlayerFilterState(UUID uuid) {
        return playerFilterStates.computeIfAbsent(uuid, k -> new FilterState());
    }

    private void fillBackground(Inventory gui) {
        ItemStack background = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, background);
            }
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        return createItem(mat, name, false, lore);
    }

    private ItemStack createItem(Material mat, String name, boolean glow, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        if (glow) {
            meta.addEnchant(Enchantment.LURE, 1, true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private void populatePlayerListGui(Inventory gui, List<OfflinePlayer> players, int page, String... lore) {
        final int itemsPerPage = 45;
        for (int i = 0; i < itemsPerPage; i++) {
            int index = page * itemsPerPage + i;
            if (index >= players.size()) break;
            gui.setItem(i, createPlayerHead(players.get(index), lore));
        }
    }

    private ItemStack createPlayerHead(OfflinePlayer player, String... lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if(meta == null) return head;

        String displayName = nameManager.getDisplayName(player.getUniqueId());

        meta.setOwningPlayer(player);

        meta.setDisplayName("§r" + displayName);
        List<String> finalLore = new ArrayList<>(Arrays.asList(lore));
        finalLore.add("§8Original: " + player.getName());
        meta.setLore(finalLore);

        head.setItemMeta(meta);
        return head;
    }

    public String getSortableName(OfflinePlayer p) {
        String name = nameManager.getLinkedName(p.getUniqueId());
        if (name == null || name.isEmpty()) name = p.getName();
        return name != null ? name.toUpperCase() : "";
    }

    private int getSortCategory(String name) {
        if (name == null || name.isEmpty()) return 3;

        char firstChar = name.toUpperCase().charAt(0);

        if (Character.isDigit(firstChar)) return 0;
        if (firstChar >= 'A' && firstChar <= 'Z') return 1;

        String firstCharStr = String.valueOf(name.charAt(0));
        if (Pattern.matches("^[\\u3040-\\u309F\\u30A0-\\u30FF\\uFF65-\\uFF9F]", firstCharStr)) return 2;

        return 3;
    }

    public Predicate<Character> getPredicateForCategory(FilterCategory category, String subCategory) {
        return switch (category) {
            case ALL -> c -> true;
            case NUMERIC -> switch (subCategory) {
                case "0-4" -> c -> c >= '0' && c <= '4';
                case "5-9" -> c -> c >= '5' && c <= '9';
                default -> c -> Character.isDigit(c);
            };
            case ALPHABET -> switch (subCategory) {
                case "A-F" -> c -> c >= 'A' && c <= 'F';
                case "G-O" -> c -> c >= 'G' && c <= 'O';
                case "P-Z" -> c -> c >= 'P' && c <= 'Z';
                default -> c -> c >= 'A' && c <= 'Z';
            };
            case KANA -> {
                yield switch (subCategory) {
                    case "あ行・か行" -> c -> "あいうえおかきくけこがぎぐげごアイウエオカキクケコガギグゲゴｱｲｳｴｵｶｷｸｹｺｶﾞｷﾞｸﾞｹﾞｺﾞ".indexOf(c) != -1;
                    case "さ行・た行" -> c -> "さしすせそざじずぜぞたちつてとだぢづでどサシスセソザジズゼゾタチツテトダヂヅデドｻｼｽｾｿｻﾞｼﾞｽﾞｾﾞｿﾞﾀﾁﾂﾃﾄﾀﾞﾁﾞﾂﾞﾃﾞﾄﾞ".indexOf(c) != -1;
                    case "な行・は行" -> c -> "なにぬねのはひふへほばびぶべぼぱぴぷぺぽナニヌネノハヒフヘホバビブベボパピプペポﾏﾐﾑﾒﾓﾊﾋﾌﾍﾎﾊﾞﾋﾞﾌﾞﾍﾞﾎﾞﾊﾟﾋﾟﾌﾟﾍﾟﾎﾟ".indexOf(c) != -1;
                    case "ま行・や行" -> c -> "まみむめもやゆよマミムメモヤユヨﾏﾐﾑﾒﾓﾔﾕﾖ".indexOf(c) != -1;
                    case "ら行・わ行" -> c -> "らりるれろわをんラリルレロワヲンﾗﾘﾙﾚﾛﾜｦﾝ".indexOf(c) != -1;
                    default -> c -> Pattern.matches("^[\\u3040-\\u309F\\u30A0-\\u30FF\\uFF65-\\uFF9F]", String.valueOf(c));
                };
            }
            case OTHER -> c -> getSortCategory(String.valueOf(c)) == 3;
        };
    }

    private Comparator<OfflinePlayer> getCustomComparator() {
        Collator collator = Collator.getInstance(Locale.JAPANESE);
        return (p1, p2) -> {
            String name1 = getSortableName(p1);
            String name2 = getSortableName(p2);

            int category1 = getSortCategory(name1);
            int category2 = getSortCategory(name2);

            if (category1 != category2) {
                return Integer.compare(category1, category2);
            }

            return collator.compare(name1, name2);
        };
    }
}