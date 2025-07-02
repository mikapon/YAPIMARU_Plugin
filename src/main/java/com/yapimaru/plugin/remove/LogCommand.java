package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;

    // 正規表現パターン
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (\\S+) is ([0-9a-f\\-]+)");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(\\S+)\\[/.*\\] logged in");
    private static final Pattern DEATH_PATTERN = Pattern.compile("(\\S+) (was slain by|was shot by|drowned|blew up|fell|suffocated|starved|froze|died)");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(\\S+)> (.*)");

    public LogCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantManager = plugin.getParticipantManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage("このコマンドを使用する権限がありません。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "add":
                handleAddCommand(sender);
                break;
            case "reset":
                handleResetCommand(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleAddCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("古いログの統計情報への反映を開始します...", NamedTextColor.YELLOW));

        File logDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log");
        if (!logDir.exists() || !logDir.isDirectory()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + logDir.getPath() + " ディレクトリが見つかりません。", NamedTextColor.RED));
            return;
        }

        File processedDir = new File(logDir, "processed");
        if (!processedDir.exists()) {
            processedDir.mkdirs();
        }

        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log"));
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイル(.log)が見つかりませんでした。", NamedTextColor.GOLD));
            return;
        }

        int filesProcessed = 0;
        int linesProcessed = 0;

        for (File logFile : logFiles) {
            if (logFile.isDirectory()) continue;

            try {
                List<String> lines = Files.readAllLines(logFile.toPath());
                Map<String, UUID> nameToUuidMap = new HashMap<>();

                for (String line : lines) {
                    Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                    if (uuidMatcher.find()) {
                        nameToUuidMap.put(uuidMatcher.group(1), UUID.fromString(uuidMatcher.group(2)));
                    }
                }

                for (String line : lines) {
                    if (parseAndApplyLog(line, nameToUuidMap)) {
                        linesProcessed++;
                    }
                }

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
    }

    private void handleResetCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("全参加者の統計情報をリセットしています...", NamedTextColor.YELLOW));
        int count = participantManager.resetAllStats();
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }

    private boolean parseAndApplyLog(String line, Map<String, UUID> nameToUuidMap) {
        Matcher joinMatcher = JOIN_PATTERN.matcher(line);
        if (joinMatcher.find()) {
            String playerName = joinMatcher.group(1);
            UUID uuid = nameToUuidMap.get(playerName);
            if (uuid != null) {
                participantManager.incrementJoins(uuid);
                return true;
            }
        }

        Matcher deathMatcher = DEATH_PATTERN.matcher(line);
        if (deathMatcher.find()) {
            String playerName = deathMatcher.group(1);
            UUID uuid = nameToUuidMap.get(playerName);
            if (uuid != null) {
                participantManager.incrementDeaths(uuid);
                return true;
            }
        }

        Matcher chatMatcher = CHAT_PATTERN.matcher(line);
        if (chatMatcher.find()) {
            String playerName = chatMatcher.group(1);
            String message = chatMatcher.group(2);
            UUID uuid = nameToUuidMap.get(playerName);
            if (uuid != null) {
                participantManager.incrementChats(uuid);
                int w_count = StringUtils.countMatches(message, "w");
                if (w_count > 0) {
                    participantManager.incrementWCount(uuid, w_count);
                }
                return true;
            }
        }

        return false;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}