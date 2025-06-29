package com.yapimaru.plugin.data;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class VoteData {

    public enum VoteMode { ANONYMITY, OPEN }

    private final String pollId;
    private final String question;
    private final List<String> options;
    private final VoteMode mode;
    private final boolean multiChoice;
    private long endTime; // 0 for indefinite

    private final Map<Integer, Set<UUID>> votes = new HashMap<>();
    private final Map<UUID, Set<Integer>> playerVotes = new HashMap<>();

    public VoteData(String pollId, String question, List<String> options, VoteMode mode, boolean multiChoice, long durationMillis) {
        this.pollId = pollId;
        this.question = question;
        this.options = new ArrayList<>(options);
        this.mode = mode;
        this.multiChoice = multiChoice;
        this.endTime = (durationMillis > 0) ? System.currentTimeMillis() + durationMillis : 0;

        for (int i = 0; i < options.size(); i++) {
            votes.put(i + 1, new HashSet<>());
        }
    }

    // Getters
    public String getPollId() { return pollId; }
    public String getQuestion() { return question; }
    public List<String> getOptions() { return new ArrayList<>(options); }
    public VoteMode getMode() { return mode; }
    public boolean isMultiChoice() { return multiChoice; }
    public long getEndTime() { return endTime; }
    public Map<Integer, Set<UUID>> getVotes() { return votes; }
    public Map<UUID, Set<Integer>> getPlayerVotes() { return playerVotes; }

    public boolean hasVoted(UUID playerUuid) {
        return playerVotes.containsKey(playerUuid) && !playerVotes.get(playerUuid).isEmpty();
    }

    public boolean vote(UUID playerUuid, int choice) {
        if (choice < 1 || choice > options.size()) {
            return false; // Invalid choice
        }

        if (!multiChoice && playerVotes.containsKey(playerUuid)) {
            playerVotes.get(playerUuid).forEach(previousChoice -> {
                votes.get(previousChoice).remove(playerUuid);
            });
            playerVotes.get(playerUuid).clear();
        }

        playerVotes.computeIfAbsent(playerUuid, k -> new HashSet<>());

        if (playerVotes.get(playerUuid).contains(choice)) {
            playerVotes.get(playerUuid).remove(choice);
            votes.get(choice).remove(playerUuid);
        } else {
            playerVotes.get(playerUuid).add(choice);
            votes.get(choice).add(playerUuid);
        }

        return true;
    }

    public boolean isExpired() {
        return endTime > 0 && System.currentTimeMillis() > endTime;
    }

    public void save(File file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", pollId);
        config.set("question", question);
        config.set("options", options);
        config.set("mode", mode.name());
        config.set("multi-choice", multiChoice);
        config.set("end-time", endTime);

        for (Map.Entry<Integer, Set<UUID>> entry : votes.entrySet()) {
            List<String> uuidStrings = entry.getValue().stream().map(UUID::toString).collect(Collectors.toList());
            config.set("results." + entry.getKey(), uuidStrings);
        }

        config.set("player-votes", null);
        for (Map.Entry<UUID, Set<Integer>> entry : playerVotes.entrySet()) {
            config.set("player-votes." + entry.getKey().toString(), new ArrayList<>(entry.getValue()));
        }

        config.save(file);
    }

    public static VoteData load(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String id = config.getString("id");
        String question = config.getString("question");
        List<String> options = config.getStringList("options");
        VoteMode mode = VoteMode.valueOf(config.getString("mode", "OPEN"));
        boolean multiChoice = config.getBoolean("multi-choice", false);
        long endTime = config.getLong("end-time", 0);

        VoteData voteData = new VoteData(id, question, options, mode, multiChoice, 0);
        voteData.endTime = endTime;

        if (config.isConfigurationSection("results")) {
            for (String key : config.getConfigurationSection("results").getKeys(false)) {
                try {
                    int choice = Integer.parseInt(key);
                    Set<UUID> voters = config.getStringList("results." + key).stream()
                            .map(UUID::fromString)
                            .collect(Collectors.toSet());
                    voteData.votes.put(choice, voters);
                } catch (IllegalArgumentException e) { // ★★★ 修正点: NumberFormatExceptionはIllegalArgumentExceptionに含まれるので、一本化 ★★★
                    // Ignore invalid keys
                }
            }
        }

        if (config.isConfigurationSection("player-votes")) {
            for (String uuidString : config.getConfigurationSection("player-votes").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    Set<Integer> choices = new HashSet<>(config.getIntegerList("player-votes." + uuidString));
                    voteData.playerVotes.put(uuid, choices);
                } catch (IllegalArgumentException e) {
                    // Ignore invalid UUIDs
                }
            }
        }

        return voteData;
    }
}