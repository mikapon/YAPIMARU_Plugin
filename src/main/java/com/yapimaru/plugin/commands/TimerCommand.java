package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.TimerManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;

public class TimerCommand implements CommandExecutor {
    private final YAPIMARU_Plugin plugin;
    private final TimerManager timerManager;
    private final BukkitAudiences adventure;

    public TimerCommand(YAPIMARU_Plugin plugin, TimerManager timerManager) {
        this.plugin = plugin;
        this.timerManager = timerManager;
        this.adventure = plugin.getAdventure();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, "main");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("on") || sub.equals("off")) {
            timerManager.setFeatureEnabled(sub.equals("on"), sender);
            return true;
        }

        if (!timerManager.isFeatureEnabled()) {
            adventure.sender(sender).sendMessage(Component.text("タイマー機能は現在無効です。/timer on で有効にしてください。", NamedTextColor.RED));
            return true;
        }

        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "set":
                if (subArgs.length < 1) { sendSubHelp(sender, "set"); return true; }
                timerManager.setTime(sender, subArgs[0]);
                break;
            case "start":
                timerManager.start(sender);
                break;
            case "pause":
                timerManager.pause(sender);
                break;
            case "restart":
                timerManager.resume(sender);
                break;
            case "stop":
                timerManager.stop(sender);
                break;
            case "add":
                if (subArgs.length < 1) { sendSubHelp(sender, "add"); return true; }
                timerManager.addTime(sender, subArgs[0]);
                break;
            case "mode":
                if (subArgs.length < 1) { sendSubHelp(sender, "mode"); return true; }
                timerManager.setMode(sender, subArgs[0]);
                break;
            case "display":
                if (subArgs.length < 1) { sendSubHelp(sender, "display"); return true; }
                timerManager.setDisplay(sender, subArgs[0]);
                break;
            case "onend":
                handleOnEndCommand(sender, subArgs);
                break;
            case "prestart":
                if (subArgs.length < 1) { sendSubHelp(sender, "prestart"); return true; }
                timerManager.setPreStartSeconds(sender, subArgs[0]);
                break;
            case "reset":
                timerManager.reset(sender);
                break;
            default:
                sendHelp(sender, "main");
        }
        return true;
    }

    private void handleOnEndCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, "onend");
            return;
        }

        String action = args[0].toLowerCase();
        if ("add".equals(action)) {
            if (args.length < 4) {
                if (args.length > 1) {
                    if (args[1].equalsIgnoreCase("msg")) sendSubHelp(sender, "onend_add_msg");
                    else if (args[1].equalsIgnoreCase("cmd")) sendSubHelp(sender, "onend_add_cmd");
                } else {
                    sender.sendMessage("§e使い方: /timer onend add <msg|cmd> ...");
                }
                return;
            }
            String type = args[1].toLowerCase();
            try {
                int priority = Integer.parseInt(args[2]);
                if (priority < 0) { sender.sendMessage("優先度は0以上の数字で入力してください。"); return; }
                String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                if (timerManager.addOnEndAction(sender, type, priority, value)) {
                    sender.sendMessage("終了時アクションを追加しました。");
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("優先度は数字で入力してください。");
            }

        } else if ("remove".equals(action)) {
            if (args.length < 3) {
                if (args.length > 1) {
                    if (args[1].equalsIgnoreCase("msg")) sendSubHelp(sender, "onend_remove_msg");
                    else if (args[1].equalsIgnoreCase("cmd")) sendSubHelp(sender, "onend_remove_cmd");
                } else {
                    sender.sendMessage("§e使い方: /timer onend remove <msg|cmd> ...");
                }
                return;
            }
            String removeType = args[1].toLowerCase();
            try {
                int priority = Integer.parseInt(args[2]);
                if (timerManager.removeOnEndAction(sender, removeType, priority)) {
                    sender.sendMessage("優先度" + priority + "のアクションを削除しました。");
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("優先度は数字で入力してください。");
            }

        } else if ("list".equals(action)) {
            timerManager.listOnEndActions(sender, null);
        } else {
            sendHelp(sender, "onend");
        }
    }

    private void sendHelp(CommandSender s, String category) {
        s.sendMessage("§6--- Timer Command Help ---");
        switch (category) {
            case "main" -> {
                s.sendMessage("§e/timer on|off §7- onで有効、offで無効化し設定リセット");
                s.sendMessage("§e/timer set <時間> §7- タイマーの時間を設定 (例: 1h30m10s)");
                s.sendMessage("§e/timer start §7- タイマーを開始");
                s.sendMessage("§e/timer stop §7- タイマーのストップ");
                s.sendMessage("§e/timer pause|restart §7- 一時停止/再開");
                s.sendMessage("§e/timer add <時間> §7- 指定した時間を追加(<時間>例1h20m31s)");
                s.sendMessage("§e/timer mode <countdown|countup> §7- タイマー/ストップウォッチ切替");
                s.sendMessage("§e/timer display <actionbar|bossbar|title|off> §7- タイマーの表示形式を変更");
                s.sendMessage("§e/timer onend <add|list|remove> §7- タイマー終了後の動作の設定");
                s.sendMessage("§e/timer prestart <秒数> §7- タイマーの開始前カウントを設定(デフォ値3)");
                s.sendMessage("§e/timer reset §7- 全ての設定をリセット");
            }
            case "onend" -> {
                s.sendMessage("§e/timer onend add <msg|cmd> <優先度> <値> §7- アクション追加");
                s.sendMessage("§e/timer onend remove <msg|cmd> <優先度> §7- アクション削除");
                s.sendMessage("§e/timer onend list §7- アクション一覧表示");
            }
        }
    }

    private void sendSubHelp(CommandSender s, String sub) {
        switch (sub) {
            case "onend_add_msg":
                s.sendMessage("§e/timer onend add msg <優先度> <値>");
                s.sendMessage("§7- <優先度>0以上の数字で数字の少ない順に実行");
                s.sendMessage("§7- <値>チャットに流したい文章を記入");
                break;
            case "onend_add_cmd":
                s.sendMessage("§e/timer onend add cmd <優先度> <値>");
                s.sendMessage("§7- <優先度>0以上の数字で数字の少ない順に実行");
                s.sendMessage("§7- <値>コマンドを実行したい内容を記載");
                s.sendMessage("§7 例: give @p stone");
                break;
            case "onend_remove_msg":
                s.sendMessage("§e/timer onend remove msg <優先度>");
                s.sendMessage("§7- 指定優先度のメッセージを削除");
                break;
            case "onend_remove_cmd":
                s.sendMessage("§e/timer onend remove cmd <優先度>");
                s.sendMessage("§7- 指定優先度のコマンドを削除");
                break;
            default: s.sendMessage("§c引数が不足しています。"); break;
        }
    }
}
