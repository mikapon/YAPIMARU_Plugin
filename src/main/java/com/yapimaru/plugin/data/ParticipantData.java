package com.yapimaru.plugin.data;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ParticipantData {
    private String baseName;
    private String linkedName;
    private final Map<UUID, AccountInfo> accounts = new HashMap<>();
    private final Map<String, Number> statistics = new HashMap<>();
    private final List<String> joinHistory = new ArrayList<>();
    private String lastQuitTime = null;
    private final List<Long> playtimeHistory = new ArrayList<>();
    private final List<String> photoshootHistory = new ArrayList<>();
    private boolean isOnline;
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
        this.isOnline = false;
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
                    if (name != null) {
                        boolean online = accountsSection.getBoolean(uuidStr + ".online", false);
                        this.accounts.put(uuid, new AccountInfo(name, online));
                    }
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
        this.isOnline = config.getBoolean("is-online", false);
        this.playtimeHistory.addAll(config.getLongList("playtime-history"));

        initializeStats();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("participant_id", getParticipantId());
        map.put("source_file", getParticipantId() + ".yml");
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
        map.put("is-online", this.isOnline());
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

    public void addLogData(ParticipantData logData) {
        // 統計情報は、0から再計算された値を加算する
        logData.getStatistics().forEach((key, value) -> {
            Number currentValue = this.statistics.getOrDefault(key, 0);
            if (currentValue instanceof Long || value instanceof Long) {
                this.statistics.put(key, currentValue.longValue() + value.longValue());
            } else {
                this.statistics.put(key, currentValue.intValue() + value.intValue());
            }
        });

        // 履歴は、古い履歴+新しい履歴がマージ済みのリストで「上書き」する
        this.joinHistory.clear();
        this.joinHistory.addAll(logData.getJoinHistory());

        this.photoshootHistory.clear();
        this.photoshootHistory.addAll(logData.getPhotoshootHistory());

        this.playtimeHistory.clear();
        this.playtimeHistory.addAll(logData.getPlaytimeHistory());

        // 最終退出日時は、より新しい方で上書きする
        LocalDateTime existingLQT = this.getLastQuitTimeAsDate();
        LocalDateTime newLQT = logData.getLastQuitTimeAsDate();
        if (newLQT != null && (existingLQT == null || newLQT.isAfter(existingLQT))) {
            this.setLastQuitTime(newLQT);
        }
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
        this.statistics.clear();
        initializeStats();

        joinHistory.clear();
        photoshootHistory.clear();
        playtimeHistory.clear();
        lastQuitTime = null;
        isOnline = false;
        accounts.values().forEach(acc -> acc.setOnline(false));
    }

    public void resetStatsForLog() {
        // 統計情報のみをリセット
        this.statistics.clear();
        initializeStats();

        // オンライン状態をリセット
        accounts.values().forEach(acc -> acc.setOnline(false));
        this.isOnline = false;

        // joinHistory, photoshootHistory, playtimeHistory, lastQuitTime は変更しない
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
        if (this.isOnline) return true;
        return accounts.values().stream().anyMatch(AccountInfo::isOnline);
    }

    public void setOnlineStatus(boolean online) {
        this.isOnline = online;
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
        this.accounts.putIfAbsent(uuid, new AccountInfo(name, false));
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