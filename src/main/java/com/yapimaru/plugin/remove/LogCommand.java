package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LogCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final Logger logger;

    private static final Pattern LOG_LINE_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})].*");
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (\\S+) is (\\S+)");

    private static final Pattern JOIN_PATTERN = Pattern.compile("(\\S+?) (joined the game|logged in with entity|がマッチングしました)");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(\\S+?) (left the game|lost connection: Disconnected|が退出しました)");

    private static final Pattern DEATH_PATTERN = Pattern.compile(
            "(\\S+?) (was squashed by a falling anvil|was shot by.*|was pricked to death|walked into a cactus.*|was squished too much|was squashed by.*|was roasted in dragon's breath|drowned|died from dehydration|was killed by even more magic|blew up|was blown up by.*|hit the ground too hard|was squashed by a falling block|was skewered by a falling stalactite|was fireballed by.*|went off with a bang|experienced kinetic energy|froze to death|was frozen to death by.*|died|died because of.*|was killed|discovered the floor was lava|walked into the danger zone due to.*|was killed by.*using magic|went up in flames|walked into fire.*|suffocated in a wall|tried to swim in lava|was struck by lightning|was smashed by.*|was killed by magic|was slain by.*|burned to death|was burned to a crisp.*|fell out of the world|didn't want to live in the same world as.*|left the confines of this world|was obliterated by a sonically-charged shriek|was impaled on a stalagmite|starved to death|was stung to death|was poked to death by a sweet berry bush|was killed while trying to hurt.*|was pummeled by.*|was impaled by.*|withered away|was shot by a skull from.*|was killed by.*)"
    );

    private static final Pattern CHAT_PATTERN = Pattern.compile("<(\\S+)> (.*)");

    private record LogLine(LocalDateTime timestamp, String content) {}
    private record ProcessResult(boolean hasError, List<String> errorLines) {}

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
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> handleAddCommand(sender));
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
        plugin.getAdventure().sender(sender).sendMessage(Component.text("古いログの統計情報への一括反映を開始します... (処理中はサーバーに負荷がかかる可能性があります)", NamedTextColor.YELLOW));

        File logDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log");
        File processedDir = new File(logDir, "processed");
        File errorDir = new File(logDir, "Error");

        try {
            if (!processedDir.exists()) Files.createDirectories(processedDir.toPath());
            if (!errorDir.exists()) Files.createDirectories(errorDir.toPath());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "作業ディレクトリの作成に失敗しました。", e);
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: 作業ディレクトリの作成に失敗しました。サーバーの権限を確認してください。", NamedTextColor.RED));
            return;
        }

        Map<String, List<File>> groupedLogFiles = groupLogFiles(logDir);
        if (groupedLogFiles.isEmpty()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GOLD));
            return;
        }

        // Sort the groups chronologically
        List<Map.Entry<String, List<File>>> sortedGroups = new ArrayList<>(groupedLogFiles.entrySet());
        sortedGroups.sort(Comparator.comparing(entry -> entry.getKey(), (key1, key2) -> {
            Pattern keyPattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d+)");
            Matcher m1 = keyPattern.matcher(key1);
            Matcher m2 = keyPattern.matcher(key2);

            if (m1.matches() && m2.matches()) {
                LocalDate date1 = LocalDate.parse(m1.group(1));
                int num1 = Integer.parseInt(m1.group(2));
                LocalDate date2 = LocalDate.parse(m2.group(1));
                int num2 = Integer.parseInt(m2.group(2));

                int dateCompare = date1.compareTo(date2);
                if (dateCompare != 0) {
                    return dateCompare;
                }
                return Integer.compare(num1, num2);
            }
            return key1.compareTo(key2); // Fallback for non-matching keys
        }));

        int totalFilesProcessed = 0;
        for (Map.Entry<String, List<File>> entry : sortedGroups) {
            String groupName = entry.getKey();
            List<File> fileGroup = entry.getValue();

            plugin.getAdventure().sender(sender).sendMessage(Component.text("ロググループ: " + groupName + " の処理を開始 (" + fileGroup.size() + "ファイル)...", NamedTextColor.GRAY));

            try {
                List<LogLine> allLines = new ArrayList<>();
                Map<String, UUID> nameToUuidMap = new HashMap<>();
                LocalDate baseDate = null;

                for (File logFile : fileGroup) {
                    List<String> lines = Files.readAllLines(logFile.toPath());
                    lines.forEach(line -> {
                        Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                        if (uuidMatcher.find()) {
                            try {
                                UUID uuid = UUID.fromString(uuidMatcher.group(2));
                                nameToUuidMap.put(uuidMatcher.group(1), uuid);
                            } catch (IllegalArgumentException ignored) {}
                        }
                    });

                    if (baseDate == null) {
                        baseDate = parseDateFromFileName(logFile.getName());
                    }

                    LocalTime lastTime = LocalTime.MIN;
                    LocalDate currentDate = baseDate;

                    for (String line : lines) {
                        Matcher timeMatcher = LOG_LINE_PATTERN.matcher(line);
                        if (timeMatcher.find()) {
                            try {
                                LocalTime currentTime = LocalTime.parse(timeMatcher.group(1));
                                if (currentTime.isBefore(lastTime)) {
                                    currentDate = currentDate.plusDays(1);
                                }
                                allLines.add(new LogLine(currentDate.atTime(currentTime), line));
                                lastTime = currentTime;
                            } catch (DateTimeParseException ignored) {}
                        }
                    }
                }

                if (allLines.isEmpty()) {
                    moveFilesTo(fileGroup, processedDir);
                    continue;
                }

                ProcessResult result = processLogGroup(allLines, nameToUuidMap);

                if (result.hasError()) {
                    moveFilesTo(fileGroup, errorDir);
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("グループ " + groupName + " に不整合があったためErrorフォルダに移動しました。", NamedTextColor.RED));
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("原因となった可能性のある行:", NamedTextColor.RED));
                    for (String errorLine : result.errorLines()) {
                        plugin.getAdventure().sender(sender).sendMessage(Component.text("  " + errorLine, NamedTextColor.YELLOW));
                    }
                } else {
                    moveFilesTo(fileGroup, processedDir);
                    totalFilesProcessed += fileGroup.size();
                }

            } catch (IOException e) {
                logger.log(Level.SEVERE, "ロググループ " + groupName + " の処理中にエラーが発生しました。", e);
                plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + groupName + " の処理に失敗しました。次のグループに進みます。", NamedTextColor.RED));
            }
        }

        if (totalFilesProcessed > 0) {
            plugin.getAdventure().sender(sender).sendMessage(
                    Component.text("全てのログ処理が完了しました！", NamedTextColor.GREEN)
                            .append(Component.newline())
                            .append(Component.text("合計処理ファイル数: " + totalFilesProcessed + "件", NamedTextColor.AQUA))
            );
        } else {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("正常に処理されたロググループはありませんでした。", NamedTextColor.GOLD));
        }
    }


    private ProcessResult processLogGroup(List<LogLine> allLines, Map<String, UUID> nameToUuidMap) {
        Map<UUID, LocalDateTime> openSessions = new HashMap<>();
        List<String> errorLines = new ArrayList<>();
        Map<UUID, LocalDateTime> lastJoinEventTimes = new HashMap<>();
        Map<UUID, LocalDateTime> lastLeaveEventTimes = new HashMap<>();

        for (LogLine log : allLines) {
            Matcher joinMatcher = JOIN_PATTERN.matcher(log.content());
            if (joinMatcher.find()) {
                UUID uuid = nameToUuidMap.get(joinMatcher.group(1));
                if (uuid != null) {
                    LocalDateTime lastEvent = lastJoinEventTimes.get(uuid);
                    if (lastEvent == null || Duration.between(lastEvent, log.timestamp()).getSeconds() > 10) {
                        participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                        participantManager.incrementJoins(uuid);
                        openSessions.putIfAbsent(uuid, log.timestamp());
                        lastJoinEventTimes.put(uuid, log.timestamp());
                    }
                }
                continue;
            }

            Matcher leaveMatcher = LEAVE_PATTERN.matcher(log.content());
            if (leaveMatcher.find()) {
                UUID uuid = nameToUuidMap.get(leaveMatcher.group(1));
                if (uuid != null) {
                    LocalDateTime lastEvent = lastLeaveEventTimes.get(uuid);
                    if (lastEvent == null || Duration.between(lastEvent, log.timestamp()).getSeconds() > 10) {
                        if (openSessions.containsKey(uuid)) {
                            LocalDateTime joinTime = openSessions.remove(uuid);
                            long playTime = Duration.between(joinTime, log.timestamp()).getSeconds();
                            if (playTime > 0) participantManager.addPlaytime(uuid, playTime);
                        } else {
                            errorLines.add(log.content());
                        }
                        lastLeaveEventTimes.put(uuid, log.timestamp());
                    }
                }
                continue;
            }

            Matcher deathMatcher = DEATH_PATTERN.matcher(log.content());
            if (deathMatcher.find()) {
                UUID uuid = nameToUuidMap.get(deathMatcher.group(1));
                if (uuid != null) {
                    participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                    participantManager.incrementDeaths(uuid);
                }
                continue;
            }

            Matcher chatMatcher = CHAT_PATTERN.matcher(log.content());
            if (chatMatcher.find()) {
                UUID uuid = nameToUuidMap.get(chatMatcher.group(1));
                if (uuid != null) {
                    participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                    participantManager.incrementChats(uuid);
                    int wCount = StringUtils.countMatches(chatMatcher.group(2), 'w');
                    if (wCount > 0) participantManager.incrementWCount(uuid, wCount);
                }
            }
        }

        if (!allLines.isEmpty()) {
            LocalDateTime lastLogTimestamp = allLines.get(allLines.size() - 1).timestamp();
            for (Map.Entry<UUID, LocalDateTime> session : openSessions.entrySet()) {
                long playTime = Duration.between(session.getValue(), lastLogTimestamp).getSeconds();
                if (playTime > 0) {
                    participantManager.addPlaytime(session.getKey(), playTime);
                }
            }
        }

        return new ProcessResult(!errorLines.isEmpty(), errorLines);
    }


    private Map<String, List<File>> groupLogFiles(File logDir) {
        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));
        if (logFiles == null) {
            return Collections.emptyMap();
        }

        Pattern groupPattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d+)(_\\d+)?\\.log(\\.gz)?");

        Map<String, List<File>> groupedFiles = new HashMap<>();
        for (File file : logFiles) {
            if (!file.isFile()) continue;
            Matcher matcher = groupPattern.matcher(file.getName());
            if (matcher.matches()) {
                String groupKey = matcher.group(1);
                groupedFiles.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(file);
            } else {
                groupedFiles.computeIfAbsent(file.getName(), k -> new ArrayList<>()).add(file);
            }
        }

        groupedFiles.values().forEach(list -> list.sort(Comparator.comparing(File::getName)));

        return groupedFiles;
    }


    private LocalDate parseDateFromFileName(String fileName) {
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher matcher = datePattern.matcher(fileName);
        if (matcher.find()) {
            return LocalDate.parse(matcher.group(1));
        }
        return LocalDate.now(); // Fallback
    }

    private void moveFilesTo(List<File> files, File targetDir) {
        for (File file : files) {
            try {
                Files.move(file.toPath(), targetDir.toPath().resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "ログファイルの移動に失敗: " + file.getName(), e);
            }
        }
    }

    private void handleResetCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("全参加者の統計情報をリセットしています...", NamedTextColor.YELLOW));
        int count = participantManager.resetAllStats();
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }


    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを全て読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}