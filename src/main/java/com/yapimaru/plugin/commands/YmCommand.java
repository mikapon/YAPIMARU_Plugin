package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class YmCommand implements CommandExecutor {
    // GUI Titles
    public static final String GUI_PREFIX = "§b§lYAPIMARU §7- ";
    public static final String GUI_TITLE = GUI_PREFIX + "§8設定パネル";
    public static final String PVP_MODES_GUI_TITLE = GUI_PREFIX + "§cPVP各モード";
    public static final String PVP_DETAILED_SETTINGS_GUI_TITLE = GUI_PREFIX + "§cPVP詳細設定";
    public static final String ADMIN_GUI_TITLE = GUI_PREFIX + "§6管理者機能";
    public static final String PLAYER_SETTINGS_GUI_TITLE = GUI_PREFIX + "§6プレイヤー設定";
    public static final String WL_MAIN_GUI_TITLE = GUI_PREFIX + "§6WL管理";
    public static final String TIMER_SETTINGS_GUI_TITLE = GUI_PREFIX + "§aタイマー詳細設定";

    private final YAPIMARU_Plugin plugin;
    private final TimerManager timerManager;
    private final PvpManager pvpManager;
    private final WhitelistManager whitelistManager;
    private final PlayerRestrictionManager restrictionManager;
    private final ParticipantManager participantManager;


    public YmCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.timerManager = plugin.getTimerManager();
        this.pvpManager = plugin.getPvpManager();
        this.whitelistManager = plugin.getWhitelistManager();
        this.participantManager = plugin.getParticipantManager();
        this.restrictionManager = plugin.getRestrictionManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("このコマンドを使用する権限がありません。", NamedTextColor.RED));
            return true;
        }

        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            switch(subCommand) {
                case "reload" -> {
                    plugin.loadConfigAndManual();
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("プラグインの設定ファイルと全参加者情報を再読み込みしました。", NamedTextColor.GREEN));
                    return true;
                }
                case "list" -> {
                    List<String> manual = plugin.getCommandManual();
                    if (manual.isEmpty()) {
                        plugin.getAdventure().sender(sender).sendMessage(Component.text("マニュアルファイル(commands.txt)が読み込めませんでした。", NamedTextColor.RED));
                    } else {
                        manual.forEach(line -> plugin.getAdventure().sender(sender).sendMessage(Component.text(line)));
                    }
                    return true;
                }
                case "cmlist" -> {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§6--- Command List ---"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/hub §7- hubにテレポート"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/skinlist §7- スキンリストを表示"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/name §7- 名前変更"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/creator [c] §7- クリエイターGUI"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/yapimaru [ym] §7- 管理者GUI"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/server §7- サーバー再起動関連"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/spectator §7- スペクテイター設定"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/timer [tm] §7- timer設定"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/pvp §7- pvp設定"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/stats - 統計機能"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/photographing - 撮影参加回数を記録"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/voting [vote] §7- 投票機能"));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/ans §7- 投票に回答"));
                    return true;
                }
                case "participant" -> {
                    handleParticipantCommand(sender, args);
                    return true;
                }
                case "remove-unregistered-wl" -> {
                    if (args.length == 2) {
                        try {
                            UUID uuid = UUID.fromString(args[1]);
                            whitelistManager.removeAllowed(uuid);
                            plugin.getAdventure().sender(sender).sendMessage(Component.text("UUID: " + uuid + " をホワイトリストから削除しました。", NamedTextColor.GREEN));
                        } catch (IllegalArgumentException e) {
                            plugin.getAdventure().sender(sender).sendMessage(Component.text("無効なUUIDです。", NamedTextColor.RED));
                        }
                    }
                    return true;
                }
            }
        }

        if (!(sender instanceof Player p)) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("GUIはプレイヤーのみが開けます。", NamedTextColor.RED));
            return true;
        }
        openMainGui(p);
        return true;
    }

    private void handleParticipantCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("使い方: /ym participant <add|remove|list> ...", NamedTextColor.RED));
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "add" -> {
                if (args.length < 3) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("参加者IDを指定してください。", NamedTextColor.RED));
                    return;
                }
                String participantId = args[2];
                if (participantManager.moveParticipantToActive(participantId)) {
                    ParticipantData data = participantManager.getParticipant(participantId);
                    if (data != null) {
                        for (UUID uuid : data.getAssociatedUuids()) {
                            whitelistManager.addAllowed(uuid);
                        }
                    }
                    plugin.getAdventure().sender(sender).sendMessage(Component.text(participantId + " を現役参加者に戻し、ホワイトリストに追加しました。", NamedTextColor.GREEN));
                } else {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text(participantId + " は除隊リストにいないか、移動に失敗しました。", NamedTextColor.RED));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("参加者IDを指定してください。", NamedTextColor.RED));
                    return;
                }
                String participantId = args[2];
                ParticipantData data = participantManager.getParticipant(participantId);
                if (data != null && participantManager.moveParticipantToDischarged(participantId)) {
                    for (UUID uuid : data.getAssociatedUuids()) {
                        whitelistManager.removeAllowed(uuid);
                    }
                    plugin.getAdventure().sender(sender).sendMessage(Component.text(participantId + " を除隊させ、ホワイトリストから削除しました。", NamedTextColor.GREEN));
                } else {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text(participantId + " は現役参加者にいないか、移動に失敗しました。", NamedTextColor.RED));
                }
            }
            case "list" -> {
                if (args.length < 3) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("リストの種類を指定してください: participant または discharge", NamedTextColor.RED));
                    return;
                }
                String type = args[2].toLowerCase();
                if (type.equals("participant")) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§6--- 現役参加者リスト ---"));
                    participantManager.getActiveParticipants().stream()
                            .map(ParticipantData::getParticipantId)
                            .sorted()
                            .forEach(id -> plugin.getAdventure().sender(sender).sendMessage(Component.text("§f- " + id)));
                } else if (type.equals("discharge")) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("§6--- 除隊者リスト ---"));
                    participantManager.getDischargedParticipants().stream()
                            .map(ParticipantData::getParticipantId)
                            .sorted()
                            .forEach(id -> plugin.getAdventure().sender(sender).sendMessage(Component.text("§7- " + id)));
                } else {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("リストの種類は participant または discharge を指定してください。", NamedTextColor.RED));
                }
            }
            default -> plugin.getAdventure().sender(sender).sendMessage(Component.text("無効なアクションです: " + action, NamedTextColor.RED));
        }
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

        gui.setItem(11, createItem(Material.PLAYER_HEAD, "§e参加者管理", false, "§7参加者の現役/除隊の管理や", "§7ホワイトリストとの連携を行います。", "§e/ym participant <add|remove|list>"));
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
}