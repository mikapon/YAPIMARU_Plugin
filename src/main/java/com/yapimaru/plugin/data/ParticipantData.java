package com.yapimaru.plugin.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class ParticipantData {
    private String baseName;
    private String linkedName;
    private final Map<String, Number> statistics = new HashMap<>();
    private final Map<UUID, AccountInfo> accounts = new HashMap<>();

    private final List<String> joinHistory = new ArrayList<>();
    private final List<String> photoshootHistory = new ArrayList<>();
    private final List<Map<String, Object>> playtimeHistory = new ArrayList<>();

    private String lastQuitTime;
    private boolean isOnline;

    public static class AccountInfo {
        private final String name;
        private boolean isOnline;

        public AccountInfo(String name, boolean isOnline) {
            this.name = name;
            this.isOnline = isOnline;
        }

        public String getName() { return name; }
        public boolean isOnline() { return isOnline; }
        public void setOnline(boolean online) { isOnline = online; }
    }

    public ParticipantData(String baseName, String linkedName) {
        this.baseName = baseName;
        this.linkedName = linkedName;
        initializeStats();
    }

    @SuppressWarnings("unchecked")
    public ParticipantData(ConfigurationSection config) {
        this.baseName = config.getString("base_name", "");
        this.linkedName = config.getString("linked_name", "");

        ConfigurationSection accountsSection = config.getConfigurationSection("accounts");
        if (accountsSection != null) {
            for (String uuidStr : accountsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String name = accountsSection.getString(uuidStr + ".name");
                    boolean online = accountsSection.getBoolean(uuidStr + ".online");
                    accounts.put(uuid, new AccountInfo(name, online));
                } catch (IllegalArgumentException e) {
                    // Ignore invalid UUID in config
                }
            }
        }

        Object statsObj = config.get("statistics");
        if (statsObj instanceof MemorySection) {
            Map<String, Object> statsMap = ((MemorySection) statsObj).getValues(false);
            for(Map.Entry<String, Object> entry : statsMap.entrySet()) {
                if(entry.getValue() instanceof Number) {
                    this.statistics.put(entry.getKey(), (Number)entry.getValue());
                }
            }
        }
        initializeStats();

        this.joinHistory.addAll(config.getStringList("join-history"));
        this.photoshootHistory.addAll(config.getStringList("photoshoot-history"));
        List<?> rawPlaytimeHistory = config.getList("playtime-history", new ArrayList<>());
        for (Object item : rawPlaytimeHistory) {
            if (item instanceof Map) {
                this.playtimeHistory.add((Map<String, Object>) item);
            }
        }

        this.lastQuitTime = config.getString("last-quit-time");
        this.isOnline = config.getBoolean("is-online");
    }

    private void initializeStats() {
        statistics.putIfAbsent("total_deaths", 0);
        statistics.putIfAbsent("total_joins", 0);
        statistics.putIfAbsent("total_playtime_seconds", 0L);
        statistics.putIfAbsent("photoshoot_participations", 0);
        statistics.putIfAbsent("total_chats", 0);
        statistics.putIfAbsent("w_count", 0);
    }

    public String getParticipantId() {
        return generateId(this.baseName, this.linkedName);
    }

    public static String generateId(String baseName, String linkedName) {
        String id = (linkedName != null && !linkedName.isEmpty()) ? linkedName + "_" + baseName : baseName;
        return id.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public String getDisplayName() {
        return (linkedName != null && !linkedName.isEmpty()) ? linkedName + "(" + baseName + ")" : baseName;
    }

    public String getBaseName() { return baseName; }
    public String getLinkedName() { return linkedName; }
    public Map<String, Number> getStatistics() { return statistics; }
    public Map<UUID, AccountInfo> getAccounts() { return accounts; }
    public Set<UUID> getAssociatedUuids() { return new HashSet<>(accounts.keySet()); }
    public List<String> getJoinHistory() { return joinHistory; }
    public List<String> getPhotoshootHistory() { return photoshootHistory; }
    public List<Map<String, Object>> getPlaytimeHistory() { return playtimeHistory; }
    public String getLastQuitTime() { return lastQuitTime; }
    public boolean isOnline() { return isOnline; }

    public void setFullName(String newBaseName, String newLinkedName) {
        this.baseName = newBaseName;
        this.linkedName = newLinkedName;
    }

    public void setOnlineStatus(boolean online) { this.isOnline = online; }

    public void setLastQuitTime(LocalDateTime time) {
        this.lastQuitTime = time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public LocalDateTime getLastQuitTimeAsDate() {
        if (lastQuitTime == null || lastQuitTime.isEmpty()) return null;
        try {
            return LocalDateTime.parse(lastQuitTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public void addAccount(UUID uuid, String name) {
        accounts.computeIfAbsent(uuid, u -> new AccountInfo(name, false));
    }

    public void incrementStat(String key, Number amount) {
        Number value = statistics.getOrDefault(key, 0);
        if (value instanceof Long || amount instanceof Long) {
            statistics.put(key, value.longValue() + amount.longValue());
        } else if (value instanceof Integer || amount instanceof Integer) {
            statistics.put(key, value.intValue() + amount.intValue());
        }
    }

    public void addPlaytime(long seconds) {
        incrementStat("total_playtime_seconds", seconds);
    }

    public void addHistoryEvent(String eventType, LocalDateTime timestamp) {
        String formattedTime = timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        switch (eventType.toLowerCase()) {
            case "join":
                joinHistory.add(formattedTime);
                break;
            case "photoshoot":
                photoshootHistory.add(formattedTime);
                break;
        }
    }

    public void addPlaytimeToHistory(long durationSeconds) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("date", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        entry.put("duration_seconds", durationSeconds);
        playtimeHistory.add(entry);
    }

    public void addTimeToLastPlaytime(long secondsToAdd) {
        if (playtimeHistory.isEmpty()) {
            addPlaytimeToHistory(secondsToAdd);
            return;
        }
        Map<String, Object> lastEntry = playtimeHistory.get(playtimeHistory.size() - 1);
        long currentDuration = ((Number) lastEntry.getOrDefault("duration_seconds", 0L)).longValue();
        lastEntry.put("duration_seconds", currentDuration + secondsToAdd);
    }

    public void addLogData(ParticipantData logData) {
        logData.getStatistics().forEach(this::incrementStat);
        this.joinHistory.addAll(logData.getJoinHistory());
        this.photoshootHistory.addAll(logData.getPhotoshootHistory());
        this.playtimeHistory.addAll(logData.getPlaytimeHistory());
    }
}