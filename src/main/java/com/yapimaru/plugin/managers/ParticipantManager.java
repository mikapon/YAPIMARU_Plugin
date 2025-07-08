package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        loadAllParticipants();
        plugin.getLogger().info("Correcting session data and ensuring all keys exist for all participants...");

        Stream.concat(activeParticipants.values().stream(), dischargedParticipants.values().stream())
                .forEach(data -> {
                    for (Map.Entry<UUID, ParticipantData.AccountInfo> entry : data.getAccounts().entrySet()) {
                        if (entry.getValue().isOnline()) {
                            plugin.getLogger().warning("Player " + entry.getValue().getName() + " ("+ data.getParticipantId() +") was marked as online during startup. Correcting status.");
                            entry.getValue().setOnline(false);
                        }
                    }
                    saveParticipant(data);
                });

        participantSessionStartTimes.clear();
        isContinuingSession.clear();
        plugin.getLogger().info("Participant check finished.");
    }

    private synchronized void loadAllParticipants() {
        activeParticipants.clear();
        uuidToParticipantMap.clear();
        dischargedParticipants.clear();

        loadParticipantsFromDirectory(activeDir, activeParticipants);
        loadParticipantsFromDirectory(dischargedDir, dischargedParticipants);

        plugin.getLogger().info("Loaded " + activeParticipants.size() + " active and " + dischargedParticipants.size() + " discharged participant data files.");

        if (plugin.getWhitelistManager() != null) {
            plugin.getWhitelistManager().syncAllowedPlayers();
        }
    }

    private synchronized void loadParticipantsFromDirectory(File directory, Map<String, ParticipantData> map) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
                ParticipantData data = new ParticipantData(config);
                map.put(data.getParticipantId(), data);
                if (map == activeParticipants) {
                    for (UUID uuid : data.getAssociatedUuids()) {
                        uuidToParticipantMap.put(uuid, data);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load participant file with UTF-8: " + file.getName(), e);
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

    public synchronized ParticipantData getLoadedParticipant(String participantId) {
        return activeParticipants.getOrDefault(participantId, dischargedParticipants.get(participantId));
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
            if (!data.getAccounts().containsKey(player.getUniqueId()) && player.getName() != null) {
                data.addAccount(player.getUniqueId(), player.getName());
                saveParticipant(data);
            }
            return data;
        }

        if (player.getName() != null) {
            Optional<ParticipantData> foundByName = findParticipantByAnyName(player.getName());
            if (foundByName.isPresent()) {
                data = foundByName.get();
                data.addAccount(player.getUniqueId(), player.getName());
                uuidToParticipantMap.put(player.getUniqueId(), data);
                saveParticipant(data);
                return data;
            }
        }

        String baseName = player.getName() != null ? player.getName() : player.getUniqueId().toString();
        data = new ParticipantData(baseName, "");
        if(player.getName() != null) {
            data.addAccount(player.getUniqueId(), player.getName());
        }

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
        config.set("statistics", data.getStatistics());
        config.set("join-history", data.getJoinHistory());
        config.set("photoshoot-history", data.getPhotoshootHistory());
        config.set("playtime-history", data.getPlaytimeHistory());
        config.set("last-quit-time", data.getLastQuitTime());
        config.set("is-online", data.isOnline());
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(config.saveToString());
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
        data.setOnlineStatus(true);
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
        if (data.getAccounts().values().stream().noneMatch(ParticipantData.AccountInfo::isOnline)) {
            data.setOnlineStatus(false);
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

    public synchronized void addLogData(ParticipantData logData) {
        ParticipantData existingData = getLoadedParticipant(logData.getParticipantId());
        if (existingData == null) {
            plugin.getLogger().warning("Log processing found a participant not in memory: " + logData.getParticipantId() + ". Registering as new.");
            registerNewParticipant(logData);
            return;
        }
        existingData.addLogData(logData);
    }

    public synchronized void incrementDeaths(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("total_deaths", 1);
            saveParticipant(data);
        }
    }

    public synchronized void incrementChats(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("total_chats", 1);
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
        return StringUtils.countMatches(message.toLowerCase(), "w");
    }

    public synchronized void incrementPhotoshootParticipations(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("photoshoot_participations", 1);
            data.addHistoryEvent("photoshoot", LocalDateTime.now());
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