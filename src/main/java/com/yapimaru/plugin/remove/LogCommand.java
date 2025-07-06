package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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
import java.util.concurrent.ConcurrentHashMap;
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
    private final Logger logger;
    private final Map<String, Pattern> patterns = new HashMap<>();

    private static final Set<String> NON_PLAYER_ENTITIES = new HashSet<>(Arrays.asList(
            "Villager", "Librarian", "Farmer", "Shepherd", "Nitwit", "Leatherworker",
            "Weaponsmith", "Fisherman", "You", "adomin"
    ));

    private record LogLineWithSource(LocalDateTime timestamp, String content, int sourceIndex) implements Comparable<LogLineWithSource> {
        @Override
        public int compareTo(@NotNull LogLineWithSource other) {
            return this.timestamp.compareTo(other.timestamp);
        }
    }
    private record UnprocessedAccount(UUID uuid, String name) {}
    private final Map<String, LocalDateTime> participantSessionStartTimes = new ConcurrentHashMap<>();
    private final AtomicInteger mmFileCounter = new AtomicInteger(1);

    public LogCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantManager = plugin.getParticipantManager();
        this.logger = plugin.getLogger();
    }

    private void loadPatterns() {
        patterns.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("log-patterns");
        if (section == null) {
            logger.severe("Config.ymlに 'log-patterns' セクションが見つかりません。/log add 機能は動作しません。");
            return;
        }

        logger.info("Loading log patterns...");
        for (String key : section.getKeys(false)) {
            try {
                patterns.put(key, Pattern.compile(section.getString(key)));
                logger.info("  - Loaded pattern: " + key);
            } catch (Exception e) {
                logger.severe("正規表現パターンのコンパイルに失敗しました: " + key);
                e.printStackTrace();
            }
        }
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
                // 非同期で実行
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
        plugin.getAdventure().sender(sender).sendMessage(Component.text("古いログの統計情報への一括反映を開始します...", NamedTextColor.GREEN));

        // [フェーズ1] 準備段階
        plugin.getAdventure().sender(sender).sendMessage(Component.text("[1/5] 準備中です...", NamedTextColor.GRAY));
        loadPatterns();
        if (patterns.isEmpty()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("正規表現パターンの読み込みに失敗しました。処理を中止します。", NamedTextColor.RED));
            return;
        }

        File logDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log");
        File processedDir = new File(logDir, "processed");
        File errorDir = new File(logDir, "error");
        File memoryDumpsDir = new File(logDir, "memory_dumps");

        try {
            if (!processedDir.exists() && !processedDir.mkdirs()) logger.warning("Failed to create processed directory.");
            if (!errorDir.exists() && !errorDir.mkdirs()) logger.warning("Failed to create error directory.");
            if (!memoryDumpsDir.exists() && !memoryDumpsDir.mkdirs()) logger.warning("Failed to create memory_dumps directory.");
        } catch (SecurityException e) {
            handleError(sender, null, "作業ディレクトリの作成に失敗しました: " + e.getMessage(), e, null);
            return;
        }

        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".txt"));
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GREEN));
            return;
        }

        // [フェーズ2] サーバーセッションの特定
        plugin.getAdventure().sender(sender).sendMessage(Component.text("[2/5] ログファイルを解析し、サーバーセッションを特定しています...", NamedTextColor.GRAY));
        List<List<File>> serverSessions = groupLogsByServerSession(logFiles);
        if (serverSessions.isEmpty()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("有効なサーバーセッションが見つかりませんでした。", NamedTextColor.YELLOW));
            // 見つからなかったファイルはエラーに移動
            moveFilesTo(Arrays.asList(logFiles), errorDir);
            return;
        }
        plugin.getAdventure().sender(sender).sendMessage(Component.text("... " + serverSessions.size() + " 個のセッションが見つかりました。", NamedTextColor.GRAY));


        // [フェーズ3] セッションごとのデータ処理
        AtomicInteger sessionsWithError = new AtomicInteger(0);
        int totalSessions = serverSessions.size();
        int currentSessionNum = 1;

        for (List<File> sessionFiles : serverSessions) {
            if (sessionFiles.isEmpty()) continue;

            String sessionName = sessionFiles.get(0).getName().split("\\.log")[0];
            plugin.getAdventure().sender(sender).sendMessage(Component.text("[3/5] セッション " + (currentSessionNum++) + "/" + totalSessions + " (" + sessionName + ") の処理を開始...", NamedTextColor.AQUA));
            File mmFile = new File(memoryDumpsDir, "mm_" + sessionName + "_" + mmFileCounter.getAndIncrement() + ".yml");
            participantSessionStartTimes.clear();

            try {
                // フェーズ1: 情報収集と中間ファイルの作成
                Set<UnprocessedAccount> unprocessedAccounts = new HashSet<>();
                Set<String> processedNames = new HashSet<>();

                Pattern uuidPattern = patterns.get("uuid");
                Pattern floodgatePattern = patterns.get("floodgate-uuid");

                for (File logFile : sessionFiles) {
                    try (BufferedReader reader = getReaderForFile(logFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (uuidPattern != null) {
                                Matcher uuidMatcher = uuidPattern.matcher(line);
                                if (uuidMatcher.find()) {
                                    String name = uuidMatcher.group(1);
                                    // 重複を避ける
                                    if (processedNames.add(name.toLowerCase())) {
                                        unprocessedAccounts.add(new UnprocessedAccount(UUID.fromString(uuidMatcher.group(2)), name));
                                    }
                                    continue;
                                }
                            }
                            if (floodgatePattern != null) {
                                Matcher floodgateMatcher = floodgatePattern.matcher(line);
                                if (floodgateMatcher.find()) {
                                    String name = floodgateMatcher.group(1);
                                    if (processedNames.add(name.toLowerCase())) {
                                        unprocessedAccounts.add(new UnprocessedAccount(UUID.fromString(floodgateMatcher.group(2)), name));
                                    }
                                }
                            }
                        }
                    }
                }
                saveMmFile(mmFile, "phase1_unprocessed_accounts", unprocessedAccounts.stream().map(acc -> Map.of("name", acc.name(), "uuid", acc.uuid().toString())).collect(Collectors.toList()));

                if (unprocessedAccounts.isEmpty()) {
                    logger.warning("[YAPIMARU WARNING] セッション " + sessionName + " からアカウント情報を一件も抽出できませんでした。このセッションはスキップされます。");
                    moveFilesTo(sessionFiles, errorDir);
                    sessionsWithError.getAndIncrement();
                    continue;
                }

                // フェーズ2: 名寄せとデータ統合
                Map<String, ParticipantData> sessionParticipants = new HashMap<>();
                Set<UnprocessedAccount> accountsToProcess = new HashSet<>(unprocessedAccounts);

                while (!accountsToProcess.isEmpty()) {
                    UnprocessedAccount currentAccount = accountsToProcess.iterator().next();
                    accountsToProcess.remove(currentAccount);

                    ParticipantData pData = participantManager.findParticipantByAnyName(currentAccount.name())
                            .or(() -> Optional.ofNullable(participantManager.getParticipant(currentAccount.uuid())))
                            .orElseGet(() -> {
                                // 新規参加者作成
                                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(currentAccount.uuid());
                                return participantManager.findOrCreateParticipant(offlinePlayer);
                            });

                    pData.addAccount(currentAccount.uuid(), currentAccount.name());

                    // 関連アカウントをリストから削除
                    Set<UUID> relatedUuids = new HashSet<>(pData.getAssociatedUuids());
                    Set<String> relatedNames = pData.getAccounts().values().stream()
                            .map(info -> info.getName().toLowerCase())
                            .collect(Collectors.toSet());
                    accountsToProcess.removeIf(acc -> relatedUuids.contains(acc.uuid()) || relatedNames.contains(acc.name().toLowerCase()));

                    sessionParticipants.put(pData.getParticipantId(), pData);
                }
                saveMmFile(mmFile, "phase2_nayose_results", sessionParticipants.values().stream().map(ParticipantData::toMap).collect(Collectors.toList()));


                // フェーズ2.5: 統計データのリセット
                for(ParticipantData pData : sessionParticipants.values()){
                    pData.resetStatsForLog();
                }
                saveMmFile(mmFile, "phase2.5_reset_stats", sessionParticipants.values().stream().map(ParticipantData::toMap).collect(Collectors.toList()));


                // フェーズ3: ログからの再集計
                processSessionEvents(sessionFiles, sessionParticipants);
                saveMmFile(mmFile, "phase3_recalculation_results", sessionParticipants.values().stream().map(ParticipantData::toMap).collect(Collectors.toList()));

                // フェーズ4: 永続データへのマージ
                for (ParticipantData sessionData : sessionParticipants.values()) {
                    participantManager.addOrUpdateDataFromLog(sessionData);
                }

                plugin.getAdventure().sender(sender).sendMessage(Component.text("...セッション " + sessionName + " のデータを永続データに合算しました。", NamedTextColor.GRAY));

                moveFilesTo(sessionFiles, processedDir);

            } catch (Exception e) {
                sessionsWithError.getAndIncrement();
                handleError(sender, sessionName, "セッション処理中に予期せぬエラーが発生しました。", e, sessionFiles);
            }
        }

        // [フェーズ5] 完了処理
        plugin.getAdventure().sender(sender).sendMessage(Component.text("[4/5] 全ての参加者データをファイルに保存しています...", NamedTextColor.GRAY));
        participantManager.saveAllParticipantData();
        plugin.getAdventure().sender(sender).sendMessage(Component.text("[5/5] メモリ上のデータを同期しています...", NamedTextColor.GRAY));
        participantManager.reloadAllParticipants();

        plugin.getAdventure().sender(sender).sendMessage(Component.text("全てのログ処理が完了しました。", NamedTextColor.GREEN));
        if (sessionsWithError.get() > 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text(sessionsWithError + " 件のセッションでエラーが検出されました。詳細は`plugins/YAPIMARU_Plugin/Participant_Information/log/error`フォルダを確認してください。", NamedTextColor.RED));
        }
    }

    private void processSessionEvents(List<File> sessionFiles, Map<String, ParticipantData> sessionParticipants) throws IOException {
        List<BufferedReader> readers = new ArrayList<>();
        for (File file : sessionFiles) {
            readers.add(getReaderForFile(file));
        }

        PriorityQueue<LogLineWithSource> queue = new PriorityQueue<>();
        LocalDate currentDate = parseDateFromFileName(sessionFiles.get(0).getName());
        if (currentDate == null) currentDate = LocalDate.now();
        LocalTime lastTime = LocalTime.MIN;

        for (int i = 0; i < readers.size(); i++) {
            String line = readers.get(i).readLine();
            if (line != null) {
                Optional<LogLineWithSource> logLineOpt = parseLine(line, i, currentDate, lastTime);
                logLineOpt.ifPresent(queue::add);
            }
        }

        while (!queue.isEmpty()) {
            LogLineWithSource log = queue.poll();
            int sourceIndex = log.sourceIndex();

            // 日付またぎ処理
            if(log.timestamp().toLocalTime().isBefore(lastTime)){
                currentDate = currentDate.plusDays(1);
            }
            lastTime = log.timestamp().toLocalTime();
            LocalDateTime correctedTimestamp = currentDate.atTime(log.timestamp().toLocalTime());

            handleLogLine(log.content(), correctedTimestamp, sessionParticipants);

            String nextLine = readers.get(sourceIndex).readLine();
            if (nextLine != null) {
                Optional<LogLineWithSource> nextLogLineOpt = parseLine(nextLine, sourceIndex, currentDate, lastTime);
                nextLogLineOpt.ifPresent(queue::add);
            }
        }

        for (BufferedReader reader : readers) {
            try {
                reader.close();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to close BufferedReader.", e);
            }
        }
    }

    private Optional<LogLineWithSource> parseLine(String line, int sourceIndex, LocalDate currentDate, LocalTime lastTime) {
        Pattern linePattern = patterns.get("log-line");
        if(linePattern == null) return Optional.empty();

        Matcher timeMatcher = linePattern.matcher(line);
        if (timeMatcher.find()) {
            try {
                LocalTime currentTime = LocalTime.parse(timeMatcher.group(1));
                LocalDate lineDate = currentDate;
                // 日付またぎの判定
                if (currentTime.isBefore(lastTime)) {
                    lineDate = currentDate.plusDays(1);
                }
                return Optional.of(new LogLineWithSource(lineDate.atTime(currentTime), line, sourceIndex));
            } catch (DateTimeParseException ignored) {}
        }
        return Optional.empty();
    }

    private void handleLogLine(String content, LocalDateTime timestamp, Map<String, ParticipantData> sessionParticipants) {
        Pattern joinPattern = patterns.get("join");
        if (joinPattern != null) {
            Matcher joinMatcher = joinPattern.matcher(content);
            if (joinMatcher.find()) {
                String name = joinMatcher.group(1);
                findParticipantByName(sessionParticipants, name).ifPresent(p -> handleLoginLogic(p, timestamp));
                return;
            }
        }

        Pattern leftPattern = patterns.get("left-game");
        if(leftPattern != null) {
            Matcher leftMatcher = leftPattern.matcher(content);
            if (leftMatcher.find()) {
                String name = leftMatcher.group(1);
                findParticipantByName(sessionParticipants, name).ifPresent(p -> handleLogoutLogic(p, timestamp));
                return;
            }
        }

        Pattern deathPattern = patterns.get("death");
        if(deathPattern != null) {
            Matcher deathMatcher = deathPattern.matcher(content);
            if (deathMatcher.find()) {
                String name = deathMatcher.group(1).trim();
                if (NON_PLAYER_ENTITIES.contains(name)) return;
                findParticipantByName(sessionParticipants, name).ifPresent(p -> p.incrementStat("total_deaths", 1));
                return;
            }
        }

        Pattern photoPattern = patterns.get("photographing");
        if (photoPattern != null && photoPattern.matcher(content).find()) {
            sessionParticipants.values().stream().filter(ParticipantData::isOnline)
                    .forEach(p -> {
                        p.incrementStat("photoshoot_participations", 1);
                        p.addHistoryEvent("photoshoot", timestamp);
                    });
            return;
        }

        Pattern chatPattern = patterns.get("chat");
        if(chatPattern != null) {
            Matcher chatMatcher = chatPattern.matcher(content);
            if (chatMatcher.find()) {
                String playerName = chatMatcher.group(1);
                findParticipantByName(sessionParticipants, playerName).ifPresent(p -> {
                    p.incrementStat("total_chats", 1);
                    p.incrementStat("w_count", participantManager.calculateWCount(chatMatcher.group(2)));
                });
            }
        }
    }


    private Optional<ParticipantData> findParticipantByName(Map<String, ParticipantData> participants, String name) {
        return participants.values().stream()
                .filter(pData -> {
                    String lowerCaseName = name.toLowerCase();
                    // base_name or linked_name
                    if (pData.getBaseName().equalsIgnoreCase(lowerCaseName) || (pData.getLinkedName() != null && !pData.getLinkedName().isEmpty() && pData.getLinkedName().equalsIgnoreCase(lowerCaseName))) {
                        return true;
                    }
                    // account names
                    if (pData.getAccounts().values().stream().anyMatch(acc -> acc.getName() != null && acc.getName().equalsIgnoreCase(lowerCaseName))) {
                        return true;
                    }
                    // linked_name(base_name)
                    Pattern pattern = Pattern.compile("(.+)\\((.+)\\)");
                    Matcher matcher = pattern.matcher(pData.getDisplayName());
                    if (matcher.matches()) {
                        return matcher.group(1).equalsIgnoreCase(lowerCaseName) || matcher.group(2).equalsIgnoreCase(lowerCaseName);
                    }
                    return false;
                })
                .findFirst();
    }

    private void handleLoginLogic(ParticipantData data, LocalDateTime loginTime) {
        if (data == null) return;
        boolean wasParticipantOnline = data.isOnline();
        data.setOnlineStatus(true);
        data.getAccounts().values().forEach(acc -> acc.setOnline(true)); // 仮で全アカウントをオンラインにする

        if (!wasParticipantOnline) {
            LocalDateTime lastQuit = data.getLastQuitTimeAsDate();
            boolean isNewSession = true;
            if (lastQuit != null) {
                // 10分ルール
                if (Duration.between(lastQuit, loginTime).toMinutes() < 10) {
                    isNewSession = false;
                }
            }

            if (isNewSession) {
                data.incrementStat("total_joins", 1);
                data.addHistoryEvent("join", loginTime);
            }
            participantSessionStartTimes.put(data.getParticipantId(), loginTime);
        }
    }

    private void handleLogoutLogic(ParticipantData data, LocalDateTime logoutTime) {
        if (data == null || !data.isOnline()) return;
        data.setOnlineStatus(false);
        data.getAccounts().values().forEach(acc -> acc.setOnline(false));

        LocalDateTime startTime = participantSessionStartTimes.remove(data.getParticipantId());
        if (startTime != null) {
            long durationSeconds = Duration.between(startTime, logoutTime).getSeconds();
            if (durationSeconds > 0) {
                data.addPlaytime(durationSeconds);

                LocalDateTime lastQuit = data.getLastQuitTimeAsDate();
                boolean isContinuing = false;
                if(lastQuit != null){
                    // 10分ルール
                    if (Duration.between(lastQuit, startTime).toMinutes() < 10) {
                        isContinuing = true;
                    }
                }

                if (isContinuing) {
                    data.addTimeToLastPlaytime(durationSeconds);
                } else {
                    data.addPlaytimeToHistory(durationSeconds);
                }
            }
        }
        data.setLastQuitTime(logoutTime);
    }

    private List<List<File>> groupLogsByServerSession(File[] logFiles) {
        if (logFiles == null || logFiles.length == 0) return Collections.emptyList();
        Arrays.sort(logFiles, Comparator.comparing(this::getFileNameTimestamp).thenComparing(this::getFileNameIndex));
        List<List<File>> sessions = new ArrayList<>();
        List<File> currentSessionFiles = new ArrayList<>();

        Pattern serverStartPattern = patterns.get("server-start");
        Pattern serverStopPattern = patterns.get("server-stop");

        if (serverStartPattern == null || serverStopPattern == null) {
            logger.warning("'server-start' or 'server-stop' pattern not found. Treating all logs as one session.");
            sessions.add(Arrays.asList(logFiles));
            return sessions;
        }

        for (File currentFile : logFiles) {
            boolean startsNewSession = false;
            try (BufferedReader reader = getReaderForFile(currentFile)) {
                if (reader.lines().anyMatch(serverStartPattern.asPredicate())) {
                    startsNewSession = true;
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not read log file: " + currentFile.getName(), e);
                continue;
            }

            if (startsNewSession && !currentSessionFiles.isEmpty()) {
                sessions.add(new ArrayList<>(currentSessionFiles));
                currentSessionFiles.clear();
            }

            currentSessionFiles.add(currentFile);
        }
        if (!currentSessionFiles.isEmpty()) sessions.add(currentSessionFiles);
        return sessions;
    }

    private String getFileNameTimestamp(File file) {
        Matcher m = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(file.getName());
        return m.find() ? m.group(1) : "9999-12-31";
    }

    private int getFileNameIndex(File file) {
        Matcher m = Pattern.compile("-(\\d+)\\.log").matcher(file.getName());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private BufferedReader getReaderForFile(File file) throws IOException {
        InputStream is = new FileInputStream(file);
        String fileName = file.getName();
        // .txt は実際には .log ファイルなので、圧縮は考慮しない
        if (fileName.endsWith(".log.gz")) {
            is = new GZIPInputStream(is);
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    private LocalDate parseDateFromFileName(String fileName) {
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher matcher = datePattern.matcher(fileName);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1));
            } catch (DateTimeParseException e) { /* fallback */ }
        }
        return null;
    }

    private void saveMmFile(File mmFile, String key, Object data) {
        try {
            YamlConfiguration mmConfig = new YamlConfiguration();
            if (mmFile.exists()) {
                try (Reader reader = new InputStreamReader(new FileInputStream(mmFile), StandardCharsets.UTF_8)) {
                    mmConfig.load(reader);
                } catch(Exception e) {
                    logger.log(Level.WARNING, "Could not load existing mm file, creating new one.", e);
                    mmConfig = new YamlConfiguration();
                }
            }
            mmConfig.set(key, data);
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(mmFile), StandardCharsets.UTF_8)) {
                writer.write(mmConfig.saveToString());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not save debug mm file: " + mmFile.getName(), e);
        }
    }

    private void handleError(CommandSender sender, String sessionName, String message, Exception e, List<File> filesToMove) {
        logger.log(Level.SEVERE, "Error during /log add processing (session: " + (sessionName != null ? sessionName : "N/A") + "): " + message, e);
        if (sender instanceof Player || sender.getName().equals("CONSOLE")) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text(message + "詳細はコンソールとerrorフォルダを確認してください。", NamedTextColor.RED));
        }
        if (filesToMove != null && !filesToMove.isEmpty()) {
            File errorDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log/error");
            if (!errorDir.exists() && !errorDir.mkdirs()) {
                logger.warning("Could not create error directory at: " + errorDir.getPath());
            }
            String errorSessionName = (sessionName != null) ? sessionName.replaceAll("[^a-zA-Z0-9.-]", "_") : "unknown_session";
            File errorReasonFile = new File(errorDir, errorSessionName + "_error_reason.txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(errorReasonFile, true))) {
                writer.println("Error processing session: " + sessionName + " at " + LocalDateTime.now());
                writer.println("Reason: " + message);
                e.printStackTrace(writer);
            } catch (IOException ioException) {
                logger.log(Level.SEVERE, "Could not write error reason file.", ioException);
            }
            moveFilesTo(filesToMove, errorDir);
        }
    }

    private void moveFilesTo(List<File> files, File targetDir) {
        for (File file : files) {
            try {
                if (file.exists()) {
                    Files.move(file.toPath(), targetDir.toPath().resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "ログファイルの移動に失敗: " + file.getName() + " to " + targetDir.getPath(), e);
            }
        }
    }

    private void handleResetCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("全参加者の統計情報をリセットしています...", NamedTextColor.GREEN));
        int count = participantManager.resetAllStats();
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを全て読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}