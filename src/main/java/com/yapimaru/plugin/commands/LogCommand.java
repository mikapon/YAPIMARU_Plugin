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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class LogCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final BukkitAudiences adventure;
    private final Map<String, Pattern> patterns = new HashMap<>();

    // ★★★ エラーの原因となっていたこの変数を再追加しました ★★★
    private final DateTimeFormatter logFileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");


    public LogCommand(YAPIMARU_Plugin plugin, ParticipantManager participantManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
        this.adventure = plugin.getAdventure();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("add")) {
            adventure.sender(sender).sendMessage(Component.text("§a[YAPIMARU] ログファイルの解析をバックグラウンドで開始します...", NamedTextColor.GREEN));
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

    private void runLogProcessing(CommandSender sender) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // II-1. 処理開始と準備
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

                // II-2. ログファイルのセッション化
                List<LogSession> sessions = findSessions(sourceLogFiles);
                if (sessions.isEmpty()) {
                    adventure.sender(sender).sendMessage(Component.text("§e有効なサーバーセッションが見つかりませんでした。", NamedTextColor.YELLOW));
                    return;
                }
                adventure.sender(sender).sendMessage(Component.text("§a" + sessions.size() + "件のログセッションを検出しました。処理を開始します...", NamedTextColor.GREEN));

                // II-3. セッションごとのループ処理
                AtomicInteger sessionCounter = new AtomicInteger(1);
                sessions.forEach(session -> {
                    int currentSessionNum = sessionCounter.getAndIncrement();
                    File mmFile = new File(memoryDumpsDir, "mm_" + currentSessionNum + ".yml");

                    adventure.sender(sender).sendMessage(Component.text("§b[YAPIMARU] セッション " + currentSessionNum + "/" + sessions.size() + " の処理を開始...", NamedTextColor.AQUA));

                    try {
                        // フェーズ1: 情報収集
                        adventure.sender(sender).sendMessage(Component.text("§7 -> フェーズ1: プレイヤー情報を収集中...", NamedTextColor.GRAY));
                        Map<UUID, String> unprocessedAccounts = collectPlayerInfo(session);

                        // フェーズ2: 名寄せとデータ統合
                        adventure.sender(sender).sendMessage(Component.text("§7 -> フェーズ2: 既存データと統合中...", NamedTextColor.GRAY));
                        Map<String, ParticipantData> sessionParticipants = integrateData(unprocessedAccounts);

                        // フェーズ2.5: 統計データのリセット
                        adventure.sender(sender).sendMessage(Component.text("§7 -> フェーズ2.5: 統計データをリセット中...", NamedTextColor.GRAY));
                        sessionParticipants.values().forEach(ParticipantData::resetStatsForLog);

                        // 中間ファイルにデバッグ情報を保存
                        saveMmFile(mmFile, sessionParticipants, unprocessedAccounts);

                        // フェーズ3: ログからの再集計
                        adventure.sender(sender).sendMessage(Component.text("§7 -> フェーズ3: ログを再集計中...", NamedTextColor.GRAY));
                        recalculateStats(session, sessionParticipants);

                        // フェーズ4: 永続データへのマージと保存
                        adventure.sender(sender).sendMessage(Component.text("§7 -> フェーズ4: 最終データを保存中...", NamedTextColor.GRAY));
                        sessionParticipants.values().forEach(participantManager::addOrUpdateDataFromLog);

                        moveFiles(session.logFiles(), processedDir);
                        adventure.sender(sender).sendMessage(Component.text("§a[YAPIMARU] セッション " + currentSessionNum + " の処理が正常に完了しました。", NamedTextColor.GREEN));

                    } catch (Exception e) {
                        adventure.sender(sender).sendMessage(Component.text("§c[YAPIMARU] セッション " + currentSessionNum + " の処理中にエラーが発生しました。", NamedTextColor.RED));
                        plugin.getLogger().log(Level.SEVERE, "Error processing log session #" + currentSessionNum, e);
                        createErrorLog(session, e, errorDir);
                        moveFiles(session.logFiles(), errorDir);
                    } finally {
                        mmFile.delete();
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
                    plugin.getLogger().log(Level.SEVERE, "Failed to compile log pattern: " + key, e);
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
            plugin.getLogger().warning("server-start pattern not found. Treating all logs as one session.");
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
                plugin.getLogger().log(Level.WARNING, "Could not read log file: " + file.getName(), e);
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

    private void recalculateStats(LogSession session, Map<String, ParticipantData> sessionParticipants) throws IOException {
        List<LogEvent> events = new ArrayList<>();
        Pattern timePattern = patterns.get("log-line");
        if (timePattern == null) return;

        LocalDate currentDate = session.date();
        LocalTime lastTime = LocalTime.MIN;

        for (File logFile : session.logFiles()) {
            try (BufferedReader reader = getReaderForFile(logFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
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
        events.sort(Comparator.comparing(LogEvent::timestamp));

        Map<String, LocalDateTime> sessionStartTimes = new HashMap<>();
        events.forEach(event -> findParticipantByName(sessionParticipants, event.playerName()).ifPresent(pData -> {
            switch (event.type()) {
                case LOGIN -> handleLoginLogic(pData, event.timestamp(), sessionStartTimes);
                case LOGOUT -> handleLogoutLogic(pData, event.timestamp(), sessionStartTimes);
                case DEATH -> pData.incrementStat("total_deaths", 1);
                case CHAT -> {
                    pData.incrementStat("total_chats", 1);
                    pData.incrementStat("w_count", WCounter.countW(event.message()));
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
                    return; // 1行は1イベントと仮定
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
                return LocalDate.parse(m.group(1), logFileDateFormatter);
            } catch (DateTimeParseException e) {
                plugin.getLogger().warning("Could not parse date from filename: " + file.getName());
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
                plugin.getLogger().log(Level.SEVERE, "Failed to move log file: " + file.getName() + " to " + destDir.getPath(), e);
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
            plugin.getLogger().log(Level.SEVERE, "Could not write the error log file.", e);
        }
    }

    private void saveMmFile(File mmFile, Map<String, ParticipantData> participants, Map<UUID, String> unprocessed) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("phase1_unprocessed_accounts_found", unprocessed.size());
        config.set("phase2_integrated_participants_count", participants.size());
        config.set("phase3_final_participant_data", participants.values().stream().map(ParticipantData::toMap).collect(Collectors.toList()));
        try {
            config.save(mmFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save memory dump file: " + mmFile.getName(), e);
        }
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