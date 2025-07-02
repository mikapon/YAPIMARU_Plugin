package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.PvpManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class PvpCommand implements CommandExecutor {
    private final YAPIMARU_Plugin plugin;
    private final PvpManager pvpManager;
    private final BukkitAudiences adventure;

    public PvpCommand(YAPIMARU_Plugin plugin, PvpManager pvpManager) {
        this.plugin = plugin;
        this.pvpManager = pvpManager;
        this.adventure = plugin.getAdventure();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender, "main");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("on")) {
            pvpManager.setFeatureEnabled(true, sender);
            return true;
        }

        if (!pvpManager.isFeatureEnabled() && !sub.equals("off")) {
            adventure.sender(sender).sendMessage(Component.text("PvPモードは現在無効です。/pvp on で有効にしてください。", NamedTextColor.RED));
            return true;
        }

        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "off":
                pvpManager.setFeatureEnabled(false, sender);
                break;
            case "set":
                if (!(sender instanceof Player p)) { sender.sendMessage("プレイヤーのみが実行できます。"); return true; }
                if (subArgs.length < 1) { sendSubHelp(sender, "set"); return true; }
                pvpManager.setCombined(p, subArgs[0]);
                break;
            case "remove":
                if (subArgs.length < 1) { sendSubHelp(sender, "remove"); return true; }
                pvpManager.removeTeamSettings(sender, subArgs[0]);
                break;
            case "reset":
                pvpManager.resetSettings(sender);
                break;
            case "status":
                pvpManager.showStatus(sender);
                break;
            case "confirm":
                pvpManager.prepareGame(sender);
                break;
            case "ded":
                handleDedCommand(sender, subArgs);
                break;
            case "lives":
                handleLivesCommand(sender, subArgs);
                break;
            case "invincible":
                handleInvincibleCommand(sender, subArgs);
                break;
            case "grace":
                handleGraceCommand(sender, subArgs);
                break;
            default:
                sendHelp(sender, "main");
                break;
        }
        return true;
    }

    private void handleDedCommand(CommandSender sender, String[] args) {
        if (args.length < 1) { sendHelp(sender, "ded"); return; }
        switch(args[0].toLowerCase()) {
            case "on":
                pvpManager.setDedFeatureEnabled(true, sender);
                break;
            case "off":
                pvpManager.setDedFeatureEnabled(false, sender);
                break;
            case "set":
                if (!(sender instanceof Player p)) { sender.sendMessage("プレイヤーのみが実行できます。"); return; }
                pvpManager.setDedCombined(p);
                break;
            case "time":
                if (args.length < 2) { sendSubHelp(sender, "ded_time"); return; }
                try {
                    int time = Integer.parseInt(args[1]);
                    pvpManager.setDedTime(time, sender);
                } catch (NumberFormatException e) {
                    sender.sendMessage("秒数は数字で入力してください。");
                }
                break;
            default: sendHelp(sender, "ded");
        }
    }

    private void handleLivesCommand(CommandSender sender, String[] args) {
        if (args.length < 1) { sendHelp(sender, "lives"); return; }
        switch(args[0].toLowerCase()) {
            case "on":
                pvpManager.setLivesFeatureEnabled(true, sender);
                break;
            case "off":
                pvpManager.setLivesFeatureEnabled(false, sender);
                break;
            case "mode":
                if (args.length < 2) { sendSubHelp(sender, "lives_mode"); return; }
                try {
                    PvpManager.LivesMode mode = PvpManager.LivesMode.valueOf(args[1].toUpperCase());
                    pvpManager.setLivesMode(mode, sender);
                } catch (IllegalArgumentException e) {
                    sendSubHelp(sender, "lives_mode");
                }
                break;
            case "set":
                if (args.length < 4) { sendSubHelp(sender, "lives_set"); return; }
                try {
                    int amount = Integer.parseInt(args[3]);
                    if (args[1].equalsIgnoreCase("team")) {
                        pvpManager.setTeamLives(args[2], amount, sender);
                    } else if (args[1].equalsIgnoreCase("player")) {
                        Player target = Bukkit.getPlayer(args[2]);
                        if (target == null) { sender.sendMessage("プレイヤー " + args[2] + " が見つかりません。"); return; }
                        pvpManager.setPlayerLives(target, amount, sender);
                    } else {
                        sendSubHelp(sender, "lives_set");
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("残機は数字で入力してください。");
                }
                break;
            case "onzero":
                if (args.length < 2) { sendSubHelp(sender, "lives_onzero"); return; }
                try {
                    PvpManager.OnZeroAction action = PvpManager.OnZeroAction.valueOf(args[1].toUpperCase());
                    pvpManager.setOnZeroAction(action, sender);
                } catch (IllegalArgumentException e) {
                    sendSubHelp(sender, "lives_onzero");
                }
                break;
            default: sendHelp(sender, "lives");
        }
    }

    private void handleInvincibleCommand(CommandSender sender, String[] args) {
        if (args.length < 1) { sendHelp(sender, "invincible"); return; }
        String action = args[0].toLowerCase();
        if(action.equals("on") || action.equals("off")){
            pvpManager.setRespawnInvincibleEnabled(action.equals("on"), sender);
        } else if(action.equals("time") && args.length > 1) {
            try {
                int time = Integer.parseInt(args[1]);
                pvpManager.setRespawnInvincibleTime(time, sender);
            } catch (NumberFormatException e) {
                sender.sendMessage("時間は数字で入力してください。");
            }
        } else {
            sendHelp(sender, "invincible");
        }
    }

    private void handleGraceCommand(CommandSender sender, String[] args) {
        if (args.length < 1) { sendHelp(sender, "grace"); return; }
        String action = args[0].toLowerCase();
        if(action.equals("on") || action.equals("off")){
            pvpManager.setGracePeriodEnabled(action.equals("on"), sender);
        } else if(action.equals("time") && args.length > 1) {
            try {
                int time = Integer.parseInt(args[1].replaceAll("[sSmMhH]", ""));
                pvpManager.setGracePeriodTime(time, sender);
            } catch (NumberFormatException e) {
                sender.sendMessage("時間は数字で入力してください。");
            }
        } else {
            sendHelp(sender, "grace");
        }
    }

    private void sendHelp(CommandSender s, String category) {
        s.sendMessage("§6--- PvP Command Help ---");
        switch (category) {
            case "ded":
                s.sendMessage("§e/pvp ded <on|off> §7- デススポーン機能の有効/無効");
                s.sendMessage("§e/pvp ded set §7- 待機場所と壁を設定");
                s.sendMessage("§e/pvp ded time <秒数> §7- 待機時間を設定(デフォ3)");
                break;
            case "lives":
                s.sendMessage("§e/pvp lives <on|off> §7- 残機システムの有効/無効");
                s.sendMessage("§e/pvp lives mode <team|player> §7- 残機モード設定");
                s.sendMessage("§e/pvp lives set <team|player> <対象> <数> §7- 残機設定");
                s.sendMessage("§e/pvp lives onzero <spectator|wait> §7- 残機0時の動作設定");
                break;
            case "invincible":
                s.sendMessage("§e/pvp invincible <on|off> §7- リスポーン時無敵の有効/無効");
                s.sendMessage("§e/pvp invincible time <秒数> §7- 効果時間を設定（デフォルト3秒）");
                break;
            case "grace":
                s.sendMessage("§e/pvp grace <on|off> §7- 準備時間の有効/無効");
                s.sendMessage("§e/pvp grace time <秒数> §7- 効果時間を設定（デフォルト3秒）");
                break;
            default:
                s.sendMessage("§e/pvp <on|off> §7- PvPモードの有効/無効");
                s.sendMessage("§e/pvp set <色> §7- アリーナとスポーン地点を同時設定");
                s.sendMessage("§e/pvp remove <色> §7- チーム設定を削除");
                s.sendMessage("§e/pvp reset §7- 全てのPvP設定を初期化");
                s.sendMessage("§e/pvp status §7- 設定状況の確認");
                s.sendMessage("§e/pvp confirm §7- 開始前の設定最終確認");
                s.sendMessage("§e/pvp <ded|lives|invincible|grace> ... §7- 各機能の詳細設定");
                break;
        }
    }

    private void sendSubHelp(CommandSender s, String sub) {
        s.sendMessage("§c引数が不足しています。");
        switch(sub) {
            case "set": s.sendMessage("§e使い方: /pvp set <チームの色>"); break;
            case "remove": s.sendMessage("§e使い方: /pvp remove <チームの色>"); break;
            case "ded_time":
                s.sendMessage("§e使い方: /pvp ded time <秒数> §7- 待機時間を設定(デフォ3)");
                break;
            case "lives_mode":
                s.sendMessage("§e使い方: /pvp lives mode <team|player>");
                s.sendMessage("§7 team - チームモードに変更");
                s.sendMessage("§7 player - 個人モードに変更");
                break;
            case "lives_set":
                s.sendMessage("§e使い方: /pvp lives set team <対象> <数>");
                s.sendMessage("§7- <対象> チームの色");
                s.sendMessage("§7- <数> 残機数");
                s.sendMessage("§e使い方: /pvp lives set player <対象> <数>");
                s.sendMessage("§7- <対象> プレイヤーの名前");
                s.sendMessage("§7- <数> 残機数");
                break;
            case "lives_onzero":
                s.sendMessage("§e使い方: /pvp lives onzero <spectator|wait>");
                s.sendMessage("§7 spectator - スペクテイターにする");
                s.sendMessage("§7 wait - ded地点で待機する");
                break;
        }
    }
}