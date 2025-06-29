package com.yapimaru.plugin.data;

import java.util.*;

public class VoteData {

    private final int numericId;
    private final String directoryName;
    private final String question;
    private final List<String> options;
    private final boolean multiChoice;
    private final long endTime; // 0 for indefinite

    private final Map<Integer, Set<UUID>> votes = new HashMap<>();
    private final Map<UUID, Set<Integer>> playerVotes = new HashMap<>();

    public VoteData(int numericId, String directoryName, String question, List<String> options, boolean multiChoice, long durationMillis) {
        this.numericId = numericId;
        this.directoryName = directoryName;
        this.question = question;
        this.options = new ArrayList<>(options);
        this.multiChoice = multiChoice;
        this.endTime = (durationMillis > 0) ? System.currentTimeMillis() + durationMillis : 0;

        for (int i = 0; i < options.size(); i++) {
            votes.put(i + 1, new HashSet<>());
        }
    }

    // Getters
    public int getNumericId() { return numericId; }
    public String getDirectoryName() { return directoryName; }
    public String getQuestion() { return question; }
    public List<String> getOptions() { return new ArrayList<>(options); }
    public boolean isMultiChoice() { return multiChoice; }
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
            playerVotes.get(playerUuid).forEach(previousChoice -> votes.get(previousChoice).remove(playerUuid));
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
}