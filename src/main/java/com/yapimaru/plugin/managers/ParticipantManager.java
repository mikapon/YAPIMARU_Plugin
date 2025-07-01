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
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ParticipantManager {

    private final YAPIMARU_Plugin plugin;
    private final File participantDir;
    private final Map<String, ParticipantData> participants = new HashMap<>(); // Key: participantId (base_linked)
    private final Map<UUID, ParticipantData> uuidToParticipantMap = new HashMap<>();

    // For join/playtime tracking
    private final Map<UUID, Long> loginTimestamps = new HashMap<>();
    private final Map<UUID, Long> lastQuitTimestamps = new HashMap<>();

    public ParticipantManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantDir = new File(plugin.getDataFolder(), "Participant_Information");
        if (!participantDir.exists()) {
            if(!participantDir.mkdirs()) {
                plugin.getLogger().warning("Failed to create Participant_Information directory.");
            }
        }
        loadAllParticipants();
    }

    private void loadAllParticipants() {
        File[] files = participantDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ParticipantData data = new ParticipantData(config);
            participants.put(data.getParticipantId(), data);
            for (UUID uuid : data.getAssociatedUuids()) {
                uuidToParticipantMap.put(uuid, data);
            }
        }
    }

    public ParticipantData getParticipant(UUID uuid) {
        return uuidToParticipantMap.get(uuid);
    }

    public ParticipantData getParticipant(String participantId) {
        return participants.get(participantId);
    }

    public Collection<ParticipantData> getAllParticipants() {
        return participants.values();
    }

    public ParticipantData findOrCreateParticipant(Player player) {
        return findOrCreateParticipant((OfflinePlayer) player);
    }

    public ParticipantData findOrCreateParticipant(OfflinePlayer player) {
        if (uuidToParticipantMap.containsKey(player.getUniqueId())) {
            return uuidToParticipantMap.get(player.getUniqueId());
        }

        String baseName = player.getName() != null ? player.getName() : player.getUniqueId().toString();
        String linkedName = "";

        ParticipantData data = new ParticipantData(baseName, linkedName);
        data.addAssociatedUuid(player.getUniqueId());

        participants.put(data.getParticipantId(), data);
        uuidToParticipantMap.put(player.getUniqueId(), data);
        saveParticipant(data);
        return data;
    }

    // ★★★ 修正箇所 ★★★
    // ファイル保存時にコメントを付与するロジックを追加
    public void saveParticipant(ParticipantData data) {
        File file = new File(participantDir, data.getParticipantId() + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        // データの設定
        config.set("base_name", data.getBaseName());
        config.set("linked_name", data.getLinkedName());
        config.set("associated-uuids", data.getAssociatedUuids().stream().map(UUID::toString).collect(Collectors.toList()));
        data.getStatistics().forEach((key, value) -> config.set("statistics." + key, value));

        // コメントの設定
        config.setComments("base_name", List.of("プレイヤー名"));
        config.setComments("linked_name", List.of("キャラクター名"));
        config.setComments("associated-uuids", List.of("UUID"));
        config.setComments("statistics", List.of("統計情報"));
        config.setComments("statistics.total_deaths", List.of("デス合計"));
        config.setComments("statistics.total_playtime_seconds", List.of("サーバー参加合計時間"));
        config.setComments("statistics.total_joins", List.of("サーバー入室合計回数"));
        config.setComments("statistics.photoshoot_participations", List.of("撮影参加合計回数"));
        config.setComments("statistics.total_chats", List.of("チャット合計回数"));
        config.setComments("statistics.w_count", List.of("w合計数"));

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save participant data for " + data.getParticipantId(), e);
        }
    }

    // --- Statistics Methods ---

    public void incrementDeaths(UUID uuid) {
        ParticipantData data = getParticipant(uuid);
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
        data.incrementStat("total_joins");
        saveParticipant(data);
    }

    public void addPlaytime(UUID uuid, long secondsToAdd) {
        ParticipantData data = getParticipant(uuid);
        if (data == null) return;
        long currentPlaytime = data.getStatistics().getOrDefault("total_playtime_seconds", 0L).longValue();
        data.getStatistics().put("total_playtime_seconds", currentPlaytime + secondsToAdd);
        saveParticipant(data);
    }

    public void incrementPhotoshootParticipations(UUID uuid) {
        ParticipantData data = getParticipant(uuid);
        if (data == null) return;
        data.incrementStat("photoshoot_participations");
        saveParticipant(data);
    }

    public void incrementChats(UUID uuid) {
        ParticipantData data = getParticipant(uuid);
        if (data == null) return;
        data.incrementStat("total_chats");
        saveParticipant(data);
    }

    public void incrementWCount(UUID uuid, int amount) {
        if (amount == 0) return;
        ParticipantData data = getParticipant(uuid);
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

    public void changePlayerName(UUID uuid, String newBaseName, String newLinkedName) {
        ParticipantData oldData = getParticipant(uuid);
        if (oldData == null) return;

        String newParticipantId = ParticipantData.generateId(newBaseName, newLinkedName);

        ParticipantData existingNewData = participants.get(newParticipantId);

        oldData.removeAssociatedUuid(uuid);

        if (existingNewData != null) {
            existingNewData.addAssociatedUuid(uuid);
            uuidToParticipantMap.put(uuid, existingNewData);
            saveParticipant(existingNewData);
        } else {
            ParticipantData newData = new ParticipantData(newBaseName, newLinkedName);
            newData.addAssociatedUuid(uuid);
            participants.put(newParticipantId, newData);
            uuidToParticipantMap.put(uuid, newData);
            saveParticipant(newData);
        }

        if (oldData.getAssociatedUuids().isEmpty()) {
            participants.remove(oldData.getParticipantId());
            File oldFile = new File(participantDir, oldData.getParticipantId() + ".yml");
            if (!oldFile.delete()) {
                plugin.getLogger().warning("Failed to delete empty participant file: " + oldFile.getName());
            }
        } else {
            saveParticipant(oldData);
        }
    }

    public void migrateFromConfig() {
        ConfigurationSection oldPlayersSection = plugin.getConfig().getConfigurationSection("players");
        if (oldPlayersSection == null || oldPlayersSection.getKeys(false).isEmpty()) {
            return;
        }

        plugin.getLogger().info("Starting migration of old name data from config.yml...");

        for (String uuidStr : oldPlayersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                String baseName = oldPlayersSection.getString(uuidStr + ".base_name");
                String linkedName = oldPlayersSection.getString(uuidStr + ".linked_name", "");

                if (baseName == null) continue;

                String participantId = ParticipantData.generateId(baseName, linkedName);
                ParticipantData data = participants.get(participantId);

                if (data == null) {
                    data = new ParticipantData(baseName, linkedName);
                    participants.put(participantId, data);
                }

                data.addAssociatedUuid(uuid);
                uuidToParticipantMap.put(uuid, data);
                saveParticipant(data);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to migrate player data for UUID " + uuidStr + ": " + e.getMessage());
            }
        }

        plugin.getConfig().set("players", null);
        plugin.saveConfig();
        plugin.getLogger().info("Name data migration completed successfully!");
    }
}