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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull; // ★★★ 修正: NotNullをインポート

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    // Log line patterns
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})].*");
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (\\S+) is ([0-9a-f\\-]+)");
    private static final Pattern FLOODGATE_UUID_PATTERN = Pattern.compile("\\[floodgate] Floodgate.+? (\\S+) でログインしているプレイヤーが参加しました \\(UUID: ([0-9a-f\\-]+)");

    private static final Pattern JOIN_PATTERN = Pattern.compile("] (\\.?[a-zA-Z0-9_]{2,17})(?:\\[.+])? (joined the game|logged in with entity|がマッチングしました)");
    private static final Pattern LEFT_GAME_PATTERN = Pattern.compile("] (\\.?[a-zA-Z0-9_]{2,17}) (left the game|が退出しました)");

    private static final Pattern DEATH_PATTERN = Pattern.compile(
            "(\\.?[a-zA-Z0-9_]{2,17}) (was squashed by a falling anvil|was shot by.*|was pricked to death|walked into a cactus.*|was squished too much|was squashed by.*|was roasted in dragon's breath|drowned|died from dehydration|was killed by even more magic|blew up|was blown up by.*|hit the ground too hard|was squashed by a falling block|was skewered by a falling stalactite|was fireballed by.*|went off with a bang|experienced kinetic energy|froze to death|was frozen to death by.*|died|died because of.*|was killed|discovered the floor was lava|walked into the danger zone due to.*|was killed by.*using magic|went up in flames|walked into fire.*|suffocated in a wall|tried to swim in lava|was struck by lightning|was smashed by.*|was killed by magic|was slain by.*|burned to death|was burned to a crisp.*|fell out of the world|didn't want to live in the same world as.*|left the confines of this world|was obliterated by a sonically-charged shriek|was impaled on a stalagmite|starved to death|was stung to death|was poked to death by a sweet berry bush|was killed while trying to hurt.*|was pummeled by.*|was impaled by.*|withered away|was shot by a skull from.*|was killed by.*)"
    );
    private static final Pattern PHOTOGRAPHING_PATTERN = Pattern.compile(".*issued server command: /photographing on");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(\\S+)> (.*)");

    // Server state patterns
    private static final Pattern SERVER_START_PATTERN = Pattern.compile("Starting minecraft server version");

    private static final Set<String> NON_PLAYER_ENTITIES = new HashSet<>(Arrays.asList(
            "Villager", "Librarian", "Farmer", "Shepherd", "Nitwit", "Leatherworker",
            "Weaponsmith", "Fisherman", "You", "adomin"
    ));


    private record LogLine(LocalDateTime timestamp, String content) {}
    private record UnprocessedAccount(UUID uuid, String name) {}

    // These maps are temporary for the duration of a single /log add command execution
    private final Map<String, Boolean> isContinuingSession = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> participantSessionStartTimes = new ConcurrentHashMap<>();

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
        plugin.getAdventure().sender(sender).sendMessage(Component.text("古いログの統計情報への一括反映を開始します...", NamedTextColor.GREEN));

        File logDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log");
        File processedDir = new File(logDir, "processed");
        File errorDir = new File(logDir, "error");

        try {
            if (!processedDir.exists() && !processedDir.mkdirs()) logger.warning("Failed to create processed directory.");
            if (!errorDir.exists() && !errorDir.mkdirs()) logger.warning("Failed to create error directory.");
        } catch (SecurityException e) {
            handleError(sender, null, "作業ディレクトリの作成に失敗しました: " + e.getMessage(), e, null);
            return;
        }

        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".txt"));
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GREEN));
            return;
        }

        List<List<File>> serverSessions = groupLogsByServerSession(logFiles);
        if (serverSessions.isEmpty()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("有効なサーバーセッションが見つかりませんでした。", NamedTextColor.YELLOW));
            return;
        }

        AtomicInteger sessionsWithError = new AtomicInteger(0);

        for (List<File> sessionFiles : serverSessions) {
            if (sessionFiles.isEmpty()) continue;

            String sessionName = sessionFiles.get(0).getName();
            isContinuingSession.clear();
            participantSessionStartTimes.clear();

            try {
                plugin.getAdventure().sender(sender).sendMessage(Component.text("サーバーセッション: " + sessionName + " の処理を開始 (" + sessionFiles.size() + "ファイル)...", NamedTextColor.AQUA));

                // === Phase 1: Information Gathering ===
                Set<UnprocessedAccount> unprocessedAccounts = new HashSet<>();
                for (File logFile : sessionFiles) {
                    try (BufferedReader reader = getReaderForFile(logFile)) {
                        reader.lines().forEach(line -> {
                            Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                            if (uuidMatcher.find()) {
                                unprocessedAccounts.add(new UnprocessedAccount(UUID.fromString(uuidMatcher.group(2)), uuidMatcher.group(1)));
                            }
                            Matcher floodgateMatcher = FLOODGATE_UUID_PATTERN.matcher(line);
                            if (floodgateMatcher.find()) {
                                unprocessedAccounts.add(new UnprocessedAccount(UUID.fromString(floodgateMatcher.group(2)), floodgateMatcher.group(1)));
                            }
                        });
                    }
                }

                // === Phase 2: Name Aggregation (Nayose) ===
                Set<ParticipantData> sessionParticipants = new HashSet<>();
                Set<UnprocessedAccount> accountsToProcess = new HashSet<>(unprocessedAccounts);

                while (!accountsToProcess.isEmpty()) {
                    UnprocessedAccount currentAccount = accountsToProcess.iterator().next();
                    accountsToProcess.remove(currentAccount);

                    Optional<ParticipantData> existingDataOpt = participantManager.findParticipantByAnyName(currentAccount.name())
                            .or(() -> Optional.ofNullable(participantManager.getParticipant(currentAccount.uuid())));

                    ParticipantData pData;
                    if (existingDataOpt.isPresent()) {
                        pData = existingDataOpt.get();
                    } else {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(currentAccount.uuid());
                        pData = participantManager.findOrCreateParticipant(op);
                    }

                    pData.addAccount(currentAccount.uuid(), currentAccount.name());
                    sessionParticipants.add(pData);

                    // Remove all known aliases of this participant from the processing set
                    Set<UUID> allKnownUuids = new HashSet<>(pData.getAssociatedUuids());
                    Set<String> allKnownNames = pData.getAccounts().values().stream()
                            .map(ParticipantData.AccountInfo::getName).filter(Objects::nonNull).collect(Collectors.toSet());
                    if (pData.getBaseName() != null) allKnownNames.add(pData.getBaseName());
                    if (pData.getLinkedName() != null) allKnownNames.add(pData.getLinkedName());

                    accountsToProcess.removeIf(acc -> allKnownUuids.contains(acc.uuid()) || allKnownNames.stream().anyMatch(name -> name.equalsIgnoreCase(acc.name())));
                }


                // === Phase 2.5: Reset Stats for Recalculation ===
                for (ParticipantData data : sessionParticipants) {
                    data.resetStatsForLog();
                }

                // === Phase 3: Recalculate Stats from Logs ===
                processSessionEvents(sessionFiles, sessionParticipants, sessionsWithError.get() == 0); // isServerStart logic can be improved

                // === Phase 4: Save Data ===
                for (ParticipantData data : sessionParticipants) {
                    participantManager.saveParticipant(data);
                }

                moveFilesTo(sessionFiles, processedDir);

            } catch (Exception e) {
                sessionsWithError.getAndIncrement();
                handleError(sender, sessionName, "セッション処理中に予期せぬエラーが発生しました。", e, sessionFiles);
            }
        }

        participantManager.saveAllParticipantData();
        participantManager.reloadAllParticipants();

        plugin.getAdventure().sender(sender).sendMessage(Component.text("全てのログ処理が完了しました。", NamedTextColor.GREEN));
        if (sessionsWithError.get() > 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text(sessionsWithError + " 件のセッションでエラーが検出されました。詳細は`plugins/YAPIMARU_Plugin/Participant_Information/log/error`フォルダを確認してください。", NamedTextColor.RED));
        }
    }


    private void processSessionEvents(List<File> sessionFiles, Set<ParticipantData> sessionParticipants, boolean isServerStart) throws IOException {

        List<LogLine> allLines = new ArrayList<>();
        LocalDate currentDate = null;
        for (File logFile : sessionFiles) {
            LocalDate fileDate = parseDateFromFileName(logFile.getName());
            if (currentDate == null) {
                currentDate = fileDate;
            } else if (fileDate.isAfter(currentDate)) {
                currentDate = fileDate;
            }

            LocalTime lastTime = LocalTime.MIN;
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
        allLines.sort(Comparator.comparing(LogLine::timestamp));


        for (LogLine log : allLines) {
            LocalDateTime timestamp = log.timestamp();
            String content = log.content();

            Matcher joinMatcher = JOIN_PATTERN.matcher(content);
            if (joinMatcher.find()) {
                String name = joinMatcher.group(1);
                findParticipantByName(sessionParticipants, name)
                        .ifPresent(p -> handleLoginLogic(p, getUuidForName(p, name), isServerStart, timestamp));
                continue;
            }

            Matcher leftMatcher = LEFT_GAME_PATTERN.matcher(content);
            if (leftMatcher.find()) {
                String name = leftMatcher.group(1);
                findParticipantByName(sessionParticipants, name)
                        .ifPresent(p -> handleLogoutLogic(p, getUuidForName(p, name), timestamp));
                continue;
            }

            Matcher deathMatcher = DEATH_PATTERN.matcher(content);
            if (deathMatcher.find()) {
                String name = deathMatcher.group(1).trim();
                if (NON_PLAYER_ENTITIES.contains(name)) continue;
                findParticipantByName(sessionParticipants, name)
                        .ifPresent(p -> p.incrementStat("total_deaths", 1));
                continue;
            }

            if (PHOTOGRAPHING_PATTERN.matcher(content).find()) {
                sessionParticipants.stream().filter(ParticipantData::isOnline)
                        .forEach(p -> {
                            p.incrementStat("photoshoot_participations", 1);
                            p.addHistoryEvent("photoshoot", timestamp);
                        });
                continue;
            }

            Matcher chatMatcher = CHAT_PATTERN.matcher(content);
            if (chatMatcher.find()) {
                String playerName = chatMatcher.group(1);
                // In case display name has prefixes/suffixes, find the core name
                findParticipantByName(sessionParticipants, playerName).ifPresent(p -> {
                    p.incrementStat("total_chats", 1);
                    p.incrementStat("w_count", participantManager.calculateWCount(chatMatcher.group(2)));
                });
            }
        }
    }

    private Optional<ParticipantData> findParticipantByName(Set<ParticipantData> participants, String name) {
        // Exact match on any known name first
        for (ParticipantData pData : participants) {
            // base_name or linked_name
            if (pData.getBaseName() != null && pData.getBaseName().equalsIgnoreCase(name)) return Optional.of(pData);
            if (pData.getLinkedName() != null && pData.getLinkedName().equalsIgnoreCase(name)) return Optional.of(pData);
            // a specific account name
            for (ParticipantData.AccountInfo accountInfo : pData.getAccounts().values()) {
                if (accountInfo.getName() != null && accountInfo.getName().equalsIgnoreCase(name)) {
                    return Optional.of(pData);
                }
            }
        }
        // Fallback for display names like "LinkedName(BaseName)"
        for (ParticipantData pData : participants) {
            if (pData.getDisplayName().equalsIgnoreCase(name)) return Optional.of(pData);
        }
        return Optional.empty();
    }

    private UUID getUuidForName(ParticipantData p, String name) {
        return p.getAccounts().entrySet().stream()
                .filter(entry -> entry.getValue().getName() != null && entry.getValue().getName().equalsIgnoreCase(name))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(p.getAssociatedUuids().stream().findFirst().orElse(null));
    }

    private void handleLoginLogic(ParticipantData data, UUID uuid, boolean isServerStart, LocalDateTime loginTime) {
        if (data == null || uuid == null) return;
        boolean wasParticipantOnline = data.isOnline();
        data.getAccounts().computeIfPresent(uuid, (k, v) -> {
            v.setOnline(true);
            return v;
        });

        if (!wasParticipantOnline) {
            LocalDateTime lastQuit = data.getLastQuitTimeAsDate();

            boolean isNewSession = true;
            if (lastQuit != null && !isServerStart) {
                if (Duration.between(lastQuit, loginTime).toMinutes() < 10) {
                    isNewSession = false;
                }
            }

            if (isNewSession) {
                isContinuingSession.put(data.getParticipantId(), false);
                data.incrementStat("total_joins", 1);
                data.addHistoryEvent("join", loginTime);
            } else {
                isContinuingSession.put(data.getParticipantId(), true);
            }
            participantSessionStartTimes.put(data.getParticipantId(), loginTime);
        }
    }

    private void handleLogoutLogic(ParticipantData data, UUID uuid, LocalDateTime logoutTime) {
        if (data == null || uuid == null) return;
        data.getAccounts().computeIfPresent(uuid, (k, v) -> {
            v.setOnline(false);
            return v;
        });

        if (!data.isOnline()) {
            LocalDateTime startTime = participantSessionStartTimes.remove(data.getParticipantId());
            if (startTime != null) {
                long durationSeconds = Duration.between(startTime, logoutTime).getSeconds();
                if (durationSeconds > 0) {
                    data.addPlaytime(durationSeconds);
                    if (isContinuingSession.getOrDefault(data.getParticipantId(), false)) {
                        data.addTimeToLastPlaytime(durationSeconds);
                    } else {
                        data.addPlaytimeToHistory(durationSeconds);
                    }
                }
            }
            data.setLastQuitTime(logoutTime);
            isContinuingSession.remove(data.getParticipantId());
        }
    }

    private List<List<File>> groupLogsByServerSession(File[] logFiles) {
        if (logFiles == null || logFiles.length == 0) {
            return Collections.emptyList();
        }
        Arrays.sort(logFiles, Comparator.comparing(this::getFileNameTimestamp).thenComparing(this::getFileNameIndex));
        List<List<File>> sessions = new ArrayList<>();
        List<File> currentSessionFiles = new ArrayList<>();
        for (File currentFile : logFiles) {
            boolean isStart = false;
            try (BufferedReader reader = getReaderForFile(currentFile)) {
                isStart = reader.lines().anyMatch(SERVER_START_PATTERN.asPredicate());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not read log file: " + currentFile.getName(), e);
                continue;
            }
            if (isStart && !currentSessionFiles.isEmpty()) {
                sessions.add(new ArrayList<>(currentSessionFiles));
                currentSessionFiles.clear();
            }
            currentSessionFiles.add(currentFile);
        }
        if (!currentSessionFiles.isEmpty()) {
            sessions.add(currentSessionFiles);
        }
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
        FileInputStream fis = new FileInputStream(file);
        InputStream is;
        if (file.getName().endsWith(".gz")) {
            is = new GZIPInputStream(fis);
        } else {
            is = fis;
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
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
        return LocalDate.now();
    }

    private void handleError(CommandSender sender, String sessionName, String message, Exception e, List<File> filesToMove) {
        logger.log(Level.SEVERE, message, e);
        // ★★★ 修正: senderがPlayerの場合のみメッセージを送信
        if (sender instanceof Player) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text(message + "詳細はコンソールとerrorフォルダを確認してください。", NamedTextColor.RED));
        }

        if (filesToMove != null && !filesToMove.isEmpty()) {
            File errorDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log/error");
            if (!errorDir.exists() && !errorDir.mkdirs()) {
                logger.warning("Could not create error directory at: " + errorDir.getPath());
            }

            // Write error reason to a text file
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