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
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private record PlayerSession(LocalDateTime loginTime, String loginLogLine, String loginFileName) {}

    // ★★★ 中間的な更新データを保持するためのクラス ★★★
    private static class PlayerUpdates {
        long totalPlaytime = 0;
        int totalJoins = 0;
        int totalDeaths = 0;
        int totalChats = 0;
        int totalWCount = 0;
        int totalPhotoSessions = 0;
        List<LocalDateTime> joinHistory = new ArrayList<>();
        List<Long> playtimeHistory = new ArrayList<>();
        List<LocalDateTime> photoHistory = new ArrayList<>();
        LocalDateTime lastQuitTime = null;
    }


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
        File memoryDir = new File(logDir, "memory_dumps");

        try {
            if (!processedDir.exists()) Files.createDirectories(processedDir.toPath());
            if (!errorDir.exists()) Files.createDirectories(errorDir.toPath());
            if (!memoryDir.exists()) Files.createDirectories(memoryDir.toPath());
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
        Map<UUID, PlayerUpdates> aggregatedUpdates = new HashMap<>();
        int sessionsWithError = 0;
        int memoryFileCounter = 1;

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

                Map<UUID, PlayerUpdates> sessionUpdates = processSessionGroup(allLines, finalNameToUuidMap);

                if (!sessionUpdates.isEmpty()) {
                    File memoryFile = new File(memoryDir, "mm_" + memoryFileCounter + ".yml");
                    saveMemoryFile(sessionUpdates, memoryFile, sessionName);
                    memoryFileCounter++;

                    sessionUpdates.forEach((uuid, updates) -> {
                        PlayerUpdates aggregate = aggregatedUpdates.computeIfAbsent(uuid, k -> new PlayerUpdates());
                        aggregate.totalPlaytime += updates.totalPlaytime;
                        aggregate.totalJoins += updates.totalJoins;
                        aggregate.totalDeaths += updates.totalDeaths;
                        aggregate.totalChats += updates.totalChats;
                        aggregate.totalWCount += updates.totalWCount;
                        aggregate.totalPhotoSessions += updates.totalPhotoSessions;
                        aggregate.joinHistory.addAll(updates.joinHistory);
                        aggregate.playtimeHistory.addAll(updates.playtimeHistory);
                        aggregate.photoHistory.addAll(updates.photoHistory);
                        if (updates.lastQuitTime != null) {
                            if (aggregate.lastQuitTime == null || updates.lastQuitTime.isAfter(aggregate.lastQuitTime)) {
                                aggregate.lastQuitTime = updates.lastQuitTime;
                            }
                        }
                    });
                }

                moveFilesTo(sessionFiles, processedDir);

            } catch (IOException e) {
                logger.log(Level.SEVERE, "セッション " + sessionName + " の処理中にエラーが発生しました。", e);
                plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + sessionName + " の処理に失敗しました。次のセッションに進みます。", NamedTextColor.RED));
                moveFilesTo(sessionFiles, errorDir);
                sessionsWithError++;
            }
        }

        final int finalSessionsWithError = sessionsWithError;

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("ログ解析完了。全プレイヤーデータの更新と保存を開始します...", NamedTextColor.AQUA));
            int updatedPlayers = 0;
            for(Map.Entry<UUID, PlayerUpdates> entry : aggregatedUpdates.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerUpdates updates = entry.getValue();
                ParticipantData data = participantManager.findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));

                if (data != null) {
                    data.addPlaytime(updates.totalPlaytime);
                    for(int i = 0; i < updates.totalJoins; i++) data.incrementStat("total_joins");
                    for(int i = 0; i < updates.totalDeaths; i++) data.incrementStat("total_deaths");
                    for(int i = 0; i < updates.totalChats; i++) data.incrementStat("total_chats");
                    if(updates.totalWCount > 0) {
                        long currentWCount = data.getStatistics().getOrDefault("w_count", 0L).longValue();
                        data.getStatistics().put("w_count", currentWCount + updates.totalWCount);
                    }
                    for(int i = 0; i < updates.totalPhotoSessions; i++) data.incrementStat("photoshoot_participations");

                    updates.joinHistory.forEach(ts -> data.addHistoryEvent("join", ts));
                    updates.playtimeHistory.forEach(data::addPlaytimeToHistory);
                    updates.photoHistory.forEach(ts -> data.addHistoryEvent("photoshoot", ts));

                    if(updates.lastQuitTime != null) data.setLastQuitTime(updates.lastQuitTime);

                    updatedPlayers++;
                }
            }

            participantManager.saveAllParticipantData();

            plugin.getAdventure().sender(sender).sendMessage(Component.text(updatedPlayers + "人のプレイヤーデータを更新し、保存しました。", NamedTextColor.GREEN));

            if (finalSessionsWithError > 0) {
                plugin.getAdventure().sender(sender).sendMessage(Component.text(finalSessionsWithError + " 件のセッションでエラーが検出されました。詳細はErrorフォルダを確認してください。", NamedTextColor.RED));
            }
        });
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


    private Map<UUID, PlayerUpdates> processSessionGroup(List<LogLine> allLines, Map<String, UUID> nameToUuidMap) {
        Map<UUID, PlayerUpdates> sessionUpdates = new HashMap<>();
        Map<UUID, PlayerSession> openSessions = new HashMap<>();
        Set<String> unmappedNames = new HashSet<>();

        for (LogLine log : allLines) {
            LocalDateTime timestamp = log.timestamp();
            String content = log.content();

            if (WHITELIST_KICK_PATTERN.matcher(content).find()) continue;

            Matcher joinMatcher = JOIN_PATTERN.matcher(content);
            if (joinMatcher.find()) {
                String name = joinMatcher.group(1);
                UUID uuid = findUuidForName(name, nameToUuidMap);
                if (uuid != null) {
                    if (!openSessions.containsKey(uuid)) {
                        openSessions.put(uuid, new PlayerSession(timestamp, content, log.fileName()));
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
                    endPlayerSession(uuid, timestamp, openSessions, sessionUpdates);
                }
                continue;
            }

            Matcher leftGameMatcher = LEFT_GAME_PATTERN.matcher(content);
            if (leftGameMatcher.find()) {
                String name = leftGameMatcher.group(1);
                UUID uuid = findUuidForName(name, nameToUuidMap);
                if(uuid != null && openSessions.containsKey(uuid)) {
                    endPlayerSession(uuid, timestamp, openSessions, sessionUpdates);
                }
                continue;
            }

            Matcher deathMatcher = DEATH_PATTERN.matcher(content);
            if (deathMatcher.find()) {
                String potentialName = deathMatcher.group(1).trim();
                if (NON_PLAYER_ENTITIES.contains(potentialName)) continue;
                UUID uuid = findUuidForName(potentialName, nameToUuidMap);
                if (uuid != null) {
                    PlayerUpdates u = sessionUpdates.computeIfAbsent(uuid, k -> new PlayerUpdates());
                    u.totalDeaths++;
                } else {
                    unmappedNames.add(potentialName);
                }
                continue;
            }

            Matcher photoMatcher = PHOTOGRAPHING_PATTERN.matcher(content);
            if (photoMatcher.find()) {
                for (UUID pUuid : openSessions.keySet()) {
                    PlayerUpdates u = sessionUpdates.computeIfAbsent(pUuid, k -> new PlayerUpdates());
                    u.totalPhotoSessions++;
                    u.photoHistory.add(timestamp);
                }
                continue;
            }

            Matcher chatMatcher = CHAT_PATTERN.matcher(content);
            if (chatMatcher.find()) {
                String name = chatMatcher.group(1);
                if (name.matches("^\\d+$")) continue;
                UUID uuid = findUuidForName(name, nameToUuidMap);
                if (uuid != null) {
                    PlayerUpdates u = sessionUpdates.computeIfAbsent(uuid, k -> new PlayerUpdates());
                    u.totalChats++;
                    u.totalWCount += StringUtils.countMatches(chatMatcher.group(2), 'w');
                }
            }
        }

        if (!allLines.isEmpty()) {
            LocalDateTime lastLogTime = allLines.get(allLines.size() - 1).timestamp();
            for (UUID uuid : new HashSet<>(openSessions.keySet())) {
                endPlayerSession(uuid, lastLogTime, openSessions, sessionUpdates);
            }
        }


        if (!unmappedNames.isEmpty()) {
            logger.warning("[YAPIMARU_Plugin] 以下のプレイヤー名をUUIDにマッピングできませんでした: " + String.join(", ", unmappedNames));
        }

        return sessionUpdates;
    }

    private void endPlayerSession(UUID uuid, LocalDateTime timestamp, Map<UUID, PlayerSession> openSessions, Map<UUID, PlayerUpdates> sessionUpdates) {
        PlayerSession session = openSessions.remove(uuid);
        if (session != null) {
            PlayerUpdates u = sessionUpdates.computeIfAbsent(uuid, k -> new PlayerUpdates());

            boolean isNewJoin = true;

            List<LocalDateTime> allJoinHistory = new ArrayList<>();
            ParticipantData existingData = participantManager.getParticipant(uuid);
            if (existingData != null) {
                existingData.getJoinHistory().forEach(s -> {
                    try { allJoinHistory.add(LocalDateTime.parse(s)); } catch (DateTimeParseException e) {/*ignore*/}
                });
            }
            allJoinHistory.addAll(u.joinHistory);
            allJoinHistory.sort(Comparator.naturalOrder());

            if(!allJoinHistory.isEmpty()){
                LocalDateTime lastJoin = allJoinHistory.get(allJoinHistory.size() - 1);
                if(Duration.between(lastJoin, session.loginTime()).toMinutes() < 10) {
                    isNewJoin = false;
                }
            }

            long playTime = Duration.between(session.loginTime(), timestamp).getSeconds();
            if(playTime > 0) {
                u.totalPlaytime += playTime;
                u.playtimeHistory.add(playTime);
            }

            if(isNewJoin) {
                u.totalJoins++;
                u.joinHistory.add(session.loginTime());
            }
            u.lastQuitTime = timestamp;
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

    private void saveMemoryFile(Map<UUID, PlayerUpdates> updates, File file, String sessionName) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("session-name", sessionName);
        config.set("processed-at", LocalDateTime.now().toString());

        for (Map.Entry<UUID, PlayerUpdates> entry : updates.entrySet()) {
            String uuid = entry.getKey().toString();
            PlayerUpdates u = entry.getValue();
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());

            config.set("updates." + uuid + ".name", player.getName());
            config.set("updates." + uuid + ".totalPlaytime", u.totalPlaytime);
            config.set("updates." + uuid + ".totalJoins", u.totalJoins);
            config.set("updates." + uuid + ".totalDeaths", u.totalDeaths);
            config.set("updates." + uuid + ".totalChats", u.totalChats);
            config.set("updates." + uuid + ".totalWCount", u.totalWCount);
            config.set("updates." + uuid + ".totalPhotoSessions", u.totalPhotoSessions);
            config.set("updates." + uuid + ".joinHistory", u.joinHistory.stream().map(LocalDateTime::toString).collect(Collectors.toList()));
            config.set("updates." + uuid + ".playtimeHistory", u.playtimeHistory);
            config.set("updates." + uuid + ".photoHistory", u.photoHistory.stream().map(LocalDateTime::toString).collect(Collectors.toList()));
            config.set("updates." + uuid + ".lastQuitTime", u.lastQuitTime != null ? u.lastQuitTime.toString() : null);
        }

        try {
            config.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save memory file: " + file.getName(), e);
        }
    }

    private void handleResetCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("全参加者の統計情報をリセットしています...", NamedTextColor.GREEN));
        int count = participantManager.resetAllStats();
        participantManager.saveAllParticipantData();
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }


    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを全て読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}