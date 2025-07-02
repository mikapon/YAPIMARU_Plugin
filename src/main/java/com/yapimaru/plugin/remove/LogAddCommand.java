package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class LogAddCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;

    public LogAddCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantManager = plugin.getParticipantManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage("このコマンドを使用する権限がありません。");
            return true;
        }

        plugin.getAdventure().sender(sender).sendMessage(Component.text("古いログの統計情報への反映を開始します...", NamedTextColor.YELLOW));

        File logDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log");
        if (!logDir.exists() || !logDir.isDirectory()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + logDir.getPath() + " ディレクトリが見つかりません。", NamedTextColor.RED));
            return true;
        }

        File processedDir = new File(logDir, "processed");
        if (!processedDir.exists()) {
            processedDir.mkdirs();
        }

        File[] logFiles = logDir.listFiles((dir, name) -> !name.equalsIgnoreCase("processed"));
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GOLD));
            return true;
        }

        int filesProcessed = 0;
        int linesProcessed = 0;

        for (File logFile : logFiles) {
            if (logFile.isDirectory()) continue;

            try {
                List<String> lines = Files.readAllLines(logFile.toPath());
                for (String line : lines) {
                    if (parseAndApplyLog(line)) {
                        linesProcessed++;
                    }
                }
                // 処理が終わったファイルをprocessedディレクトリに移動
                Files.move(logFile.toPath(), new File(processedDir, logFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                filesProcessed++;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "ログファイル " + logFile.getName() + " の読み込みまたは移動に失敗しました。", e);
            }
        }

        plugin.getAdventure().sender(sender).sendMessage(
                Component.text("ログの反映が完了しました！", NamedTextColor.GREEN)
                        .append(Component.newline())
                        .append(Component.text("処理ファイル数: " + filesProcessed + "件", NamedTextColor.AQUA))
                        .append(Component.newline())
                        .append(Component.text("処理ログ行数: " + linesProcessed + "件", NamedTextColor.AQUA))
        );

        return true;
    }

    private boolean parseAndApplyLog(String line) {
        String[] parts = line.split(" ");
        if (parts.length < 2) return false;

        try {
            UUID uuid = UUID.fromString(parts[0]);
            String action = parts[1].toUpperCase();

            // プレイヤーデータがなければ作成する
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            participantManager.findOrCreateParticipant(player);

            switch (action) {
                case "DEATH":
                    participantManager.incrementDeaths(uuid);
                    break;
                case "JOIN":
                    participantManager.incrementJoins(uuid);
                    break;
                case "PLAYTIME":
                    if (parts.length > 2) {
                        long seconds = Long.parseLong(parts[2]);
                        participantManager.addPlaytime(uuid, seconds);
                    }
                    break;
                case "CHAT":
                    participantManager.incrementChats(uuid);
                    break;
                case "W_COUNT":
                    if (parts.length > 2) {
                        int count = Integer.parseInt(parts[2]);
                        participantManager.incrementWCount(uuid, count);
                    }
                    break;
                case "PHOTOSHOOT":
                    participantManager.incrementPhotoshootParticipations(uuid);
                    break;
                default:
                    return false;
            }
            return true;
        } catch (IllegalArgumentException |ArrayIndexOutOfBoundsException e) {
            plugin.getLogger().warning("ログの解析に失敗しました: " + line);
            return false;
        }
    }
}