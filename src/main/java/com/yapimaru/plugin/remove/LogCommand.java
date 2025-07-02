package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.commons.lang3.StringUtils;
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
import java.util.Arrays;
import java.util.Comparator;
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

        // ★★★ 修正箇所 ★★★
        // .logで終わるファイルだけでなく、ディレクトリではない全てのファイルを対象にする
        File[] logFiles = logDir.listFiles(File::isFile);
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GOLD));
            return;
        }

        Arrays.sort(logFiles, Comparator.comparing(File::getName));
        File logFileToProcess = logFiles[0];

        int linesProcessed = 0;

        plugin.getAdventure().sender(sender).sendMessage(Component.text("ファイル: " + logFileToProcess.getName() + " の処理を開始...", NamedTextColor.GRAY));

        try {
            List<String> lines = Files.readAllLines(logFileToProcess.toPath());
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

            Files.move(logFileToProcess.toPath(), new File(processedDir, logFileToProcess.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "ログファイル " + logFileToProcess.getName() + " の読み込みまたは移動に失敗しました。", e);
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + logFileToProcess.getName() + " の処理に失敗しました。詳細はコンソールを確認してください。", NamedTextColor.RED));
            return;
        }

        int remainingFilesCount = 0;
        File[] remainingFiles = logDir.listFiles(File::isFile);
        if (remainingFiles != null) {
            remainingFilesCount = remainingFiles.length;
        }

        plugin.getAdventure().sender(sender).sendMessage(
                Component.text("ログファイル「" + logFileToProcess.getName() + "」の処理が完了しました！", NamedTextColor.GREEN)
                        .append(Component.newline())
                        .append(Component.text("処理ログ行数: " + linesProcessed + "件", NamedTextColor.AQUA))
                        .append(Component.newline())
                        .append(Component.text("残りファイル数: " + remainingFilesCount + "件", NamedTextColor.YELLOW))
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
                participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                participantManager.incrementJoins(uuid);
                return true;
            }
        }

        Matcher deathMatcher = DEATH_PATTERN.matcher(line);
        if (deathMatcher.find()) {
            String playerName = deathMatcher.group(1);
            UUID uuid = nameToUuidMap.get(playerName);
            if (uuid != null) {
                participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
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
                participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
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
        sender.sendMessage("§e/log add §7- ログファイルを1つ読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}