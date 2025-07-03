package com.yapimaru.plugin.remove;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
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
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (\\S+) is ([0-9a-f\\-]+)");
    private static final Pattern FLOODGATE_UUID_PATTERN = Pattern.compile("\\[floodgate] Floodgate.+? (\\S+) でログインしているプレイヤーが参加しました \\(UUID: ([0-9a-f\\-]+)");

    private static final Pattern JOIN_PATTERN = Pattern.compile("\\] (\\.?[a-zA-Z0-9_]{2,16})(?:\\[.+])? (joined the game|logged in with entity|がマッチングしました)");
    private static final Pattern LOST_CONNECTION_PATTERN = Pattern.compile("\\] (\\.?[a-zA-Z0-9_]{2,16}) lost connection:.*");
    private static final Pattern LEFT_GAME_PATTERN = Pattern.compile("\\] (\\.?[a-zA-Z0-9_]{2,16}) (left the game|が退出しました)");
    private static final Pattern WHITELIST_KICK_PATTERN = Pattern.compile("You are not whitelisted on this server!");


    private static final Pattern DEATH_PATTERN = Pattern.compile(
            "(\\.?[a-zA-Z0-9_]{2,16}) (was squashed by a falling anvil|was shot by.*|was pricked to death|walked into a cactus.*|was squished too much|was squashed by.*|was roasted in dragon's breath|drowned|died from dehydration|was killed by even more magic|blew up|was blown up by.*|hit the ground too hard|was squashed by a falling block|was skewered by a falling stalactite|was fireballed by.*|went off with a bang|experienced kinetic energy|froze to death|was frozen to death by.*|died|died because of.*|was killed|discovered the floor was lava|walked into the danger zone due to.*|was killed by.*using magic|went up in flames|walked into fire.*|suffocated in a wall|tried to swim in lava|was struck by lightning|was smashed by.*|was killed by magic|was slain by.*|burned to death|was burned to a crisp.*|fell out of the world|didn't want to live in the same world as.*|left the confines of this world|was obliterated by a sonically-charged shriek|was impaled on a stalagmite|starved to death|was stung to death|was poked to death by a sweet berry bush|was killed while trying to hurt.*|was pummeled by.*|was impaled by.*|withered away|was shot by a skull from.*|was killed by.*)"
    );
    private static final Pattern PHOTOGRAPHING_PATTERN = Pattern.compile(".*issued server command: /photographing on");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(.+?)> (.*)");

    // Server state patterns
    private static final Pattern SERVER_START_PATTERN = Pattern.compile("Starting minecraft server version");
    private static final Pattern SERVER_STOP_PATTERN = Pattern.compile("Stopping server");

    private static final Set<String> NON_PLAYER_ENTITIES = new HashSet<>(Arrays.asList(
            "Villager", "Librarian", "Farmer", "Shepherd", "Nitwit", "Leatherworker",
            "Weaponsmith", "Fisherman", "You", "adomin" // Common misidentified names
    ));


    private record LogLine(LocalDateTime timestamp, String content, String fileName) {}
    private record ProcessResult(boolean hasError, List<String> errorLines) {}
    private record PlayerSession(LocalDateTime actualLoginTime, String loginLogLine, String loginFileName) {}


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
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GREEN));
            return;
        }

        Map<String, UUID> nameToUuidFromYml = buildComprehensiveNameMap();

        int totalFilesProcessed = 0;
        int sessionsWithError = 0;

        for (List<File> sessionFiles : serverSessions) {
            if (sessionFiles.isEmpty()) continue;

            String sessionName = sessionFiles.get(0).getName() + " から " + sessionFiles.get(sessionFiles.size() - 1).getName();
            plugin.getAdventure().sender(sender).sendMessage(Component.text("サーバーセッション: " + sessionName + " の処理を開始 (" + sessionFiles.size() + "ファイル)...", NamedTextColor.GREEN));

            try {
                List<LogLine> allLines = new ArrayList<>();
                Map<String, UUID> nameToUuidFromLog = new HashMap<>();

                for (File logFile : sessionFiles) {
                    try (BufferedReader reader = getReaderForFile(logFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                            if (uuidMatcher.find()) {
                                try {
                                    UUID uuid = UUID.fromString(uuidMatcher.group(2));
                                    nameToUuidFromLog.put(uuidMatcher.group(1).toLowerCase(), uuid);
                                } catch (IllegalArgumentException ignored) {}
                            }
                            Matcher floodgateMatcher = FLOODGATE_UUID_PATTERN.matcher(line);
                            if (floodgateMatcher.find()) {
                                try {
                                    UUID uuid = UUID.fromString(floodgateMatcher.group(2));
                                    nameToUuidFromLog.put(floodgateMatcher.group(1).toLowerCase(), uuid);
                                } catch (IllegalArgumentException ignored) {}
                            }
                        }
                    }
                }

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
                                    allLines.add(new LogLine(currentDate.atTime(currentTime), line, logFile.getName()));
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
                    sessionsWithError++;
                    moveFilesTo(sessionFiles, errorDir);
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("セッション " + sessionName + " に不整合があったためErrorフォルダに移動しました。", NamedTextColor.RED));

                    File errorLogFile = new File(errorDir, "error_details_" + sessionFiles.get(0).getName().replace(".log.gz", "").replace(".log", "") + ".txt");
                    List<String> outputLines = new ArrayList<>();
                    outputLines.add("Error in session: " + sessionName);
                    outputLines.add("Reason: Inconsistent session data (e.g., player left without joining).");
                    outputLines.add("Problematic lines:");
                    outputLines.addAll(result.errorLines().stream().map(s -> "  " + s).toList());
                    outputLines.add("");

                    try {
                        Files.write(errorLogFile.toPath(), outputLines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

        participantManager.saveAllParticipantData();


        if (totalFilesProcessed > 0) {
            plugin.getAdventure().sender(sender).sendMessage(
                    Component.text("ログ処理が完了しました！", NamedTextColor.GREEN)
                            .append(Component.newline())
                            .append(Component.text("正常に処理されたファイル数: " + totalFilesProcessed + "件", NamedTextColor.AQUA))
            );
        } else if (sessionsWithError > 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("正常に処理されたログセッションはありませんでした。", NamedTextColor.YELLOW));
        } else {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GREEN));
        }

        if (sessionsWithError > 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text(sessionsWithError + " 件のセッションでエラーが検出されました。詳細はErrorフォルダを確認してください。", NamedTextColor.RED));
        }
    }

    private Map<String, UUID> buildComprehensiveNameMap() {
        Map<String, UUID> nameMap = new HashMap<>();
        List<ParticipantData> allParticipants = Stream.concat(
                participantManager.getActiveParticipants().stream(),
                participantManager.getDischargedParticipants().stream()
        ).collect(Collectors.toList());

        for (ParticipantData data : allParticipants) {
            UUID representativeUuid = data.getAssociatedUuids().stream().findFirst().orElse(null);
            if (representativeUuid == null) continue;

            if (data.getBaseName() != null && !data.getBaseName().isEmpty()) {
                nameMap.put(data.getBaseName().toLowerCase(), representativeUuid);
            }
            if (data.getLinkedName() != null && !data.getLinkedName().isEmpty()) {
                nameMap.put(data.getLinkedName().toLowerCase(), representativeUuid);
            }

            for (UUID uuid : data.getAssociatedUuids()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                String mcName = player.getName();
                if (mcName != null) {
                    nameMap.put(mcName.toLowerCase(), representativeUuid);
                }
            }
            if(data.getUuidToNameMap() != null){
                for(String name : data.getUuidToNameMap().values()){
                    if(name != null){
                        nameMap.put(name.toLowerCase(), representativeUuid);
                    }
                }
            }
        }
        return nameMap;
    }


    private ProcessResult processSessionGroup(List<LogLine> allLines, Map<String, UUID> nameToUuidMap) {
        Map<UUID, PlayerSession> openSessions = new HashMap<>();
        List<String> errorLines = new ArrayList<>();
        Set<String> unmappedNames = new HashSet<>();

        for (LogLine log : allLines) {
            LocalDateTime timestamp = log.timestamp();
            String content = log.content();

            if (WHITELIST_KICK_PATTERN.matcher(content).find()) {
                continue;
            }

            Matcher joinMatcher = JOIN_PATTERN.matcher(content);
            if (joinMatcher.find()) {
                String name = joinMatcher.group(1);
                UUID uuid = findUuidForName(name, nameToUuidMap);
                if (uuid != null) {
                    if (!openSessions.containsKey(uuid)) {
                        ParticipantData data = participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
                        List<String> joinHistory = data.getJoinHistory();
                        String lastJoinStr = joinHistory.isEmpty() ? null : joinHistory.get(joinHistory.size() - 1);
                        LocalDateTime sessionStartTime = timestamp;
                        boolean isNewSession = true;

                        if (lastJoinStr != null) {
                            try {
                                LocalDateTime lastJoin = LocalDateTime.parse(lastJoinStr);
                                if (Duration.between(lastJoin, timestamp).toMinutes() < 10) {
                                    isNewSession = false;
                                    sessionStartTime = lastJoin;
                                }
                            } catch (DateTimeParseException e) {
                                // Ignore parse error in history
                            }
                        }

                        if (isNewSession) {
                            participantManager.incrementJoins(uuid);
                            participantManager.addJoinHistory(uuid, timestamp);
                        }
                        openSessions.put(uuid, new PlayerSession(sessionStartTime, content, log.fileName()));
                    }
                } else if (!NON_PLAYER_ENTITIES.contains(name)) {
                    unmappedNames.add(name);
                }
                continue;
            }

            Matcher lostConnectionMatcher = LOST_CONNECTION_PATTERN.matcher(content);
            if (lostConnectionMatcher.find()) {
                String name = lostConnectionMatcher.group(1);
                UUID uuid = findUuidForName(name, nameToUuidMap);
                if (uuid != null) {
                    endPlayerSession(uuid, timestamp, openSessions, errorLines, content);
                }
                continue;
            }

            Matcher leftGameMatcher = LEFT_GAME_PATTERN.matcher(content);
            if (leftGameMatcher.find()) {
                String name = leftGameMatcher.group(1);
                UUID uuid = findUuidForName(name, nameToUuidMap);
                if(uuid != null && openSessions.containsKey(uuid)) {
                    endPlayerSession(uuid, timestamp, openSessions, errorLines, content);
                }
                continue;
            }

            Matcher deathMatcher = DEATH_PATTERN.matcher(content);
            if (deathMatcher.find()) {
                String potentialName = deathMatcher.group(1).trim();
                if (NON_PLAYER_ENTITIES.contains(potentialName)) {
                    continue;
                }
                UUID uuid = findUuidForName(potentialName, nameToUuidMap);
                if (uuid != null) {
                    participantManager.incrementDeaths(uuid);
                } else {
                    unmappedNames.add(potentialName);
                }
                continue;
            }

            Matcher photoMatcher = PHOTOGRAPHING_PATTERN.matcher(content);
            if (photoMatcher.find()) {
                for (UUID pUuid : openSessions.keySet()) {
                    participantManager.incrementPhotoshootParticipations(pUuid, timestamp);
                }
                continue;
            }

            Matcher chatMatcher = CHAT_PATTERN.matcher(content);
            if (chatMatcher.find()) {
                String name = chatMatcher.group(1);

                // ★★★ 修正箇所 ★★★
                // 名前が数字のみで構成されている場合は、プレイヤーのチャットとして扱わない
                if (name.matches("^\\d+$")) {
                    continue;
                }

                UUID uuid = findUuidForName(name, nameToUuidMap);
                if (uuid != null) {
                    participantManager.incrementChats(uuid);
                    int wCount = StringUtils.countMatches(chatMatcher.group(2), 'w');
                    if (wCount > 0) {
                        participantManager.incrementWCount(uuid, wCount);
                    }
                }
            }
        }

        if (!unmappedNames.isEmpty()) {
            logger.warning("[YAPIMARU_Plugin] 以下のプレイヤー名をUUIDにマッピングできませんでした: " + String.join(", ", unmappedNames));
        }

        if (!openSessions.isEmpty()) {
            for (Map.Entry<UUID, PlayerSession> entry : openSessions.entrySet()) {
                UUID pUuid = entry.getKey();
                PlayerSession session = entry.getValue();
                String name = Bukkit.getOfflinePlayer(pUuid).getName();
                if (name == null) name = pUuid.toString();
                errorLines.add("Player '" + name + "' session was not closed.");
                errorLines.add("  Login detected in file '" + session.loginFileName() + "' with line: " + session.loginLogLine());
            }
        }

        return new ProcessResult(!errorLines.isEmpty(), errorLines);
    }

    private void endPlayerSession(UUID uuid, LocalDateTime timestamp, Map<UUID, PlayerSession> openSessions, List<String> errorLines, String originalContent) {
        PlayerSession session = openSessions.remove(uuid);
        if (session != null) {
            ParticipantData data = participantManager.getParticipant(uuid);
            if (data == null) return;

            long playTime = Duration.between(session.actualLoginTime(), timestamp).getSeconds();
            if (playTime > 0) {
                participantManager.addPlaytime(uuid, playTime);
                data.addPlaytimeToHistory(playTime);
            }
            data.setLastQuitTime(timestamp);
        } else {
            // ログアウト記録はあるが、対応するログイン記録がセッション内にない場合
            // これはエラーとして記録してもよいが、今回は無視して次の処理へ進む
        }
    }

    private UUID findUuidForName(String name, Map<String, UUID> nameToUuidMap) {
        String lowerCaseName = name.toLowerCase();

        if (nameToUuidMap.containsKey(lowerCaseName)) {
            return nameToUuidMap.get(lowerCaseName);
        }

        Optional<ParticipantData> foundParticipant = participantManager.findParticipantByAnyName(name);
        return foundParticipant.flatMap(pData -> pData.getAssociatedUuids().stream().findFirst()).orElse(null);
    }

    private List<List<File>> groupLogsByServerSession(File logDir) {
        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));
        if (logFiles == null || logFiles.length == 0) {
            return Collections.emptyList();
        }

        Comparator<File> logFileComparator = (f1, f2) -> {
            Pattern pattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log(\\.gz)?");
            Matcher m1 = pattern.matcher(f1.getName());
            Matcher m2 = pattern.matcher(f2.getName());

            if (m1.matches() && m2.matches()) {
                String date1 = m1.group(1);
                int num1 = Integer.parseInt(m1.group(2));
                String date2 = m2.group(1);
                int num2 = Integer.parseInt(m2.group(2));

                int dateCompare = date1.compareTo(date2);
                if (dateCompare != 0) {
                    return dateCompare;
                }
                return Integer.compare(num1, num2);
            }
            return f1.getName().compareTo(f2.getName());
        };

        Arrays.sort(logFiles, logFileComparator);

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
                if (inSession && !currentSessionFiles.isEmpty()) {
                    sessions.add(new ArrayList<>(currentSessionFiles));
                }
                currentSessionFiles.clear();
                currentSessionFiles.add(currentFile);
                inSession = true;
            } else if (inSession) {
                currentSessionFiles.add(currentFile);
            } else if (!currentSessionFiles.isEmpty()){
                // セッション外のファイルも前のセッションに含める
                sessions.get(sessions.size()-1).add(currentFile);
            }


            if (isStop && inSession) {
                sessions.add(new ArrayList<>(currentSessionFiles));
                currentSessionFiles.clear();
                inSession = false;
            }
        }

        if (!currentSessionFiles.isEmpty()) {
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
        return LocalDate.now();
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
        participantManager.saveAllParticipantData(); // リセット後も保存
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }


    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを全て読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}