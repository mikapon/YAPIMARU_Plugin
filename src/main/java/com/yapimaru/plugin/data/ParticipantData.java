package com.yapimaru.plugin.data;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ParticipantData {
    private String baseName;
    private String linkedName;
    private final Map<UUID, AccountInfo> accounts = new HashMap<>();
    private final Map<String, Number> statistics = new HashMap<>();
    private final List<String> joinHistory = new ArrayList<>();
    private String lastQuitTime = null;
    private final List<Long> playtimeHistory = new ArrayList<>();
    private final List<String> photoshootHistory = new ArrayList<>();
    private static final DateTimeFormatter HISTORY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss");


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

    public ParticipantData(ConfigurationSection config) {
        this.baseName = config.getString("base_name", "");
        this.linkedName = config.getString("linked_name", "");

        ConfigurationSection accountsSection = config.getConfigurationSection("accounts");
        if (accountsSection != null) {
            for (String uuidStr : accountsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String name = accountsSection.getString(uuidStr + ".name");
                    boolean online = accountsSection.getBoolean(uuidStr + ".online", false);
                    this.accounts.put(uuid, new AccountInfo(name, online));
                } catch (IllegalArgumentException e) {
                    // Invalid UUID, ignore
                }
            }
        }

        ConfigurationSection statsSection = config.getConfigurationSection("statistics");
        if (statsSection != null) {
            for (String key : statsSection.getKeys(false)) {
                statistics.put(key, (Number) statsSection.get(key));
            }
        }

        this.joinHistory.addAll(config.getStringList("join-history"));
        this.photoshootHistory.addAll(config.getStringList("photoshoot-history"));
        this.lastQuitTime = config.getString("last-quit-time", null);
        this.playtimeHistory.addAll(config.getLongList("playtime-history"));

        initializeStats();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("participant_id", getParticipantId()); // ★デバッグ用にIDを追加
        map.put("base_name", baseName);
        map.put("linked_name", linkedName);

        Map<String, Object> accountsMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, AccountInfo> entry : accounts.entrySet()) {
            Map<String, Object> accountDetails = new LinkedHashMap<>();
            accountDetails.put("name", entry.getValue().getName());
            accountDetails.put("online", entry.getValue().isOnline());
            accountsMap.put(entry.getKey().toString(), accountDetails);
        }
        map.put("accounts", accountsMap);
        map.put("statistics", statistics);
        map.put("join-history", joinHistory);
        map.put("photoshoot-history", photoshootHistory);
        map.put("last-quit-time", lastQuitTime);
        map.put("playtime-history", playtimeHistory);
        map.put("is-online", isOnline());
        return map;
    }

    private void initializeStats() {
        statistics.putIfAbsent("total_deaths", 0);
        statistics.putIfAbsent("total_joins", 0);
        statistics.putIfAbsent("total_playtime_seconds", 0L);
        statistics.putIfAbsent("photoshoot_participations", 0);
        statistics.putIfAbsent("total_chats", 0);
        statistics.putIfAbsent("w_count", 0);
    }

    public void addHistoryEvent(String type, LocalDateTime timestamp) {
        List<String> historyList;
        if ("join".equalsIgnoreCase(type)) {
            historyList = this.joinHistory;
        } else if ("photoshoot".equalsIgnoreCase(type)) {
            historyList = this.photoshootHistory;
        } else {
            return;
        }

        String formattedTimestamp = timestamp.format(HISTORY_FORMATTER);
        if (!historyList.contains(formattedTimestamp)) {
            historyList.add(formattedTimestamp);
            historyList.sort(Comparator.naturalOrder());

            if (historyList.size() > 10) {
                historyList.remove(0);
            }
        }
    }


    public void resetStats() {
        statistics.put("total_deaths", 0);
        statistics.put("total_joins", 0);
        statistics.put("total_playtime_seconds", 0L);
        statistics.put("photoshoot_participations", 0);
        statistics.put("total_chats", 0);
        statistics.put("w_count", 0);
        joinHistory.clear();
        photoshootHistory.clear();
        lastQuitTime = null;
        playtimeHistory.clear();
        accounts.values().forEach(acc -> acc.setOnline(false));
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
    public Map<UUID, AccountInfo> getAccounts() { return accounts; }
    public Set<UUID> getAssociatedUuids() { return accounts.keySet(); }
    public Map<String, Number> getStatistics() { return statistics; }
    public List<String> getJoinHistory() { return joinHistory; }
    public List<String> getPhotoshootHistory() { return photoshootHistory; }
    public String getLastQuitTime() { return lastQuitTime; }
    public LocalDateTime getLastQuitTimeAsDate() {
        if (lastQuitTime == null) return null;
        try {
            return LocalDateTime.parse(lastQuitTime, HISTORY_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }
    public List<Long> getPlaytimeHistory() { return playtimeHistory; }

    public boolean isOnline() {
        return accounts.values().stream().anyMatch(AccountInfo::isOnline);
    }

    public void setLastQuitTime(LocalDateTime timestamp) {
        this.lastQuitTime = (timestamp != null) ? timestamp.format(HISTORY_FORMATTER) : null;
    }

    public void addPlaytimeToHistory(long seconds) {
        this.playtimeHistory.add(seconds);
        if (this.playtimeHistory.size() > 10) {
            this.playtimeHistory.remove(0);
        }
    }

    public void addTimeToLastPlaytime(long seconds) {
        if (playtimeHistory.isEmpty()) {
            addPlaytimeToHistory(seconds);
        } else {
            int lastIndex = playtimeHistory.size() - 1;
            long newTime = playtimeHistory.get(lastIndex) + seconds;
            playtimeHistory.set(lastIndex, newTime);
        }
    }

    public void addAccount(UUID uuid, String name) {
        if (name == null || uuid == null) return;
        this.accounts.put(uuid, new AccountInfo(name, false));
    }

    public void incrementStat(String key, int amount) {
        Number value = statistics.getOrDefault(key, 0);
        if (value instanceof Long) {
            statistics.put(key, value.longValue() + amount);
        } else {
            statistics.put(key, value.intValue() + amount);
        }
    }

    public void addPlaytime(long seconds) {
        long current = statistics.getOrDefault("total_playtime_seconds", 0L).longValue();
        statistics.put("total_playtime_seconds", current + seconds);
    }

    public void setFullName(String newBaseName, String newLinkedName) {
        this.baseName = newBaseName;
        this.linkedName = newLinkedName;
    }
}