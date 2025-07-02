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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final Logger logger;

    // --- 正規表現パターン ---
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})].*");
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (\\S+) is ([0-9a-f\\-]+)");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(\\S+) joined the game");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(\\S+) left the game");
    private static final Pattern DEATH_PATTERN = Pattern.compile("(\\S+) (was slain by|was shot by|drowned|blew up|fell|suffocated|starved|froze|died)");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(\\S+)> (.*)");

    // --- 内部クラス ---
    private enum EventType { JOIN, LEAVE }
    private record LogEvent(LocalDateTime timestamp, EventType type) {}

    public LogCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantManager = plugin.getParticipantManager();
        this.logger = plugin.getLogger();
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
        Path processedDirPath = new File(logDir, "processed").toPath();

        try {
            if (!Files.exists(processedDirPath)) {
                Files.createDirectories(processedDirPath);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "'processed' ディレクトリの作成に失敗しました。", e);
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: 'processed' ディレクトリの作成に失敗しました。サーバーの権限を確認してください。", NamedTextColor.RED));
            return;
        }

        File[] logFiles = logDir.listFiles(File::isFile);
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GOLD));
            return;
        }

        Arrays.sort(logFiles, Comparator.comparing(File::getName));
        File logFileToProcess = logFiles[0];

        int linesProcessed = 0;
        long totalPlaytimeAdded = 0;

        plugin.getAdventure().sender(sender).sendMessage(Component.text("ファイル: " + logFileToProcess.getName() + " の処理を開始...", NamedTextColor.GRAY));

        try {
            Path sourcePath = logFileToProcess.toPath();
            Path destPath = processedDirPath.resolve(logFileToProcess.getName());

            List<String> lines = Files.readAllLines(sourcePath);

            // 1. まずファイル内の全UUIDを収集
            Map<String, UUID> nameToUuidMap = new HashMap<>();
            for (String line : lines) {
                Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                if (uuidMatcher.find()) {
                    nameToUuidMap.put(uuidMatcher.group(1), UUID.fromString(uuidMatcher.group(2)));
                }
            }

            // 2. ログイン/ログアウト/その他イベントを収集
            Map<UUID, List<LogEvent>> eventsByPlayer = new HashMap<>();
            for (String line : lines) {
                if(parseAndApplySimpleEvents(line, nameToUuidMap)) {
                    linesProcessed++;
                }
                parseJoinLeaveEvents(line, nameToUuidMap, eventsByPlayer);
            }

            // 3. プレイ時間を計算して反映
            for (Map.Entry<UUID, List<LogEvent>> entry : eventsByPlayer.entrySet()) {
                UUID uuid = entry.getKey();
                List<LogEvent> events = entry.getValue();
                events.sort(Comparator.comparing(LogEvent::timestamp));

                long sessionPlaytime = 0;
                LocalDateTime joinTime = null;

                for (LogEvent event : events) {
                    if (event.type() == EventType.JOIN) {
                        if (joinTime == null) { // 新しいセッションの開始
                            joinTime = event.timestamp();
                        }
                    } else if (event.type() == EventType.LEAVE) {
                        if (joinTime != null) { // セッションの終了
                            sessionPlaytime += Duration.between(joinTime, event.timestamp()).getSeconds();
                            joinTime = null; // セッションをリセット
                        }
                    }
                }
                if (sessionPlaytime > 0) {
                    participantManager.addPlaytime(uuid, sessionPlaytime);
                    totalPlaytimeAdded += sessionPlaytime;
                }
            }

            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(sourcePath);

        } catch (IOException e) {
            logger.log(Level.SEVERE, "ログファイル " + logFileToProcess.getName() + " の読み込みまたは移動に失敗しました。", e);
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
                        .append(Component.text("単純イベント処理行数: " + linesProcessed + "件", NamedTextColor.AQUA))
                        .append(Component.newline())
                        .append(Component.text("追加された合計プレイ時間: " + (totalPlaytimeAdded / 3600) + "時間", NamedTextColor.AQUA))
                        .append(Component.newline())
                        .append(Component.text("残りファイル数: " + remainingFilesCount + "件", NamedTextColor.YELLOW))
        );
    }

    private void handleResetCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("全参加者の統計情報をリセットしています...", NamedTextColor.YELLOW));
        int count = participantManager.resetAllStats();
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }

    private void parseJoinLeaveEvents(String line, Map<String, UUID> nameToUuidMap, Map<UUID, List<LogEvent>> eventsByPlayer) {
        Matcher timeMatcher = LOG_LINE_PATTERN.matcher(line);
        if (!timeMatcher.find()) return;

        LocalDateTime timestamp = LocalDateTime.parse(timeMatcher.group(1), DateTimeFormatter.ISO_LOCAL_TIME);

        Matcher joinMatcher = JOIN_PATTERN.matcher(line);
        if (joinMatcher.find()) {
            String playerName = joinMatcher.group(1);
            UUID uuid = nameToUuidMap.get(playerName);
            if (uuid != null) {
                eventsByPlayer.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new LogEvent(timestamp, EventType.JOIN));
            }
            return;
        }

        Matcher leaveMatcher = LEAVE_PATTERN.matcher(line);
        if(leaveMatcher.find()) {
            String playerName = leaveMatcher.group(1);
            UUID uuid = nameToUuidMap.get(playerName);
            if(uuid != null) {
                eventsByPlayer.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new LogEvent(timestamp, EventType.LEAVE));
            }
        }
    }

    private boolean parseAndApplySimpleEvents(String line, Map<String, UUID> nameToUuidMap) {
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

        // 参加回数はJoin/Leaveイベントとは別でカウント
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

        return false;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを1つ読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}