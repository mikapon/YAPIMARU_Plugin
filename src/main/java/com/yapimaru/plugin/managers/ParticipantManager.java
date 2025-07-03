package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
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

    private final Map<UUID, Long> sessionStartTimes = new ConcurrentHashMap<>();

    public ParticipantManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantDir = new File(plugin.getDataFolder(), "Participant_Information");
        this.activeDir = new File(participantDir, "participant");
        this.dischargedDir = new File(participantDir, "discharge");

        if (!activeDir.exists()) activeDir.mkdirs();
        if (!dischargedDir.exists()) dischargedDir.mkdirs();

        migrateOldFiles();
        loadAllParticipants();
    }

    public synchronized void handleServerStartup() {
        LocalDateTime startupTime = LocalDateTime.now();
        Stream.concat(activeParticipants.values().stream(), dischargedParticipants.values().stream())
                .forEach(data -> {
                    boolean wasModified = false;
                    for (Map.Entry<UUID, ParticipantData.AccountInfo> entry : data.getAccounts().entrySet()) {
                        if (entry.getValue().isOnline()) {
                            plugin.getLogger().warning("Player " + entry.getValue().getName() + " was marked as online during startup. Correcting session data.");

                            Long loginTimestamp = sessionStartTimes.remove(entry.getKey());
                            if (loginTimestamp != null) {
                                LocalDateTime quitTime = startupTime.minusMinutes(2); // Assume a 2-minute session if server crashed
                                long playTime = Duration.between(LocalDateTime.ofInstant(Instant.ofEpochMilli(loginTimestamp), ZoneId.systemDefault()), quitTime).getSeconds();
                                if (playTime > 0) {
                                    data.addPlaytime(playTime);
                                }
                            }
                            entry.getValue().setOnline(false);
                            wasModified = true;
                        }
                    }
                    if(wasModified) {
                        saveParticipant(data);
                    }
                });
        sessionStartTimes.clear();
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

        config.setComments("accounts", List.of("紐付けられたUUIDと、その時点でのプレイヤー名の記録、オンライン状況"));
        for (Map.Entry<UUID, ParticipantData.AccountInfo> entry : data.getAccounts().entrySet()) {
            String uuidStr = entry.getKey().toString();
            config.set("accounts." + uuidStr + ".name", entry.getValue().getName());
            config.set("accounts." + uuidStr + ".online", entry.getValue().isOnline());
        }

        Map<String, Number> stats = data.getStatistics();
        config.set("statistics.total_deaths", stats.get("total_deaths"));
        config.setComments("statistics.total_deaths", List.of("デス合計数"));
        config.set("statistics.total_playtime_seconds", stats.get("total_playtime_seconds"));
        config.setComments("statistics.total_playtime_seconds", List.of("サーバー参加合計時間"));
        config.set("statistics.total_joins", stats.get("total_joins"));
        config.setComments("statistics.total_joins", List.of("サーバー入室合計回数"));
        config.set("statistics.photoshoot_participations", stats.get("photoshoot_participations"));
        config.setComments("statistics.photoshoot_participations", List.of("撮影参加合計回数"));
        config.set("statistics.total_chats", stats.get("total_chats"));
        config.setComments("statistics.total_chats", List.of("チャット合計回数"));
        config.set("statistics.w_count", stats.get("w_count"));
        config.setComments("statistics.w_count", List.of("w合計数"));

        config.set("join-history", data.getJoinHistory());
        config.setComments("join-history", List.of("参加履歴"));
        config.set("photoshoot-history", data.getPhotoshootHistory());
        config.setComments("photoshoot-history", List.of("撮影参加履歴"));
        config.set("last-quit-time", data.getLastQuitTime());
        config.setComments("last-quit-time", List.of("最終退出時刻"));
        config.set("playtime-history", data.getPlaytimeHistory());
        config.setComments("playtime-history", List.of("直近10回のプレイ時間(秒)"));

        config.set("is-online", data.isOnline());
        config.setComments("is-online", List.of("この参加者に紐づくアカウントのいずれかがオンラインか"));

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

    public synchronized void recordLoginTime(Player player) {
        ParticipantData data = findOrCreateParticipant(player);
        if (data == null) return;

        data.recordLogin(player.getUniqueId(), LocalDateTime.now());
        sessionStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
        saveParticipant(data);
    }

    public synchronized void recordQuitTime(Player player) {
        ParticipantData data = getParticipant(player.getUniqueId());
        if (data == null) return;

        Long loginTimestamp = sessionStartTimes.remove(player.getUniqueId());
        long durationSeconds = 0;
        if (loginTimestamp != null) {
            durationSeconds = (System.currentTimeMillis() - loginTimestamp) / 1000;
        }

        data.recordLogout(player.getUniqueId(), LocalDateTime.now(), durationSeconds);
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

    // ★★★ ここから下に必要なメソッドを再追加 ★★★
    public synchronized void incrementDeaths(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("total_deaths");
            saveParticipant(data);
        }
    }

    public synchronized void incrementChats(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("total_chats");
            saveParticipant(data);
        }
    }

    public synchronized void incrementWCount(UUID uuid, int amount) {
        if (amount == 0) return;
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            long currentWCount = data.getStatistics().getOrDefault("w_count", 0L).longValue();
            data.getStatistics().put("w_count", currentWCount + amount);
            saveParticipant(data);
        }
    }

    public synchronized void incrementPhotoshootParticipations(UUID uuid, LocalDateTime timestamp) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data != null) {
            data.incrementStat("photoshoot_participations");
            data.addHistoryEvent("photoshoot", timestamp);
            saveParticipant(data);
        }
    }
    // ★★★ ここまで ★★★


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
                    if (pData.getBaseName() != null && pData.getBaseName().toLowerCase().contains(lowerCaseName)) return true;
                    if (pData.getLinkedName() != null && pData.getLinkedName().toLowerCase().contains(lowerCaseName)) return true;
                    for (ParticipantData.AccountInfo accountInfo : pData.getAccounts().values()) {
                        if (accountInfo.getName() != null && accountInfo.getName().toLowerCase().contains(lowerCaseName)) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }
}