package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ParticipantManager;
import com.yapimaru.plugin.utils.WCounter;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class LogCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final BukkitAudiences adventure;
    private final Logger logger;
    private final Map<String, Pattern> patterns = new HashMap<>();

    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LogCommand(YAPIMARU_Plugin plugin, ParticipantManager participantManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
        this.adventure = plugin.getAdventure();
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("add")) {
            adventure.sender(sender).sendMessage(Component.text("§a[YAPIMARU] デバッグモードでログ解析を開始します...", NamedTextColor.GREEN));
            runLogProcessing(sender);
            return true;
        } else if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            int count = participantManager.resetAllStats();
            adventure.sender(sender).sendMessage(Component.text("§a[YAPIMARU] " + count + "人の全参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
            return true;
        }
        adventure.sender(sender).sendMessage(Component.text("§c使用法: /log <add|reset>", NamedTextColor.RED));
        return false;
    }

    private void debug(CommandSender sender, String message) {
        logger.info("[LogCommand DEBUG] " + message);
        adventure.sender(sender).sendMessage(Component.text("§8[DEBUG] " + message, NamedTextColor.DARK_GRAY));
    }

    private void runLogProcessing(CommandSender sender) {
        new BukkitRunnable() {
            @Override
            public void run() {
                File participantInfoDir = new File(plugin.getDataFolder(), "Participant_Information");
                File logDir = new File(participantInfoDir, "log");
                if (!logDir.exists() && !logDir.mkdirs()) {
                    adventure.sender(sender).sendMessage(Component.text("§cログディレクトリの作成に失敗しました: " + logDir.getPath(), NamedTextColor.RED));
                    return;
                }
                File processedDir = new File(logDir, "processed");
                processedDir.mkdirs();
                File errorDir = new File(logDir, "error");
                errorDir.mkdirs();
                File memoryDumpsDir = new File(logDir, "memory_dumps");
                memoryDumpsDir.mkdirs();

                loadPatternsFromConfig();
                if (patterns.isEmpty()) {
                    adventure.sender(sender).sendMessage(Component.text("§cconfig.ymlからログパターンを読み込めませんでした。処理を中止します。", NamedTextColor.RED));
                    return;
                }

                File[] sourceLogFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".txt"));
                if (sourceLogFiles == null || sourceLogFiles.length == 0) {
                    adventure.sender(sender).sendMessage(Component.text("§e処理対象のログファイルが見つかりませんでした。", NamedTextColor.YELLOW));
                    return;
                }
                debug(sender, "発見したログファイル数: " + sourceLogFiles.length);

                List<LogSession> sessions = findSessions(sourceLogFiles);
                if (sessions.isEmpty()) {
                    adventure.sender(sender).sendMessage(Component.text("§e有効なサーバーセッションが見つかりませんでした。", NamedTextColor.YELLOW));
                    return;
                }
                debug(sender, "ログセッション数: " + sessions.size());

                AtomicInteger sessionCounter = new AtomicInteger(1);
                sessions.forEach(session -> {
                    int currentSessionNum = sessionCounter.getAndIncrement();
                    debug(sender, ">>>>> セッション " + currentSessionNum + " の処理開始 (ファイル: " + session.logFiles().stream().map(File::getName).collect(Collectors.joining(", ")) + ") <<<<<");

                    try {
                        debug(sender, "フェーズ1: プレイヤー情報収集 開始");
                        Map<UUID, String> unprocessedAccounts = collectPlayerInfo(session);
                        debug(sender, "フェーズ1: 完了。ユニークなアカウントを " + unprocessedAccounts.size() + " 件発見。");

                        debug(sender, "フェーズ2: データ統合 開始");
                        Map<String, ParticipantData> sessionParticipants = integrateData(unprocessedAccounts);
                        debug(sender, "フェーズ2: 完了。対象参加者 " + sessionParticipants.size() + " 名を特定。");

                        debug(sender, "フェーズ2.5: 統計リセット 開始");
                        sessionParticipants.values().forEach(ParticipantData::resetStatsForLog);
                        debug(sender, "フェーズ2.5: 完了。参加者 " + sessionParticipants.size() + " 名の統計をリセット。");

                        debug(sender, "フェーズ3: ログ再集計 開始");
                        recalculateStats(session, sessionParticipants, sender);
                        debug(sender, "フェーズ3: 完了。");

                        debug(sender, "フェーズ4: データ保存 開始");
                        sessionParticipants.values().forEach(participantManager::saveParticipant);
                        debug(sender, "フェーズ4: 完了。");

                        moveFiles(session.logFiles(), processedDir);
                        adventure.sender(sender).sendMessage(Component.text("§aセッション " + currentSessionNum + " の処理が正常に完了しました。", NamedTextColor.GREEN));

                    } catch (Exception e) {
                        adventure.sender(sender).sendMessage(Component.text("§cセッション " + currentSessionNum + " の処理中にエラーが発生しました。", NamedTextColor.RED));
                        logger.log(Level.SEVERE, "Error processing log session #" + currentSessionNum, e);
                        createErrorLog(session, e, errorDir);
                        moveFiles(session.logFiles(), errorDir);
                    }
                });

                adventure.sender(sender).sendMessage(Component.text("§a[YAPIMARU] すべてのログ処理が完了しました。", NamedTextColor.GREEN));
            }
        }.runTaskAsynchronously(this.plugin);
    }

    private void loadPatternsFromConfig() {
        patterns.clear();
        if (plugin.getConfig().isConfigurationSection("log-patterns")) {
            for (String key : plugin.getConfig().getConfigurationSection("log-patterns").getKeys(false)) {
                try {
                    patterns.put(key, Pattern.compile(plugin.getConfig().getString("log-patterns." + key)));
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to compile log pattern: " + key, e);
                }
            }
        }
    }

    private List<LogSession> findSessions(File[] logFiles) {
        List<File> sortedFiles = Arrays.stream(logFiles)
                .sorted(Comparator.comparing(this::parseDateFromFileName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(this::getLogIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        List<LogSession> sessions = new ArrayList<>();
        List<File> currentSessionFiles = new ArrayList<>();
        Pattern serverStartPattern = patterns.get("server-start");

        if (serverStartPattern == null) {
            if (!sortedFiles.isEmpty()) {
                sessions.add(new LogSession(sortedFiles, parseDateFromFileName(sortedFiles.get(0))));
            }
            return sessions;
        }

        for (File file : sortedFiles) {
            boolean startsNewSession = false;
            try (BufferedReader reader = getReaderForFile(file)) {
                if (reader.lines().anyMatch(line -> serverStartPattern.matcher(line).find())) {
                    startsNewSession = true;
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not read log file: " + file.getName(), e);
                continue;
            }

            if (startsNewSession && !currentSessionFiles.isEmpty()) {
                sessions.add(new LogSession(new ArrayList<>(currentSessionFiles), parseDateFromFileName(currentSessionFiles.get(0))));
                currentSessionFiles.clear();
            }
            currentSessionFiles.add(file);
        }

        if (!currentSessionFiles.isEmpty()) {
            sessions.add(new LogSession(new ArrayList<>(currentSessionFiles), parseDateFromFileName(currentSessionFiles.get(0))));
        }
        return sessions;
    }

    private Map<UUID, String> collectPlayerInfo(LogSession session) throws IOException {
        Map<UUID, String> accounts = new HashMap<>();
        Pattern uuidPattern = patterns.get("uuid");
        Pattern floodgatePattern = patterns.get("floodgate-uuid");

        for (File logFile : session.logFiles()) {
            try (BufferedReader reader = getReaderForFile(logFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (uuidPattern != null) {
                        Matcher m = uuidPattern.matcher(line);
                        if (m.find()) {
                            accounts.putIfAbsent(UUID.fromString(m.group(2)), m.group(1));
                            continue;
                        }
                    }
                    if (floodgatePattern != null) {
                        Matcher m = floodgatePattern.matcher(line);
                        if (m.find()) {
                            accounts.putIfAbsent(UUID.fromString(m.group(2)), m.group(1));
                        }
                    }
                }
            }
        }
        return accounts;
    }

    private Map<String, ParticipantData> integrateData(Map<UUID, String> unprocessedAccounts) {
        Map<String, ParticipantData> sessionParticipants = new HashMap<>();
        unprocessedAccounts.forEach((uuid, name) -> {
            ParticipantData data = participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
            if (data != null) {
                sessionParticipants.putIfAbsent(data.getParticipantId(), data);
            }
        });
        return sessionParticipants;
    }

    private void recalculateStats(LogSession session, Map<String, ParticipantData> sessionParticipants, CommandSender sender) throws IOException {
        List<LogEvent> events = new ArrayList<>();
        Pattern timePattern = patterns.get("log-line");
        if (timePattern == null) {
            debug(sender, "エラー: 'log-line'パターンがconfig.ymlに見つかりません。");
            return;
        }

        LocalDate currentDate = session.date();
        LocalTime lastTime = LocalTime.MIN;
        long lineCount = 0;

        for (File logFile : session.logFiles()) {
            debug(sender, "ファイル " + logFile.getName() + " のイベント解析を開始...");
            try (BufferedReader reader = getReaderForFile(logFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    Matcher timeMatcher = timePattern.matcher(line);
                    if (timeMatcher.find()) {
                        try {
                            LocalTime currentTime = LocalTime.parse(timeMatcher.group(1), LOG_TIMESTAMP_FORMATTER);
                            if (currentTime.isBefore(lastTime)) {
                                currentDate = currentDate.plusDays(1);
                            }
                            lastTime = currentTime;
                            LocalDateTime timestamp = currentDate.atTime(currentTime);
                            parseEventFromLine(line, timestamp, events);
                        } catch (DateTimeParseException ignored) {}
                    }
                }
            }
        }
        debug(sender, "合計 " + lineCount + "行を読み込み、" + events.size() + "件のイベントを検出しました。");

        if (events.isEmpty()) {
            debug(sender, "警告: このセッションで有効なイベントが一件も見つかりませんでした。config.ymlの正規表現が正しいか確認してください。");
            return;
        }

        events.sort(Comparator.comparing(LogEvent::timestamp));

        Map<String, LocalDateTime> sessionStartTimes = new HashMap<>();
        events.forEach(event -> findParticipantByName(sessionParticipants, event.playerName()).ifPresent(pData -> {
            switch (event.type()) {
                case LOGIN -> handleLoginLogic(pData, event.timestamp(), sessionStartTimes);
                case LOGOUT -> handleLogoutLogic(pData, event.timestamp(), sessionStartTimes);
                case DEATH -> {
                    pData.incrementStat("total_deaths", 1);
                    logger.info("[LogCommand DEBUG] DEATH イベント: " + pData.getParticipantId() + " の total_deaths をインクリメント。");
                }
                case CHAT -> {
                    pData.incrementStat("total_chats", 1);
                    pData.incrementStat("w_count", WCounter.countW(event.message()));
                    logger.info("[LogCommand DEBUG] CHAT イベント: " + pData.getParticipantId() + " の total_chats と w_count をインクリメント。");
                }
            }
        }));
    }

    private void handleLoginLogic(ParticipantData data, LocalDateTime loginTime, Map<String, LocalDateTime> sessionStartTimes) {
        if (data.isOnline()) return;
        LocalDateTime lastQuit = data.getLastQuitTimeAsDate();
        boolean isNewSession = lastQuit == null || Duration.between(lastQuit, loginTime).toMinutes() >= 10;

        if (isNewSession) {
            data.incrementStat("total_joins", 1);
            data.addHistoryEvent("join", loginTime);
            logger.info("[LogCommand DEBUG] LOGIN イベント (新規): " + data.getParticipantId() + " の total_joins をインクリメント。");
        } else {
            logger.info("[LogCommand DEBUG] LOGIN イベント (継続): " + data.getParticipantId());
        }
        sessionStartTimes.put(data.getParticipantId(), loginTime);
        data.setOnlineStatus(true);
    }

    private void handleLogoutLogic(ParticipantData data, LocalDateTime logoutTime, Map<String, LocalDateTime> sessionStartTimes) {
        if (!data.isOnline()) return;
        LocalDateTime startTime = sessionStartTimes.remove(data.getParticipantId());
        if (startTime != null) {
            long duration = Duration.between(startTime, logoutTime).getSeconds();
            if (duration > 0) {
                data.addPlaytime(duration);
                data.addPlaytimeToHistory(duration);
                logger.info("[LogCommand DEBUG] LOGOUT イベント: " + data.getParticipantId() + " のプレイ時間 " + duration + "秒を加算。");
            }
        }
        data.setLastQuitTime(logoutTime);
        data.setOnlineStatus(false);
    }

    private void parseEventFromLine(String line, LocalDateTime timestamp, List<LogEvent> events) {
        for (EventType type : EventType.values()) {
            Pattern p = patterns.get(type.patternKey);
            if (p != null) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    events.add(new LogEvent(timestamp, type, m.group(1).trim(), m.groupCount() > 1 ? m.group(2) : ""));
                    return;
                }
            }
        }
    }

    private Optional<ParticipantData> findParticipantByName(Map<String, ParticipantData> participants, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        for (ParticipantData pData : participants.values()) {
            if (pData.getBaseName().equalsIgnoreCase(name)) return Optional.of(pData);
            String linkedName = pData.getLinkedName();
            if (linkedName != null && !linkedName.isEmpty() && linkedName.equalsIgnoreCase(name)) return Optional.of(pData);
            if (pData.getAccounts().values().stream().anyMatch(acc -> acc.getName().equalsIgnoreCase(name))) return Optional.of(pData);
            if (pData.getDisplayName().equalsIgnoreCase(name)) return Optional.of(pData);
        }
        return Optional.empty();
    }

    private LocalDate parseDateFromFileName(File file) {
        Pattern pattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher m = pattern.matcher(file.getName());
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(1));
            } catch (DateTimeParseException e) {
                logger.warning("Could not parse date from filename: " + file.getName());
            }
        }
        return LocalDate.ofEpochDay(file.lastModified() / (24 * 60 * 60 * 1000));
    }

    private Integer getLogIndex(File file) {
        Matcher m = Pattern.compile("-(\\d+)\\.log").matcher(file.getName());
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private BufferedReader getReaderForFile(File file) throws IOException {
        InputStream is = new FileInputStream(file);
        if (file.getName().endsWith(".gz")) {
            is = new GZIPInputStream(is);
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    private void moveFiles(List<File> files, File destDir) {
        for (File file : files) {
            try {
                if (file.exists()) {
                    Files.move(file.toPath(), destDir.toPath().resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to move log file: " + file.getName() + " to " + destDir.getPath(), e);
            }
        }
    }

    private void createErrorLog(LogSession session, Exception error, File errorDir) {
        String errorFileName = "error_" + session.date().toString() + "_" + System.currentTimeMillis() + ".txt";
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(errorDir, errorFileName)))) {
            writer.println("Error occurred at: " + LocalDateTime.now());
            writer.println("Associated log files: " + session.logFiles().stream().map(File::getName).collect(Collectors.joining(", ")));
            writer.println("\n--- Error Message ---");
            writer.println(error.toString());
            writer.println("\n--- Stack Trace ---");
            error.printStackTrace(writer);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not write the error log file.", e);
        }
    }

    private void saveMmFile(File mmFile, Map<String, ParticipantData> participants, Map<UUID, String> unprocessed) {
        // This is a debug utility, so we don't need to be too fancy.
    }

    private record LogSession(List<File> logFiles, LocalDate date) {}
    private record LogEvent(LocalDateTime timestamp, EventType type, String playerName, String message) implements Comparable<LogEvent> {
        @Override
        public int compareTo(LogEvent other) { return this.timestamp.compareTo(other.timestamp); }
    }
    private enum EventType {
        LOGIN("join"), LOGOUT("left-game"), DEATH("death"), CHAT("chat");
        final String patternKey;
        EventType(String key) { this.patternKey = key; }
    }
}