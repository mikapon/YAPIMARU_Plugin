package com.yapimaru.plugin.data;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public class ParticipantData {
    private String baseName;
    private String linkedName;
    private final Set<UUID> associatedUuids = new HashSet<>();
    private final Map<String, Number> statistics = new HashMap<>();
    private final Map<UUID, String> uuidToNameMap = new HashMap<>();

    public ParticipantData(String baseName, String linkedName) {
        this.baseName = baseName;
        this.linkedName = linkedName;
        initializeStats();
    }

    public ParticipantData(ConfigurationSection config) {
        this.baseName = config.getString("base_name", "");
        this.linkedName = config.getString("linked_name", "");
        config.getStringList("associated-uuids").forEach(uuidStr -> associatedUuids.add(UUID.fromString(uuidStr)));

        ConfigurationSection statsSection = config.getConfigurationSection("statistics");
        if (statsSection != null) {
            for (String key : statsSection.getKeys(false)) {
                statistics.put(key, (Number) statsSection.get(key));
            }
        }

        ConfigurationSection uuidNameSection = config.getConfigurationSection("uuid-to-name");
        if (uuidNameSection != null) {
            for (String uuidStr : uuidNameSection.getKeys(false)) {
                uuidToNameMap.put(UUID.fromString(uuidStr), uuidNameSection.getString(uuidStr));
            }
        }

        initializeStats();
    }

    private void initializeStats() {
        statistics.putIfAbsent("total_deaths", 0);
        statistics.putIfAbsent("total_joins", 0);
        statistics.putIfAbsent("total_playtime_seconds", 0L);
        statistics.putIfAbsent("photoshoot_participations", 0);
        statistics.putIfAbsent("total_chats", 0);
        statistics.putIfAbsent("w_count", 0);
    }

    public void resetStats() {
        statistics.put("total_deaths", 0);
        statistics.put("total_joins", 0);
        statistics.put("total_playtime_seconds", 0L);
        statistics.put("photoshoot_participations", 0);
        statistics.put("total_chats", 0);
        statistics.put("w_count", 0);
    }

    public String getParticipantId() {
        return generateId(this.baseName, this.linkedName);
    }

    public static String generateId(String baseName, String linkedName) {
        String id;
        if (linkedName != null && !linkedName.isEmpty()) {
            id = linkedName + "_" + baseName;
        } else {
            id = baseName;
        }
        return id.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public String getDisplayName() {
        if (linkedName != null && !linkedName.isEmpty()) {
            return linkedName + "(" + baseName + ")";
        }
        return baseName;
    }

    // Getters
    public String getBaseName() { return baseName; }
    public String getLinkedName() { return linkedName; }
    public Set<UUID> getAssociatedUuids() { return associatedUuids; }
    public Map<String, Number> getStatistics() { return statistics; }
    public Map<UUID, String> getUuidToNameMap() { return uuidToNameMap; }

    public void addAssociatedUuid(UUID uuid) {
        this.associatedUuids.add(uuid);
    }
    public void removeAssociatedUuid(UUID uuid) {
        this.associatedUuids.remove(uuid);
    }

    public void incrementStat(String key) {
        Number value = statistics.getOrDefault(key, 0);
        if (value instanceof Long) {
            statistics.put(key, value.longValue() + 1);
        } else {
            statistics.put(key, value.intValue() + 1);
        }
    }

    public void setFullName(String newBaseName, String newLinkedName) {
        this.baseName = newBaseName;
        this.linkedName = newLinkedName;
    }
}