package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ParticipantManager {

    private final YAPIMARU_Plugin plugin;
    private final File participantDir;
    private final File activeDir;
    private final File dischargedDir;

    private final Map<String, ParticipantData> activeParticipants = new HashMap<>();
    private final Map<UUID, ParticipantData> uuidToParticipantMap = new HashMap<>();
    private final Map<String, ParticipantData> dischargedParticipants = new HashMap<>();

    private final Map<String, LocalDateTime> participantSessionStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> isContinuingSession = new ConcurrentHashMap<>();


    public ParticipantManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantDir = new File(plugin.getDataFolder(), "Participant_Information");
        this.activeDir = new File(participantDir, "participant");
        this.dischargedDir = new File(participantDir, "discharge");

        if (!activeDir.exists() && !activeDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create participant directory: " + activeDir.getPath());
        }
        if (!dischargedDir.exists() && !dischargedDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create discharge directory: " + dischargedDir.getPath());
        }

        migrateOldFiles();
        loadAllParticipants();
    }

    public synchronized void handleServerStartup() {
        Stream.concat(activeParticipants.values().stream(), dischargedParticipants.values().stream())
                .forEach(data -> {
                    boolean wasModified = false;
                    for (Map.Entry<UUID, ParticipantData.AccountInfo> entry : data.getAccounts().entrySet()) {
                        if (entry.getValue().isOnline()) {
                            plugin.getLogger().warning("Player " + entry.getValue().getName() + " was marked as online during startup. Correcting session data.");
                            handlePlayerLogout(entry.getKey());
                            entry.getValue().setOnline(false);
                            wasModified = true;
                        }
                    }
                    if(wasModified) {
                        saveParticipant(data);
                    }
                });
        participantSessionStartTimes.clear();
        isContinuingSession.clear();
    }


    private synchronized void loadAllParticipants() {
        activeParticipants.clear();
        uuidToParticipantMap.clear();
        dischargedParticipants.clear();

        java.util.function.Consumer<File> processDirectory = (directory) -> {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) return;

            Map<String, ParticipantData> map = (directory.equals(activeDir)) ? activeParticipants : dischargedParticipants;

            for (File file : files) {
                loadParticipantFromFile(file, map);
            }
        };

        processDirectory.accept(activeDir);
        processDirectory.accept(dischargedDir);

        plugin.getLogger().info("Loaded " + activeParticipants.size() + " active and " + dischargedParticipants.size() + " participant data files.");
    }

    private synchronized void loadParticipantFromFile(File file, Map<String, ParticipantData> map) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ParticipantData data = new ParticipantData(config);
        map.put(data.getParticipantId(), data);
        if (map == activeParticipants) {
            for (UUID uuid : data.getAssociatedUuids()) {
                uuidToParticipantMap.put(uuid, data);
            }
        }
    }

    public synchronized void reloadAllParticipants() {
        plugin.getLogger().info("Reloading all participant data...");
        loadAllParticipants();
    }

    public synchronized ParticipantData getParticipant(UUID uuid) {
        return uuidToParticipantMap.get(uuid);
    }

    public synchronized ParticipantData getParticipant(String participantId) {
        return activeParticipants.get(participantId);
    }


    public synchronized Collection<ParticipantData> getActiveParticipants() {
        return Collections.unmodifiableCollection(new ArrayList<>(activeParticipants.values()));
    }

    public synchronized Collection<ParticipantData> getDischargedParticipants() {
        return Collections.unmodifiableCollection(new ArrayList<>(dischargedParticipants.values()));
    }

    public synchronized ParticipantData findOrCreateParticipant(OfflinePlayer player) {
        if (player == null) return null;
        ParticipantData data = getParticipant(player.getUniqueId());
        if (data != null) {
            if (!data.getAccounts().containsKey(player.getUniqueId())) {
                data.addAccount(player.getUniqueId(), player.getName());
                saveParticipant(data);
            }
            return data;
        }

        String baseName = player.getName() != null ? player.getName() : player.getUniqueId().toString();
        String linkedName = "";

        data = new ParticipantData(baseName, linkedName);
        data.addAccount(player.getUniqueId(), player.getName());

        registerNewParticipant(data);
        return data;
    }

    public synchronized void saveParticipant(ParticipantData data) {
        if (data == null) return;

        boolean isDischarged = dischargedParticipants.containsKey(data.getParticipantId());
        File targetDir = isDischarged ? dischargedDir : activeDir;

        File file = new File(targetDir, data.getParticipantId() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("base_name", data.getBaseName());
        config.set("linked_name", data.getLinkedName());

        Map<String, Object> accountsMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, ParticipantData.AccountInfo> entry : data.getAccounts().entrySet()) {
            Map<String, Object> accountDetails = new LinkedHashMap<>();
            accountDetails.put("name", entry.getValue().getName());
            accountDetails.put("online", entry.getValue().isOnline());
            accountsMap.put(entry.getKey().toString(), accountDetails);
        }
        config.set("accounts", accountsMap);


        Map<String, Number> stats = data.getStatistics();
        config.set("statistics.total_deaths", stats.get("total_deaths"));
        config.set("statistics.total_playtime_seconds", stats.get("total_playtime_seconds"));
        config.set("statistics.total_joins", stats.get("total_joins"));
        config.set("statistics.photoshoot_participations", stats.get("photoshoot_participations"));
        config.set("statistics.total_chats", stats.get("total_chats"));
        config.set("statistics.w_count", stats.get("w_count"));

        config.set("join-history", data.getJoinHistory());
        config.set("photoshoot-history", data.getPhotoshootHistory());
        config.set("last-quit-time", data.getLastQuitTime());
        config.set("playtime-history", data.getPlaytimeHistory());

        config.set("is-online", data.isOnline());

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save participant data for " + data.getParticipantId(), e);
        }
    }

    public synchronized void registerNewParticipant(ParticipantData data) {
        if (data == null || data.getParticipantId() == null || data.getParticipantId().isEmpty()) {
            return;
        }
        activeParticipants.put(data.getParticipantId(), data);
        for (UUID uuid : data.getAssociatedUuids()) {
            uuidToParticipantMap.put(uuid, data);
        }
        saveParticipant(data);
    }

    public synchronized void handlePlayerLogin(UUID uuid, boolean isServerStart) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;

        boolean wasParticipantOnline = data.isOnline();
        data.getAccounts().get(uuid).setOnline(true);

        if (!wasParticipantOnline) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastQuit = data.getLastQuitTimeAsDate();

            boolean isNewSession = true;
            if (lastQuit != null && !isServerStart) {
                if (Duration.between(lastQuit, now).toMinutes() < 10) {
                    isNewSession = false;
                }
            }

            if (isNewSession) {
                isContinuingSession.put(data.getParticipantId(), false);
                data.incrementStat("total_joins", 1);
                data.addHistoryEvent("join", now);
            } else {
                isContinuingSession.put(data.getParticipantId(), true);
            }
            participantSessionStartTimes.put(data.getParticipantId(), now);
        }
        saveParticipant(data);
    }

    public synchronized void handlePlayerLogout(UUID uuid) {
        ParticipantData data = getParticipant(uuid);
        if (data == null) return;

        if (data.getAccounts().containsKey(uuid)) {
            data.getAccounts().get(uuid).setOnline(false);
        }

        if (!data.isOnline()) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = participantSessionStartTimes.remove(data.getParticipantId());
            if (startTime != null) {
                long durationSeconds = Duration.between(startTime, now).getSeconds();
                if (durationSeconds > 0) {
                    data.addPlaytime(durationSeconds);
                    if (isContinuingSession.getOrDefault(data.getParticipantId(), false)) {
                        data.addTimeToLastPlaytime(durationSeconds);
                    } else {
                        data.addPlaytimeToHistory(durationSeconds);
                    }
                }
            }
            data.setLastQuitTime(now);
            isContinuingSession.remove(data.getParticipantId());
        }
        saveParticipant(data);
    }

    public synchronized int resetAllStats() {
        int count = 0;
        for (ParticipantData data : activeParticipants.values()) {
            data.resetStats();
            saveParticipant(data);
            count++;
        }
        for (ParticipantData data : dischargedParticipants.values()) {
            data.resetStats();
            saveParticipant(data);
            count++;
        }
        return count;
    }

    public synchronized void incrementDeaths(UUID uuid, int amount) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("total_deaths", amount);
            saveParticipant(data);
        }
    }

    public synchronized void incrementChats(UUID uuid, int amount) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("total_chats", amount);
            saveParticipant(data);
        }
    }

    public synchronized void incrementWCount(UUID uuid, int amount) {
        if (amount == 0) return;
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("w_count", amount);
            saveParticipant(data);
        }
    }

    public int calculateWCount(String message) {
        String cleanedMessage = removeNestedParentheses(message);
        cleanedMessage = cleanedMessage.replaceAll("[()]", "");

        int count = 0;
        String[] laughWords = {"kusa", "草", "wara", "笑", "lol"};
        String tempMessage = cleanedMessage.toLowerCase();
        for (String word : laughWords) {
            count += (tempMessage.length() - tempMessage.replace(word, "").length()) / word.length();
            tempMessage = tempMessage.replace(word, "");
        }

        Pattern wPattern = Pattern.compile("w{2,}");
        Matcher wMatcher = wPattern.matcher(tempMessage.toLowerCase());
        while(wMatcher.find()) {
            count += wMatcher.group().length();
        }
        if (tempMessage.endsWith("w") && !tempMessage.endsWith("ww")) {
            count++;
        }

        return count;
    }

    private String removeNestedParentheses(String text) {
        StringBuilder result = new StringBuilder();
        int depth = 0;
        int lastPos = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                if(depth == 0) {
                    result.append(text, lastPos, i);
                }
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                if(depth == 0) {
                    lastPos = i + 1;
                }
            }
        }
        if (lastPos < text.length()) {
            result.append(text.substring(lastPos));
        }

        Pattern pattern = Pattern.compile("\\([^()]*\\([^()]*\\)[^()]*\\)");
        Matcher matcher = pattern.matcher(result.toString());
        if(matcher.find()){
            return removeNestedParentheses(matcher.replaceAll(""));
        }
        return result.toString();
    }


    public synchronized void incrementPhotoshootParticipations(UUID uuid, LocalDateTime timestamp) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("photoshoot_participations", 1);
            data.addHistoryEvent("photoshoot", timestamp);
            saveParticipant(data);
        }
    }

    public synchronized boolean moveParticipantToActive(String participantId) {
        ParticipantData data = dischargedParticipants.remove(participantId);
        if (data == null) return false;

        File oldFile = new File(dischargedDir, participantId + ".yml");
        File newFile = new File(activeDir, participantId + ".yml");

        try {
            Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            activeParticipants.put(participantId, data);
            data.getAssociatedUuids().forEach(uuid -> uuidToParticipantMap.put(uuid, data));
            plugin.getWhitelistManager().syncAllowedPlayers();
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not move participant file to active: " + participantId, e);
            dischargedParticipants.put(participantId, data);
            return false;
        }
    }

    public synchronized boolean moveParticipantToDischarged(String participantId) {
        ParticipantData data = activeParticipants.remove(participantId);
        if (data == null) return false;

        File oldFile = new File(activeDir, participantId + ".yml");
        File newFile = new File(dischargedDir, participantId + ".yml");

        try {
            Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dischargedParticipants.put(participantId, data);
            data.getAssociatedUuids().forEach(uuidToParticipantMap::remove);
            plugin.getWhitelistManager().syncAllowedPlayers();
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not move participant file to discharged: " + participantId, e);
            activeParticipants.put(participantId, data);
            return false;
        }
    }

    public synchronized Set<UUID> getAllAssociatedUuidsFromActive() {
        return new HashSet<>(uuidToParticipantMap.keySet());
    }

    public synchronized boolean changePlayerName(UUID uuid, String newBaseName, String newLinkedName) {
        ParticipantData oldData = getParticipant(uuid);
        if (oldData == null) return false;

        String oldParticipantId = oldData.getParticipantId();
        String newParticipantId = ParticipantData.generateId(newBaseName, newLinkedName);

        if (oldParticipantId.equals(newParticipantId)) {
            oldData.setFullName(newBaseName, newLinkedName);
            saveParticipant(oldData);
            return true;
        }

        File oldFile = new File(activeDir, oldParticipantId + ".yml");
        if(oldFile.exists()) {
            if(!oldFile.delete()) {
                plugin.getLogger().warning("Failed to delete old participant file: " + oldFile.getName());
                return false;
            }
        }

        activeParticipants.remove(oldParticipantId);
        oldData.setFullName(newBaseName, newLinkedName);
        activeParticipants.put(newParticipantId, oldData);
        saveParticipant(oldData);
        return true;
    }

    private void migrateOldFiles() {
        File[] oldFiles = participantDir.listFiles(File::isFile);
        if (oldFiles == null || oldFiles.length == 0) return;

        plugin.getLogger().info("Moving old participant files to 'participant' sub-directory...");
        int count = 0;
        for (File oldFile : oldFiles) {
            try {
                Files.move(oldFile.toPath(), new File(activeDir, oldFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                count++;
            } catch (IOException e) {
                plugin.getLogger().warning("Could not move file: " + oldFile.getName());
            }
        }
        if (count > 0) {
            plugin.getLogger().info("Moved " + count + " files.");
        }
    }

    public synchronized void saveAllParticipantData() {
        for (ParticipantData data : activeParticipants.values()) {
            saveParticipant(data);
        }
        for (ParticipantData data : dischargedParticipants.values()) {
            saveParticipant(data);
        }
        plugin.getLogger().info("Saved all participant data to files.");
    }

    public synchronized Optional<ParticipantData> findParticipantByAnyName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String lowerCaseName = name.toLowerCase();

        return Stream.concat(activeParticipants.values().stream(), dischargedParticipants.values().stream())
                .filter(pData -> {
                    if (pData.getBaseName() != null && pData.getBaseName().equalsIgnoreCase(lowerCaseName)) return true;
                    if (pData.getLinkedName() != null && pData.getLinkedName().equalsIgnoreCase(lowerCaseName)) return true;

                    String displayName = pData.getDisplayName();
                    if (displayName.equalsIgnoreCase(lowerCaseName)) return true;

                    Pattern pattern = Pattern.compile("(.+)\\((.+)\\)");
                    Matcher matcher = pattern.matcher(displayName);
                    if(matcher.matches()){
                        if(matcher.group(1).equalsIgnoreCase(lowerCaseName) || matcher.group(2).equalsIgnoreCase(lowerCaseName)){
                            return true;
                        }
                    }

                    for (ParticipantData.AccountInfo accountInfo : pData.getAccounts().values()) {
                        if (accountInfo.getName() != null && accountInfo.getName().equalsIgnoreCase(lowerCaseName)) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }
}