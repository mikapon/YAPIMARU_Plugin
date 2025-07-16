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
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ParticipantManager {

    private final YAPIMARU_Plugin plugin;
    private final File participantDir;
    private final File activeDir;
    private final File dischargedDir;

    private final Map<String, ParticipantData> activeParticipants = new HashMap<>(); // Key: participantId
    private final Map<UUID, ParticipantData> uuidToParticipantMap = new HashMap<>();
    private final Map<String, ParticipantData> dischargedParticipants = new HashMap<>();

    // For join/playtime tracking
    private final Map<UUID, Long> loginTimestamps = new HashMap<>();
    private final Map<UUID, Long> lastQuitTimestamps = new HashMap<>();

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

    private void loadAllParticipants() {
        activeParticipants.clear();
        uuidToParticipantMap.clear();
        dischargedParticipants.clear();

        // Active participants
        File[] activeFiles = activeDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (activeFiles != null) {
            for (File file : activeFiles) {
                loadParticipantFromFile(file, activeParticipants);
            }
        }

        // Discharged participants
        File[] dischargedFiles = dischargedDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (dischargedFiles != null) {
            for (File file : dischargedFiles) {
                loadParticipantFromFile(file, dischargedParticipants);
            }
        }
        plugin.getLogger().info("Loaded " + activeParticipants.size() + " active and " + dischargedParticipants.size() + " discharged participant data files.");
    }

    private void loadParticipantFromFile(File file, Map<String, ParticipantData> map) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ParticipantData data = new ParticipantData(config);
        map.put(data.getParticipantId(), data);
        if (map == activeParticipants) { // Only map UUIDs for active participants
            for (UUID uuid : data.getAssociatedUuids()) {
                uuidToParticipantMap.put(uuid, data);
            }
        }
    }

    public void reloadAllParticipants() {
        plugin.getLogger().info("Reloading all participant data...");
        loadAllParticipants();
    }

    public ParticipantData getParticipant(UUID uuid) {
        return uuidToParticipantMap.get(uuid);
    }

    public ParticipantData getParticipant(String participantId) {
        return activeParticipants.get(participantId);
    }


    public Collection<ParticipantData> getActiveParticipants() {
        return Collections.unmodifiableCollection(activeParticipants.values());
    }

    public Collection<ParticipantData> getDischargedParticipants() {
        return Collections.unmodifiableCollection(dischargedParticipants.values());
    }

    public ParticipantData findOrCreateParticipant(OfflinePlayer player) {
        if (player == null) return null;
        if (uuidToParticipantMap.containsKey(player.getUniqueId())) {
            return uuidToParticipantMap.get(player.getUniqueId());
        }

        // This is a truly new player, not in config or existing files.
        String baseName = player.getName() != null ? player.getName() : player.getUniqueId().toString();
        String linkedName = "";

        ParticipantData data = new ParticipantData(baseName, linkedName);
        data.addAssociatedUuid(player.getUniqueId());

        registerNewParticipant(data);
        return data;
    }

    public void saveParticipant(ParticipantData data) {
        if (data == null) return;

        boolean isDischarged = dischargedParticipants.containsKey(data.getParticipantId());
        File targetDir = isDischarged ? dischargedDir : activeDir;

        File file = new File(targetDir, data.getParticipantId() + ".yml");
        // 既存のファイルをロードして、未知のセクションを保持する
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        config.set("base_name", data.getBaseName());
        config.setComments("base_name", List.of("プレイヤー名"));

        config.set("linked_name", data.getLinkedName());
        config.setComments("linked_name", List.of("キャラクター名"));

        config.set("associated-uuids", data.getAssociatedUuids().stream().map(UUID::toString).collect(Collectors.toList()));
        config.setComments("associated-uuids", List.of("UUID"));

        String statsPath = "statistics";
        // statisticsセクションを一度nullでクリアすることで、古いキーが残るのを防ぐ
        config.set(statsPath, null);
        config.createSection(statsPath, data.getStatistics());
        config.setComments(statsPath, List.of("統計情報"));
        config.setComments(statsPath + ".total_deaths", List.of("デス合計"));
        config.setComments(statsPath + ".total_joins", List.of("サーバー入室合計回数"));
        config.setComments(statsPath + ".total_playtime_seconds", List.of("サーバー参加合計時間"));
        config.setComments(statsPath + ".photoshoot_participations", List.of("撮影参加合計回数"));
        config.setComments(statsPath + ".total_chats", List.of("チャット合計回数"));
        config.setComments(statsPath + ".w_count", List.of("w合計数"));

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save participant data for " + data.getParticipantId(), e);
        }
    }

    public void registerNewParticipant(ParticipantData data) {
        if (data == null || data.getParticipantId() == null || data.getParticipantId().isEmpty()) {
            return;
        }
        activeParticipants.put(data.getParticipantId(), data);
        for (UUID uuid : data.getAssociatedUuids()) {
            uuidToParticipantMap.put(uuid, data);
        }
        saveParticipant(data);
    }

    public boolean moveParticipantToActive(String participantId) {
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
            dischargedParticipants.put(participantId, data); // revert in case of failure
            return false;
        }
    }

    public boolean moveParticipantToDischarged(String participantId) {
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
            activeParticipants.put(participantId, data); // revert in case of failure
            return false;
        }
    }

    public Set<UUID> getAllAssociatedUuidsFromActive() {
        return uuidToParticipantMap.keySet();
    }

    public Set<UUID> getAssociatedUuidsFromDischarged() {
        return dischargedParticipants.values().stream()
                .flatMap(p -> p.getAssociatedUuids().stream())
                .collect(Collectors.toSet());
    }

    // --- Statistics Methods ---
    public void incrementDeaths(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;
        data.incrementStat("total_deaths");
        saveParticipant(data);
    }

    public void incrementJoins(UUID uuid) {
        long currentTime = System.currentTimeMillis();
        if (lastQuitTimestamps.containsKey(uuid)) {
            long lastQuit = lastQuitTimestamps.get(uuid);
            if (currentTime - lastQuit < 10 * 60 * 1000) {
                return;
            }
        }

        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;
        data.incrementStat("total_joins");
        saveParticipant(data);
    }

    public void addPlaytime(UUID uuid, long secondsToAdd) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;
        long currentPlaytime = data.getStatistics().getOrDefault("total_playtime_seconds", 0L).longValue();
        data.getStatistics().put("total_playtime_seconds", currentPlaytime + secondsToAdd);
        saveParticipant(data);
    }

    public void incrementPhotoshootParticipations(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;
        data.incrementStat("photoshoot_participations");
        saveParticipant(data);
    }

    public void incrementChats(UUID uuid) {
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;
        data.incrementStat("total_chats");
        saveParticipant(data);
    }

    public void incrementWCount(UUID uuid, int amount) {
        if (amount == 0) return;
        ParticipantData data = findOrCreateParticipant(Bukkit.getOfflinePlayer(uuid));
        if (data == null) return;
        long currentWCount = data.getStatistics().getOrDefault("w_count", 0L).longValue();
        data.getStatistics().put("w_count", currentWCount + amount);
        saveParticipant(data);
    }

    // --- Join/Quit Time Tracking ---
    public void recordLoginTime(Player player) {
        loginTimestamps.put(player.getUniqueId(), System.currentTimeMillis());
        findOrCreateParticipant(player);
    }

    public void recordQuitTime(Player player) {
        UUID uuid = player.getUniqueId();
        lastQuitTimestamps.put(uuid, System.currentTimeMillis());

        if (loginTimestamps.containsKey(uuid)) {
            long loginTime = loginTimestamps.remove(uuid);
            long durationMillis = System.currentTimeMillis() - loginTime;
            addPlaytime(uuid, durationMillis / 1000);
        }
    }

    // --- Name Management Logic ---
    public boolean changePlayerName(UUID uuid, String newBaseName, String newLinkedName) {
        ParticipantData data = getParticipant(uuid);
        if (data == null) return false;

        String oldParticipantId = data.getParticipantId();
        String newParticipantId = ParticipantData.generateId(newBaseName, newLinkedName);

        if (oldParticipantId.equals(newParticipantId)) {
            // ID(ファイル名)は変わらないが、大文字小文字などの違いで名前が変わっている可能性があるので保存する
            data.setFullName(newBaseName, newLinkedName);
            saveParticipant(data);
            return true;
        }

        // ID(ファイル名)が変わる場合
        File oldFile = new File(activeDir, oldParticipantId + ".yml");
        File newFile = new File(activeDir, newParticipantId + ".yml");

        if (oldFile.exists()) {
            if (!oldFile.renameTo(newFile)) {
                plugin.getLogger().severe("Failed to rename participant file from '" + oldFile.getName() + "' to '" + newFile.getName() + "'.");
                return false;
            }
        }

        // メモリ上のデータを更新
        activeParticipants.remove(oldParticipantId);
        data.setFullName(newBaseName, newLinkedName);
        activeParticipants.put(newParticipantId, data);

        // ファイルの中身を保存（ファイル名は新しいIDから取得される）
        saveParticipant(data);
        return true;
    }

    public void handleAccountLinkOnJoin(Player player) {
        UUID playerUuid = player.getUniqueId();

        Collection<ParticipantData> currentActiveParticipants = new ArrayList<>(activeParticipants.values());

        for (ParticipantData mainAccountData : currentActiveParticipants) {
            File participantFile = new File(activeDir, mainAccountData.getParticipantId() + ".yml");
            if (!participantFile.exists()) {
                continue;
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(participantFile);
            if (!config.isConfigurationSection("accounts")) {
                continue;
            }

            ConfigurationSection accountsSection = config.getConfigurationSection("accounts");
            for (String accountUuidString : accountsSection.getKeys(false)) {
                if (playerUuid.toString().equalsIgnoreCase(accountUuidString)) {
                    String mainBaseName = mainAccountData.getBaseName();
                    String mainLinkedName = mainAccountData.getLinkedName();

                    ParticipantData altAccountData = findOrCreateParticipant(player);

                    if (!altAccountData.getBaseName().equals(mainBaseName) || !altAccountData.getLinkedName().equals(mainLinkedName)) {
                        plugin.getLogger().info("Linking account " + player.getName() + " to " + mainAccountData.getDisplayName() + ". Updating names.");
                        changePlayerName(playerUuid, mainBaseName, mainLinkedName);
                    }
                    return;
                }
            }
        }
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
}