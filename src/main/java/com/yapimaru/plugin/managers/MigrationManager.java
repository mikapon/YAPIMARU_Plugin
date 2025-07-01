package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles the one-time migration of player data from config.yml to individual files.
 */
public class MigrationManager {

    public void migrate(YAPIMARU_Plugin plugin, ParticipantManager participantManager) {
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection oldPlayersSection = config.getConfigurationSection("players");

        if (oldPlayersSection == null || oldPlayersSection.getKeys(false).isEmpty()) {
            return; // 移行する古いデータはありません。
        }

        plugin.getLogger().info("Starting migration of old player data from config.yml...");
        int newProfiles = 0;
        int mergedUuids = 0;

        // 最終的な参加者データを保持するための一時的なマップ
        // Key: ParticipantID (ファイル名), Value: ParticipantData
        Map<String, ParticipantData> finalDataMap = new HashMap<>();

        // 1. 既存のファイルからデータを事前に入力し、マージできるようにする
        participantManager.getActiveParticipants().forEach(data -> finalDataMap.put(data.getParticipantId(), data));
        participantManager.getDischargedParticipants().forEach(data -> finalDataMap.put(data.getParticipantId(), data));

        // 2. config.yml のプレイヤーを処理する
        for (String uuidStr : oldPlayersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);

                // このUUIDが既に新しいシステムの参加者にリンクされている場合はスキップ
                if (participantManager.getParticipant(uuid) != null) {
                    continue;
                }

                String baseName = oldPlayersSection.getString(uuidStr + ".base_name");
                String linkedName = oldPlayersSection.getString(uuidStr + ".linked_name", "");

                if (baseName == null || baseName.isEmpty()) {
                    plugin.getLogger().warning("Skipping migration for UUID " + uuidStr + " due to missing base_name.");
                    continue;
                }

                String participantId = ParticipantData.generateId(baseName, linkedName);

                ParticipantData targetData = finalDataMap.get(participantId);

                if (targetData != null) {
                    // この人物のプロフィールは既に存在するため、このUUIDを追加/マージする
                    targetData.addAssociatedUuid(uuid);
                    mergedUuids++;
                } else {
                    // 全く新しい人物。新しいプロフィールを作成する
                    targetData = new ParticipantData(baseName, linkedName);
                    targetData.addAssociatedUuid(uuid);
                    finalDataMap.put(participantId, targetData);
                    newProfiles++;
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to migrate player data for UUID " + uuidStr, e);
            }
        }

        // 3. 全ての変更を保存する
        if (newProfiles > 0 || mergedUuids > 0) {
            plugin.getLogger().info("Saving migrated data...");
            for (ParticipantData data : finalDataMap.values()) {
                // デフォルトで "active" ディレクトリに保存するメソッドを使用
                participantManager.registerNewParticipant(data);
            }
            plugin.getLogger().info("Migration complete: " + newProfiles + " new profiles created, " + mergedUuids + " UUIDs merged.");
        }

        // 4. config.yml から古いセクションを削除する
        plugin.getLogger().info("Clearing old player data from config.yml...");
        config.set("players", null);
        plugin.saveConfig();
    }
}