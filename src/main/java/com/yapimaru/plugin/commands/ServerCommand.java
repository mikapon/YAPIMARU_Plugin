package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.TimerManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ServerCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final TimerManager timerManager;
    private final BukkitAudiences adventure;
    // ★★★ 追加 ★★★
    private final YmCommand ymCommand;

    // ★★★ 修正点: コンストラクタでYmCommandを受け取る ★★★
    public ServerCommand(YAPIMARU_Plugin plugin, TimerManager timerManager, YmCommand ymCommand) {
        this.plugin = plugin;
        this.timerManager = timerManager;
        this.adventure = plugin.getAdventure();
        this.ymCommand = ymCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        if (timerManager.isRunning()) {
            adventure.sender(sender).sendMessage(Component.text("既にタイマーが作動中です。/timer stop で停止してください。", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "off":
                setupServerStop(sender, "off");
                break;
            case "restart":
                setupServerStop(sender, "restart");
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void setupServerStop(CommandSender sender, String type) {
        timerManager.reset(sender);
        timerManager.setTime(sender, "5m");
        timerManager.setDisplay(sender, "title");

        List<String> subtitles;
        if ("off".equals(type)) {
            // ★★★ 修正点: 'off' の場合のみモードを変更する ★★★
            ymCommand.setServerMode("owner");
            adventure.sender(sender).sendMessage(Component.text("サーバーモードをオーナーモードに変更しました。", NamedTextColor.YELLOW));

            subtitles = List.of(
                    "メンテナンスを行うため",
                    "サーバーを停止します。",
                    "終了後にはdiscordにて",
                    "お知らせします。"
            );
            // 終了時のアクションを 'stop' にする（'restart'は再起動してしまうため）
            timerManager.clearOnEndActions();
            timerManager.addOnEndAction(sender, "cmd", 1, "stop");

        } else { // restart
            subtitles = List.of(
                    "アップデートを行うため",
                    "サーバーを再起動します。",
                    "1分程で終了します"
            );
            // 終了時のアクションを 'restart' にする
            timerManager.clearOnEndActions();
            timerManager.addOnEndAction(sender, "cmd", 1, "restart");
        }

        timerManager.setRotatingSubtitles(subtitles, 2);
        timerManager.start(sender);

        adventure.sender(sender).sendMessage(Component.text("サーバーの" + ("off".equals(type) ? "停止" : "再起動") + "シーケンスを開始しました。", NamedTextColor.GOLD));
    }

    private void sendHelp(CommandSender sender) {
        adventure.sender(sender).sendMessage(Component.text("§6--- Server Command Help ---"));
        adventure.sender(sender).sendMessage(Component.text("§e/server off §7- 5分後にサーバーを停止します。"));
        adventure.sender(sender).sendMessage(Component.text("§e/server restart §7- 5分後にサーバーを再起動します。"));
    }
}