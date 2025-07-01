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
            return;
        }

        plugin.getLogger().info("Starting migration of old player data from config.yml...");
        int newProfiles = 0;
        int mergedUuids = 0;

        // A temporary map to hold the final state of all participant data.
        // Key: ParticipantID (filename), Value: ParticipantData
        Map<String, ParticipantData> finalDataMap = new HashMap<>();

        // 1. Pre-populate the map with data from existing files
        participantManager.getActiveParticipants().forEach(data -> finalDataMap.put(data.getParticipantId(), data));
        participantManager.getDischargedParticipants().forEach(data -> finalDataMap.put(data.getParticipantId(), data));

        // 2. Process players from config.yml
        for (String uuidStr : oldPlayersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);

                // If this UUID is already linked to a participant, skip it.
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
                    // A profile for this person already exists, so add/merge this UUID into it.
                    targetData.addAssociatedUuid(uuid);
                    mergedUuids++;
                } else {
                    // This is a completely new person. Create a new profile.
                    targetData = new ParticipantData(baseName, linkedName);
                    targetData.addAssociatedUuid(uuid);
                    finalDataMap.put(participantId, targetData);
                    newProfiles++;
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to migrate player data for UUID " + uuidStr, e);
            }
        }

        // 3. Save all changes
        if (newProfiles > 0 || mergedUuids > 0) {
            plugin.getLogger().info("Saving migrated data...");
            for (ParticipantData data : finalDataMap.values()) {
                participantManager.registerNewParticipant(data);
            }
            plugin.getLogger().info("Migration complete: " + newProfiles + " new profiles created, " + mergedUuids + " UUIDs merged.");
        }

        // 4. Clear the old section from config.yml
        plugin.getLogger().info("Clearing old player data from config.yml...");
        config.set("players", null);
        plugin.saveConfig();
    }
}