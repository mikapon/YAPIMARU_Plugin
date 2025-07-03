package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class LogCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final Logger logger;

    // Log line patterns
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})].*");
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (\\S+) is (\\S+)");
    private static final Pattern FLOODGATE_UUID_PATTERN = Pattern.compile("\\[floodgate] Floodgate.+? ([\\w.-]+) でログインしているプレイヤーが参加しました \\(UUID: ([0-9a-f\\-]+)");

    private static final Pattern JOIN_PATTERN = Pattern.compile("(\\S+?) (joined the game|logged in with entity|がマッチングしました)");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("(\\S+?) (left the game|lost connection: Disconnected|が退出しました)");
    private static final Pattern DEATH_PATTERN = Pattern.compile(
            "(\\S+?) (was squashed by a falling anvil|was shot by.*|was pricked to death|walked into a cactus.*|was squished too much|was squashed by.*|was roasted in dragon's breath|drowned|died from dehydration|was killed by even more magic|blew up|was blown up by.*|hit the ground too hard|was squashed by a falling block|was skewered by a falling stalactite|was fireballed by.*|went off with a bang|experienced kinetic energy|froze to death|was frozen to death by.*|died|died because of.*|was killed|discovered the floor was lava|walked into the danger zone due to.*|was killed by.*using magic|went up in flames|walked into fire.*|suffocated in a wall|tried to swim in lava|was struck by lightning|was smashed by.*|was killed by magic|was slain by.*|burned to death|was burned to a crisp.*|fell out of the world|didn't want to live in the same world as.*|left the confines of this world|was obliterated by a sonically-charged shriek|was impaled on a stalagmite|starved to death|was stung to death|was poked to death by a sweet berry bush|was killed while trying to hurt.*|was pummeled by.*|was impaled by.*|withered away|was shot by a skull from.*|was killed by.*)"
    );
    private static final Pattern PHOTOGRAPHING_PATTERN = Pattern.compile(".*issued server command: /photographing on");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(\\S+)> (.*)");

    // Server state patterns
    private static final Pattern SERVER_START_PATTERN = Pattern.compile("Starting minecraft server version");
    private static final Pattern SERVER_STOP_PATTERN = Pattern.compile("Stopping server");


    private record LogLine(LocalDateTime timestamp, String content) {}
    private record ProcessResult(boolean hasError, List<String> errorLines) {}
    private record SessionInfo(LocalDateTime firstLogin, Set<UUID> onlineUuids) {}

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

        List<List<File>> serverSessions = groupLogsByServerSession(logDir);
        if (serverSessions.isEmpty()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GOLD));
            return;
        }

        Map<String, UUID> nameToUuidFromYml = buildComprehensiveNameMap();

        int totalFilesProcessed = 0;
        for (List<File> sessionFiles : serverSessions) {
            if (sessionFiles.isEmpty()) continue;

            String sessionName = sessionFiles.get(0).getName() + " から " + sessionFiles.get(sessionFiles.size() - 1).getName();
            plugin.getAdventure().sender(sender).sendMessage(Component.text("サーバーセッション: " + sessionName + " の処理を開始 (" + sessionFiles.size() + "ファイル)...", NamedTextColor.GRAY));

            try {
                List<LogLine> allLines = new ArrayList<>();
                Map<String, UUID> nameToUuidFromLog = new HashMap<>();

                // First pass: build name-UUID map from logs
                for (File logFile : sessionFiles) {
                    try (BufferedReader reader = getReaderForFile(logFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                            if (uuidMatcher.find()) {
                                try {
                                    UUID uuid = UUID.fromString(uuidMatcher.group(2));
                                    nameToUuidFromLog.put(uuidMatcher.group(1), uuid);
                                } catch (IllegalArgumentException ignored) {}
                            }
                            Matcher floodgateMatcher = FLOODGATE_UUID_PATTERN.matcher(line);
                            if (floodgateMatcher.find()) {
                                try {
                                    UUID uuid = UUID.fromString(floodgateMatcher.group(2));
                                    nameToUuidFromLog.put(floodgateMatcher.group(1), uuid);
                                } catch (IllegalArgumentException ignored) {}
                            }
                        }
                    }
                }

                // Second pass: read all lines with correct timestamps
                LocalDate currentDate = null;
                LocalTime lastTime = LocalTime.MIN;
                for (File logFile : sessionFiles) {
                    if (currentDate == null) {
                        currentDate = parseDateFromFileName(logFile.getName());
                    }

                    try (BufferedReader reader = getReaderForFile(logFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
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
                }

                Map<String, UUID> finalNameToUuidMap = new HashMap<>(nameToUuidFromYml);
                finalNameToUuidMap.putAll(nameToUuidFromLog);

                if (allLines.isEmpty()) {
                    moveFilesTo(sessionFiles, processedDir);
                    continue;
                }

                ProcessResult result = processSessionGroup(allLines, finalNameToUuidMap);

                if (result.hasError()) {
                    moveFilesTo(sessionFiles, errorDir);
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("セッション " + sessionName + " に不整合があったためErrorフォルダに移動しました。", NamedTextColor.RED));

                    File errorLogFile = new File(errorDir, "error_details.txt");
                    List<String> outputLines = new ArrayList<>();
                    outputLines.add("Error in session: " + sessionName);
                    outputLines.add("Reason: Inconsistent session data (e.g., player left without joining).");
                    outputLines.add("Problematic lines:");
                    outputLines.addAll(result.errorLines().stream().map(s -> "  " + s).toList());
                    outputLines.add(""); // Add a blank line for separation

                    try {
                        Files.write(errorLogFile.toPath(), outputLines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        plugin.getAdventure().sender(sender).sendMessage(Component.text("詳細は " + errorLogFile.getPath() + " に追記しました。", NamedTextColor.GRAY));
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, "エラーログファイルの書き込みに失敗: " + errorLogFile.getName(), e);
                    }

                } else {
                    moveFilesTo(sessionFiles, processedDir);
                    totalFilesProcessed += sessionFiles.size();
                }

            } catch (IOException e) {
                logger.log(Level.SEVERE, "セッション " + sessionName + " の処理中にエラーが発生しました。", e);
                plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + sessionName + " の処理に失敗しました。次のセッションに進みます。", NamedTextColor.RED));
            }
        }

        if (totalFilesProcessed > 0) {
            plugin.getAdventure().sender(sender).sendMessage(
                    Component.text("全てのログ処理が完了しました！", NamedTextColor.GREEN)
                            .append(Component.newline())
                            .append(Component.text("合計処理ファイル数: " + totalFilesProcessed + "件", NamedTextColor.AQUA))
            );
        } else {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("正常に処理されたログセッションはありませんでした。", NamedTextColor.GOLD));
        }
    }

    private Map<String, UUID> buildComprehensiveNameMap() {
        Map<String, UUID> nameMap = new HashMap<>();
        Stream.concat(
                participantManager.getActiveParticipants().stream(),
                participantManager.getDischargedParticipants().stream()
        ).forEach(data -> {
            UUID mainUuid = data.getAssociatedUuids().stream().findFirst().orElse(null);
            if (mainUuid == null) return;

            // Base name, linked name, display name
            if (data.getBaseName() != null && !data.getBaseName().isEmpty()) nameMap.put(data.getBaseName(), mainUuid);
            if (data.getLinkedName() != null && !data.getLinkedName().isEmpty()) nameMap.put(data.getLinkedName(), mainUuid);
            if (data.getDisplayName() != null && !data.getDisplayName().isEmpty()) nameMap.put(data.getDisplayName(), mainUuid);

            // All known past names from uuid-to-name map
            if (data.getUuidToNameMap() != null) {
                data.getUuidToNameMap().forEach((uuid, name) -> nameMap.put(name, uuid));
            }
        });
        return nameMap;
    }


    private ProcessResult processSessionGroup(List<LogLine> allLines, Map<String, UUID> nameToUuidMap) {
        Map<ParticipantData, SessionInfo> openSessions = new HashMap<>();
        List<String> errorLines = new ArrayList<>();
        Map<UUID, LocalDateTime> lastJoinEventTimes = new HashMap<>();
        Map<UUID, LocalDateTime> lastLeaveEventTimes = new HashMap<>();

        for (LogLine log : allLines) {
            Matcher joinMatcher = JOIN_PATTERN.matcher(log.content());
            if (joinMatcher.find()) {
                String name = joinMatcher.group(1);
                UUID uuid = nameToUuidMap.get(name);

                if (uuid != null) {
                    ParticipantData data = participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                    if(data == null) continue;

                    LocalDateTime lastEvent = lastJoinEventTimes.get(uuid);
                    if (lastEvent == null || Duration.between(lastEvent, log.timestamp()).getSeconds() > 10) {
                        SessionInfo session = openSessions.computeIfAbsent(data, k -> new SessionInfo(log.timestamp(), new HashSet<>()));
                        if(session.onlineUuids().isEmpty()) {
                            participantManager.incrementJoins(uuid);
                        }
                        session.onlineUuids().add(uuid);
                        lastJoinEventTimes.put(uuid, log.timestamp());
                    }
                }
                continue;
            }

            Matcher leaveMatcher = LEAVE_PATTERN.matcher(log.content());
            if (leaveMatcher.find()) {
                String name = leaveMatcher.group(1);
                UUID uuid = nameToUuidMap.get(name);
                if (uuid != null) {
                    ParticipantData data = participantManager.getParticipant(uuid);
                    if (data == null) continue;

                    LocalDateTime lastEvent = lastLeaveEventTimes.get(uuid);
                    if (lastEvent == null || Duration.between(lastEvent, log.timestamp()).getSeconds() > 10) {
                        SessionInfo session = openSessions.get(data);
                        if (session != null && session.onlineUuids().contains(uuid)) {
                            session.onlineUuids().remove(uuid);
                            if(session.onlineUuids().isEmpty()){
                                LocalDateTime joinTime = session.firstLogin();
                                long playTime = Duration.between(joinTime, log.timestamp()).getSeconds();
                                if (playTime > 0) participantManager.addPlaytime(uuid, playTime);
                                if (playTime >= 600) {
                                    participantManager.addJoinHistory(uuid, joinTime);
                                }
                                openSessions.remove(data);
                            }
                        } else {
                            if (data.isOnline()) {
                                logger.warning("Player " + data.getDisplayName() + " left without a join record in this session, but was marked as online. Treating as a session continuation.");
                                data.setOnline(false);
                                participantManager.saveParticipant(data);
                            } else {
                                errorLines.add(log.content());
                            }
                        }
                        lastLeaveEventTimes.put(uuid, log.timestamp());
                    }
                }
                continue;
            }

            Matcher deathMatcher = DEATH_PATTERN.matcher(log.content());
            if (deathMatcher.find()) {
                String name = deathMatcher.group(1);
                UUID uuid = nameToUuidMap.get(name);
                if (uuid != null) {
                    participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                    participantManager.incrementDeaths(uuid);
                }
                continue;
            }

            Matcher photoMatcher = PHOTOGRAPHING_PATTERN.matcher(log.content());
            if (photoMatcher.find()) {
                LocalDateTime timestamp = log.timestamp();
                openSessions.keySet().forEach(data -> {
                    data.getAssociatedUuids().forEach(uuid -> {
                        participantManager.incrementPhotoshootParticipations(uuid, timestamp);
                    });
                });
                continue;
            }

            Matcher chatMatcher = CHAT_PATTERN.matcher(log.content());
            if (chatMatcher.find()) {
                String name = chatMatcher.group(1);
                UUID uuid = nameToUuidMap.get(name);
                if (uuid != null) {
                    participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                    participantManager.incrementChats(uuid);
                    int wCount = StringUtils.countMatches(chatMatcher.group(2), 'w');
                    if (wCount > 0) participantManager.incrementWCount(uuid, wCount);
                }
            }
        }

        // Finalize sessions for players who were online when the logs ended (e.g., server crash)
        if (!allLines.isEmpty()) {
            LocalDateTime lastLogTimestamp = allLines.get(allLines.size() - 1).timestamp();
            for (Map.Entry<ParticipantData, SessionInfo> entry : openSessions.entrySet()) {
                if (entry.getValue().onlineUuids().isEmpty()) continue;

                UUID representativeUuid = entry.getKey().getAssociatedUuids().stream().findFirst().orElse(null);
                if(representativeUuid == null) continue;

                LocalDateTime joinTime = entry.getValue().firstLogin();
                long playTime = Duration.between(joinTime, lastLogTimestamp).getSeconds();
                if (playTime > 0) {
                    participantManager.addPlaytime(representativeUuid, playTime);
                }
                if (playTime >= 600) {
                    participantManager.addJoinHistory(representativeUuid, joinTime);
                }
            }
        }

        return new ProcessResult(!errorLines.isEmpty(), errorLines);
    }

    private List<List<File>> groupLogsByServerSession(File logDir) {
        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));
        if (logFiles == null || logFiles.length == 0) {
            return Collections.emptyList();
        }
        Arrays.sort(logFiles, Comparator.comparing(File::getName));

        List<List<File>> sessions = new ArrayList<>();
        List<File> currentSessionFiles = new ArrayList<>();
        boolean inSession = false;

        for (File currentFile : logFiles) {
            boolean isStart = false;
            boolean isStop = false;

            try (BufferedReader reader = getReaderForFile(currentFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (SERVER_START_PATTERN.matcher(line).find()) {
                        isStart = true;
                    }
                    if (SERVER_STOP_PATTERN.matcher(line).find()) {
                        isStop = true;
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not read log file: " + currentFile.getName(), e);
                continue;
            }

            if (isStart) {
                if (inSession) {
                    // Previous session ended due to crash (new start found before stop)
                    if (!currentSessionFiles.isEmpty()) {
                        sessions.add(new ArrayList<>(currentSessionFiles));
                    }
                }
                currentSessionFiles.clear();
                currentSessionFiles.add(currentFile);
                inSession = true;
            } else if (inSession) {
                currentSessionFiles.add(currentFile);
            }

            if (isStop && inSession) {
                sessions.add(new ArrayList<>(currentSessionFiles));
                currentSessionFiles.clear();
                inSession = false;
            }
        }

        // Add the last session if it was still running at the end of all logs
        if (inSession && !currentSessionFiles.isEmpty()) {
            sessions.add(currentSessionFiles);
        }

        return sessions;
    }

    private BufferedReader getReaderForFile(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        InputStreamReader isr;
        if (file.getName().endsWith(".gz")) {
            isr = new InputStreamReader(new GZIPInputStream(fis), StandardCharsets.UTF_8);
        } else {
            isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
        }
        return new BufferedReader(isr);
    }

    private LocalDate parseDateFromFileName(String fileName) {
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher matcher = datePattern.matcher(fileName);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1));
            } catch (DateTimeParseException e) {
                logger.warning("Could not parse date from filename: " + fileName + ". Using today's date.");
            }
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