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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private static final Pattern DATE_FROM_FILENAME_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

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
        File processedDir = new File(logDir, "processed");
        File memoriDir = new File(logDir, "memori");
        File sessionFile = new File(memoriDir, "sessions.yml");

        try {
            if (!processedDir.exists()) Files.createDirectories(processedDir.toPath());
            if (!memoriDir.exists()) Files.createDirectories(memoriDir.toPath());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "作業ディレクトリの作成に失敗しました。", e);
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: 作業ディレクトリの作成に失敗しました。サーバーの権限を確認してください。", NamedTextColor.RED));
            return;
        }

        File[] logFiles = logDir.listFiles(File::isFile);
        if (logFiles == null || logFiles.length == 0) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("処理対象のログファイルが見つかりませんでした。", NamedTextColor.GOLD));
            // 処理するファイルがなければ、一時ファイルも削除
            if(sessionFile.exists()) sessionFile.delete();
            return;
        }

        Arrays.sort(logFiles, Comparator.comparing(File::getName));
        File logFileToProcess = logFiles[0];

        Matcher dateMatcher = DATE_FROM_FILENAME_PATTERN.matcher(logFileToProcess.getName());
        if (!dateMatcher.find()) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: ファイル名から日付を読み取れませんでした。ファイル名は `YYYY-MM-DD...` の形式にしてください。", NamedTextColor.RED));
            return;
        }
        LocalDate initialDate = LocalDate.parse(dateMatcher.group(1));

        plugin.getAdventure().sender(sender).sendMessage(Component.text("ファイル: " + logFileToProcess.getName() + " の処理を開始...", NamedTextColor.GRAY));

        try {
            List<String> lines = Files.readAllLines(logFileToProcess.toPath());

            // 1. UUIDマップを構築
            Map<String, UUID> nameToUuidMap = new HashMap<>();
            for (String line : lines) {
                Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                if (uuidMatcher.find()) {
                    nameToUuidMap.put(uuidMatcher.group(1), UUID.fromString(uuidMatcher.group(2)));
                }
            }

            // 2. 一時ファイルから前回のセッション情報を読み込む
            Map<UUID, LocalDateTime> openSessions = loadOpenSessions(sessionFile);

            // 3. ログを解析
            LocalDate currentDate = initialDate;
            LocalTime lastTime = LocalTime.MIN;

            for (String line : lines) {
                Matcher timeMatcher = LOG_LINE_PATTERN.matcher(line);
                if (!timeMatcher.find()) continue;

                try {
                    LocalTime currentTime = LocalTime.parse(timeMatcher.group(1), DateTimeFormatter.ISO_LOCAL_TIME);
                    if (currentTime.isBefore(lastTime)) {
                        currentDate = currentDate.plusDays(1);
                    }
                    LocalDateTime timestamp = currentDate.atTime(currentTime);
                    lastTime = currentTime;

                    parseAndApplySimpleEvents(line, nameToUuidMap);

                    // Join/Leaveイベントを処理
                    Matcher joinMatcher = JOIN_PATTERN.matcher(line);
                    if (joinMatcher.find()) {
                        UUID uuid = nameToUuidMap.get(joinMatcher.group(1));
                        if (uuid != null && !openSessions.containsKey(uuid)) {
                            openSessions.put(uuid, timestamp);
                        }
                    } else {
                        Matcher leaveMatcher = LEAVE_PATTERN.matcher(line);
                        if (leaveMatcher.find()) {
                            UUID uuid = nameToUuidMap.get(leaveMatcher.group(1));
                            if (uuid != null && openSessions.containsKey(uuid)) {
                                LocalDateTime joinTime = openSessions.get(uuid);
                                long playtime = Duration.between(joinTime, timestamp).getSeconds();
                                if(playtime > 0) participantManager.addPlaytime(uuid, playtime);
                                openSessions.remove(uuid); // セッションを閉じる
                            }
                        }
                    }
                } catch (DateTimeParseException e) { /* 時刻解析失敗は無視 */ }
            }

            // 4. ログアウトしていないプレイヤーのセッション情報を一時ファイルに保存
            saveOpenSessions(sessionFile, openSessions);

            // 5. 処理済みファイルを移動
            Files.move(logFileToProcess.toPath(), processedDir.toPath().resolve(logFileToProcess.getName()), StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            logger.log(Level.SEVERE, "ログファイル " + logFileToProcess.getName() + " の処理に失敗しました。", e);
            plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: " + logFileToProcess.getName() + " の処理に失敗しました。詳細はコンソールを確認してください。", NamedTextColor.RED));
            return;
        }

        long remainingFileCount = logDir.listFiles(File::isFile).length;

        plugin.getAdventure().sender(sender).sendMessage(
                Component.text("ログファイル「" + logFileToProcess.getName() + "」の処理が完了しました！", NamedTextColor.GREEN)
                        .append(Component.newline())
                        .append(Component.text("残りファイル数: " + remainingFileCount + "件", NamedTextColor.YELLOW))
        );

        // もし残りファイルがなければ一時ファイルを削除
        if (remainingFileCount == 0) {
            if (sessionFile.exists()) sessionFile.delete();
            plugin.getAdventure().sender(sender).sendMessage(Component.text("全てのログ処理が完了したため、一時ファイルを削除しました。", NamedTextColor.GOLD));
        }
    }

    private Map<UUID, LocalDateTime> loadOpenSessions(File sessionFile) {
        Map<UUID, LocalDateTime> openSessions = new HashMap<>();
        if (sessionFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(sessionFile);
            ConfigurationSection section = config.getConfigurationSection("open-sessions");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        LocalDateTime time = LocalDateTime.parse(section.getString(key));
                        openSessions.put(uuid, time);
                    } catch (Exception e) {
                        logger.warning("セッションファイル " + key + " の読み込みに失敗しました。");
                    }
                }
            }
        }
        return openSessions;
    }

    private void saveOpenSessions(File sessionFile, Map<UUID, LocalDateTime> openSessions) {
        YamlConfiguration config = new YamlConfiguration();
        if (openSessions.isEmpty()) {
            // セッションが空ならファイルを削除
            if (sessionFile.exists()) {
                sessionFile.delete();
            }
            return;
        }

        ConfigurationSection section = config.createSection("open-sessions");
        for(Map.Entry<UUID, LocalDateTime> entry : openSessions.entrySet()) {
            section.set(entry.getKey().toString(), entry.getValue().toString());
        }

        try {
            config.save(sessionFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "セッションファイルの上書き保存に失敗しました。", e);
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

    private void handleResetCommand(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("全参加者の統計情報をリセットしています...", NamedTextColor.YELLOW));
        int count = participantManager.resetAllStats();
        plugin.getAdventure().sender(sender).sendMessage(Component.text(count + " 人の参加者の統計情報をリセットしました。", NamedTextColor.GREEN));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- Log Command Help ---");
        sender.sendMessage("§e/log add §7- ログファイルを1つ読み込み統計を合算します。");
        sender.sendMessage("§e/log reset §7- 全ての統計情報をリセットします。");
    }
}