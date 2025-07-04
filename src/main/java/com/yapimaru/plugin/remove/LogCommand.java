package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
// ★★★ エラー箇所を修正 ★★★
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

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

    private static final Pattern JOIN_PATTERN = Pattern.compile("\\] (\\.?[a-zA-Z0-9_]{2,16})(?:\\[.+])? (joined the game|logged in with entity|がマッチングしました)");
    private static final Pattern LOST_CONNECTION_PATTERN = Pattern.compile("\\] (\\.?[a-zA-Z0-9_]{2,16}) lost connection:.*");
    private static final Pattern LEFT_GAME_PATTERN = Pattern.compile("\\] (\\.?[a-zA-Z0-9_]{2,16}) (left the game|が退出しました)");

    private static final Pattern DEATH_PATTERN = Pattern.compile(
            "(\\.?[a-zA-Z0-9_]{2,16}) (was squashed by a falling anvil|was shot by.*|was pricked to death|walked into a cactus.*|was squished too much|was squashed by.*|was roasted in dragon's breath|drowned|died from dehydration|was killed by even more magic|blew up|was blown up by.*|hit the ground too hard|was squashed by a falling block|was skewered by a falling stalactite|was fireballed by.*|went off with a bang|experienced kinetic energy|froze to death|was frozen to death by.*|died|died because of.*|was killed|discovered the floor was lava|walked into the danger zone due to.*|was killed by.*using magic|went up in flames|walked into fire.*|suffocated in a wall|tried to swim in lava|was struck by lightning|was smashed by.*|was killed by magic|was slain by.*|burned to death|was burned to a crisp.*|fell out of the world|didn't want to live in the same world as.*|left the confines of this world|was obliterated by a sonically-charged shriek|was impaled on a stalagmite|starved to death|was stung to death|was poked to death by a sweet berry bush|was killed while trying to hurt.*|was pummeled by.*|was impaled by.*|withered away|was shot by a skull from.*|was killed by.*)"
    );
    private static final Pattern PHOTOGRAPHING_PATTERN = Pattern.compile(".*issued server command: /photographing on");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(\\S+)> (.*)");

    // Server state patterns
    private static final Pattern SERVER_START_PATTERN = Pattern.compile("Starting minecraft server version");
    private static final Pattern SERVER_STOP_PATTERN = Pattern.compile("Stopping server");

    private static final Set<String> NON_PLAYER_ENTITIES = new HashSet<>(Arrays.asList(
            "Villager", "Librarian", "Farmer", "Shepherd", "Nitwit", "Leatherworker",
            "Weaponsmith", "Fisherman", "You", "adomin"
    ));


    private record LogLine(LocalDateTime timestamp, String content, String raw) {}
    private record UnprocessedAccount(UUID uuid, String name) {}

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
        File errorDir = new File(logDir, "Error");
        File memoryDumpsDir = new File(logDir, "memory_dumps");

        try {
            if (!processedDir.exists()) Files.createDirectories(processedDir.toPath());
            if (!errorDir.exists()) Files.createDirectories(errorDir.toPath());
            if (!memoryDumpsDir.exists()) Files.createDirectories(memoryDumpsDir.toPath());
        } catch (IOException e) {
            handleError(sender, null, "作業ディレクトリの作成に失敗しました: " + e.getMessage(), e, null);
            return;
        }

        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GREEN));
            return;
        }


        List<List<File>> serverSessions = groupLogsByServerSession(logFiles);
        if (serverSessions.isEmpty()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("有効なサーバーセッションが見つかりませんでした。", NamedTextColor.YELLOW));
            return;
        }

        AtomicInteger sessionCounter = new AtomicInteger(1);
        int sessionsWithError = 0;

        for (List<File> sessionFiles : serverSessions) {
            if (sessionFiles.isEmpty()) continue;

            String sessionName = sessionFiles.get(0).getName();
            File mmFile = new File(memoryDumpsDir, "mm_" + sessionCounter.getAndIncrement() + ".yml");

            try {
                plugin.getAdventure().sender(sender).sendMessage(Component.text("サーバーセッション: " + sessionName + " の処理を開始 (" + sessionFiles.size() + "ファイル)...", NamedTextColor.AQUA));

                // === フェーズ1: 情報収集と中間ファイルの作成 ===
                List<UnprocessedAccount> unprocessedList = new ArrayList<>();
                for (File logFile : sessionFiles) {
                    try (BufferedReader reader = getReaderForFile(logFile)) {
                        reader.lines().forEach(line -> {
                            Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                            if (uuidMatcher.find()) {
                                unprocessedList.add(new UnprocessedAccount(UUID.fromString(uuidMatcher.group(2)), uuidMatcher.group(1)));
                            }
                            Matcher floodgateMatcher = FLOODGATE_UUID_PATTERN.matcher(line);
                            if (floodgateMatcher.find()) {
                                unprocessedList.add(new UnprocessedAccount(UUID.fromString(floodgateMatcher.group(2)), floodgateMatcher.group(1)));
                            }
                        });
                    }
                }

                YamlConfiguration mmConfig = new YamlConfiguration();
                Map<String, Object> accountsMap = new LinkedHashMap<>();
                unprocessedList.forEach(acc -> {
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("name", acc.name());
                    accountsMap.put(acc.uuid().toString(), details);
                });
                mmConfig.set("accounts", accountsMap);

                // === フェーズ2: 名寄せとデータ統合 ===
                List<ParticipantData> sessionParticipants = new ArrayList<>();
                Set<UnprocessedAccount> toProcess = new HashSet<>(unprocessedList);

                while(!toProcess.isEmpty()) {
                    UnprocessedAccount currentAccount = toProcess.iterator().next();
                    Optional<ParticipantData> existingDataOpt = participantManager.findParticipantByAnyName(currentAccount.name());
                    if (existingDataOpt.isEmpty()) {
                        existingDataOpt = Optional.ofNullable(participantManager.getParticipant(currentAccount.uuid()));
                    }

                    if(existingDataOpt.isPresent()){ // 既存参加者が見つかった
                        ParticipantData existingData = existingDataOpt.get();
                        sessionParticipants.add(existingData);

                        Set<UUID> allKnownUuids = existingData.getAssociatedUuids();
                        Set<String> allKnownNames = existingData.getAccounts().values().stream().map(ParticipantData.AccountInfo::getName).collect(Collectors.toSet());
                        allKnownNames.add(existingData.getBaseName());
                        allKnownNames.add(existingData.getLinkedName());

                        toProcess.removeIf(acc -> allKnownUuids.contains(acc.uuid()) || allKnownNames.stream().anyMatch(name -> name.equalsIgnoreCase(acc.name())));
                    } else { // 新規参加者
                        OfflinePlayer op = Bukkit.getOfflinePlayer(currentAccount.uuid());
                        ParticipantData newData = participantManager.findOrCreateParticipant(op);
                        sessionParticipants.add(newData);
                        toProcess.remove(currentAccount);
                    }
                }
                mmConfig.set("participants", sessionParticipants.stream().map(p -> p.getParticipantId()).collect(Collectors.toList()));


                // === フェーズ2.5: 統計データのリセット ===
                for (ParticipantData data : sessionParticipants) {
                    data.getStatistics().replaceAll((k, v) -> (v instanceof Long) ? 0L : 0);
                    data.getAccounts().values().forEach(acc -> acc.setOnline(false));
                }

                // === フェーズ3: ログからの再集計 ===
                Map<String, ParticipantData> participantDataMap = sessionParticipants.stream()
                        .collect(Collectors.toMap(ParticipantData::getParticipantId, p -> p));
                processSessionEvents(sessionFiles, participantDataMap, sender);

                mmConfig.set("processed_participants_data", sessionParticipants);
                mmConfig.save(mmFile);

                for(ParticipantData data : sessionParticipants){
                    participantManager.saveParticipant(data);
                }

                // ご要望によりファイルは移動させず、保持します。
                // moveFilesTo(sessionFiles, processedDir);

            } catch (Exception e) {
                handleError(sender, sessionName, "セッション処理中に予期せぬエラーが発生しました: " + e.getMessage(), e, sessionFiles);
                sessionsWithError++;
            }
        }

        participantManager.saveAllParticipantData();
        participantManager.reloadAllParticipants();

        plugin.getAdventure().sender(sender).sendMessage(Component.text("全てのログ処理が完了しました。", NamedTextColor.GREEN));
        if (sessionsWithError > 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text(sessionsWithError + " 件のセッションでエラーが検出されました。詳細はErrorフォルダを確認してください。", NamedTextColor.RED));
        }
    }


    private void processSessionEvents(List<File> sessionFiles, Map<String, ParticipantData> sessionParticipants, CommandSender sender) throws IOException {

        List<LogLine> allLines = new ArrayList<>();
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
                            allLines.add(new LogLine(currentDate.atTime(currentTime), line, line));
                            lastTime = currentTime;
                        } catch (DateTimeParseException ignored) {}
                    }
                }
            }
        }
        if(allLines.isEmpty()) return;

        Map<String, ParticipantData> onlineParticipants = new HashMap<>();

        for (LogLine log : allLines) {
            LocalDateTime timestamp = log.timestamp();
            String content = log.content();

            Matcher joinMatcher = JOIN_PATTERN.matcher(content);
            if (joinMatcher.find()) {
                findParticipantByName(sessionParticipants, joinMatcher.group(1)).ifPresent(p -> {
                    p.getAccounts().values().stream().filter(acc -> acc.getName().equalsIgnoreCase(joinMatcher.group(1))).findFirst().ifPresent(acc -> acc.setOnline(true));
                    if(!onlineParticipants.containsKey(p.getParticipantId())) {
                        onlineParticipants.put(p.getParticipantId(), p);
                        p.incrementStat("total_joins", 1);
                        p.addHistoryEvent("join", timestamp);
                    }
                });
                continue;
            }

            Matcher leftMatcher = LEFT_GAME_PATTERN.matcher(content);
            if (leftMatcher.find()) {
                findParticipantByName(sessionParticipants, leftMatcher.group(1)).ifPresent(p -> {
                    p.getAccounts().values().stream().filter(acc -> acc.getName().equalsIgnoreCase(leftMatcher.group(1))).findFirst().ifPresent(acc -> acc.setOnline(false));
                    if(!p.isOnline()){
                        onlineParticipants.remove(p.getParticipantId());
                    }
                });
                continue;
            }

            Matcher deathMatcher = DEATH_PATTERN.matcher(content);
            if (deathMatcher.find()) {
                String name = deathMatcher.group(1).trim();
                if(NON_PLAYER_ENTITIES.contains(name)) continue;
                findParticipantByName(sessionParticipants, name).ifPresent(p -> p.incrementStat("total_deaths", 1));
                continue;
            }

            if (PHOTOGRAPHING_PATTERN.matcher(content).find()) {
                onlineParticipants.values().forEach(p -> {
                    p.incrementStat("photoshoot_participations", 1);
                    p.addHistoryEvent("photoshoot", timestamp);
                });
                continue;
            }

            Matcher chatMatcher = CHAT_PATTERN.matcher(content);
            if (chatMatcher.find()) {
                findParticipantByName(sessionParticipants, chatMatcher.group(1)).ifPresent(p -> {
                    p.incrementStat("total_chats", 1);
                    p.incrementStat("w_count", participantManager.calculateWCount(chatMatcher.group(2)));
                });
            }
        }
    }

    private Optional<ParticipantData> findParticipantByName(Map<String, ParticipantData> participants, String name){
        return participants.values().stream()
                .filter(p -> p.getAccounts().values().stream().anyMatch(acc -> acc.getName().equalsIgnoreCase(name)))
                .findFirst();
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
                if (reader.lines().anyMatch(SERVER_START_PATTERN.asPredicate())) {
                    isStart = true;
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not read log file: " + currentFile.getName(), e);
                continue;
            }

            if (isStart) {
                if (!currentSessionFiles.isEmpty()) {
                    sessions.add(new ArrayList<>(currentSessionFiles));
                }
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
        return LocalDate.now();
    }

    private void handleError(CommandSender sender, String sessionName, String message, Exception e, List<File> filesToMove) {
        logger.log(Level.SEVERE, message, e);
        if(sender != null) plugin.getAdventure().sender(sender).sendMessage(Component.text(message, NamedTextColor.RED));

        if(filesToMove != null && !filesToMove.isEmpty()) {
            File errorDir = new File(new File(plugin.getDataFolder(), "Participant_Information"), "log/Error");
            if (!errorDir.exists()) errorDir.mkdirs();

            String errorSessionName = (sessionName != null) ? sessionName : "unknown_session";
            File errorReasonFile = new File(errorDir, errorSessionName + "_error.txt");
            try(PrintWriter writer = new PrintWriter(errorReasonFile)){
                writer.println("Error processing session: " + errorSessionName);
                writer.println("Reason: " + message);
                e.printStackTrace(writer);
            } catch (IOException ioException) {
                logger.log(Level.SEVERE, "Could not write error reason file.", ioException);
            }
            // ご要望によりファイルは移動させず、保持します。
            // moveFilesTo(filesToMove, errorDir);
        }
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