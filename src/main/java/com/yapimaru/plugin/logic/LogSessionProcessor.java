package com.yapimaru.plugin.logic;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ConfigManager;
import com.yapimaru.plugin.managers.ParticipantManager;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class LogSessionProcessor {

    private final YAPIMARU_Plugin plugin;
    private final List<File> sessionFiles;
    private final LogPatternMatcher patternMatcher;
    private final ParticipantManager participantManager;
    private final ConfigManager configManager;

    private final Map<String, ParticipantData> sessionParticipants = new HashMap<>();
    private final Map<String, String> nameToParticipantIdMap = new HashMap<>();
    private final Map<UUID, LocalDateTime> accountLoginTimes = new HashMap<>();
    private final LocalDate sessionDate;

    public LogSessionProcessor(YAPIMARU_Plugin plugin, List<File> sessionFiles) {
        this.plugin = plugin;
        this.sessionFiles = sessionFiles;
        this.patternMatcher = new LogPatternMatcher(plugin);
        this.participantManager = plugin.getParticipantManager();
        this.configManager = plugin.getConfigManager();
        this.sessionDate = sessionFiles.isEmpty() ? LocalDate.now() : LogAddExecutor.getFileTimestampSafe(sessionFiles.get(0));
    }

    public Map<String, ParticipantData> process() throws Exception {
        long lineCount = 0;
        long throttleMillis = getThrottleMillis();
        LogLine lastLine = null;

        try (MergedLogIterator iterator = new MergedLogIterator(sessionFiles)) {
            while (iterator.hasNext()) {
                LogLine line = iterator.next();
                if (line == null) continue;
                lastLine = line;

                handleLine(line);

                lineCount++;
                if (throttleMillis > 0 && lineCount % 1000 == 0) {
                    Thread.sleep(throttleMillis);
                }
            }
        } catch(Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Log processing failed in MergedLogIterator", e);
            throw e;
        }

        if (lastLine != null && lastLine.getTimestamp() != null) {
            LocalDateTime endOfSessionTime = LocalDateTime.of(this.sessionDate, lastLine.getTimestamp());
            forceLogoutAll(endOfSessionTime);
        }

        return sessionParticipants;
    }

    private void handleLine(LogLine line) {
        if(line.isContentOnly() || line.getTimestamp() == null) return;

        LogPatternMatcher.MatchResult result = patternMatcher.match(line.getContent());

        if (result.type == LogPatternMatcher.EventType.UNKNOWN) return;

        if (result.type == LogPatternMatcher.EventType.UUID_INFO || result.type == LogPatternMatcher.EventType.FLOODGATE_INFO) {
            try {
                mapNameToParticipant(result.primaryGroup, UUID.fromString(result.secondaryGroup));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID format for player " + result.primaryGroup + ": " + result.secondaryGroup);
            }
            return;
        }

        ParticipantData participant = null;
        if(result.type != LogPatternMatcher.EventType.PHOTOGRAPH_ON){
            participant = getParticipantDataForName(result.primaryGroup);
            if(participant == null) return;
        }

        LocalDateTime eventTime = LocalDateTime.of(this.sessionDate, line.getTimestamp());

        switch (result.type) {
            case JOIN -> handleJoin(participant, eventTime);
            case LEAVE -> handleLeave(participant, eventTime);
            case DEATH -> participant.incrementStat("total_deaths", 1);
            case CHAT -> {
                participant.incrementStat("total_chats", 1);
                int wCount = participantManager.calculateWCount(result.secondaryGroup);
                participant.incrementStat("w_count", wCount);
            }
            case PHOTOGRAPH_ON -> handlePhotographOn(eventTime);
        }
    }

    private ParticipantData getParticipantDataForName(String name) {
        if(name == null || configManager.getIgnoredNames().contains(name)) return null;

        String participantId = nameToParticipantIdMap.get(name);
        if (participantId == null) {
            ParticipantData foundData = participantManager.findParticipantByAnyName(name)
                    .orElseGet(() -> {
                        ParticipantData newData = new ParticipantData(name, "");
                        participantManager.registerNewParticipant(newData);
                        return newData;
                    });
            participantId = foundData.getParticipantId();
            nameToParticipantIdMap.put(name, participantId);
        }

        String finalParticipantId = participantId;
        return sessionParticipants.computeIfAbsent(finalParticipantId, id -> {
            ParticipantData pData = participantManager.getLoadedParticipant(id);
            if (pData != null) {
                ParticipantData sessionData = new ParticipantData(pData.getBaseName(), pData.getLinkedName());
                pData.getAccounts().forEach((uuid, accInfo) -> sessionData.getAccounts().put(uuid, new ParticipantData.AccountInfo(accInfo.getName(), false)));
                return sessionData;
            }
            return null;
        });
    }

    private void mapNameToParticipant(String name, UUID uuid) {
        ParticipantData participant = participantManager.findOrCreateParticipant(plugin.getServer().getOfflinePlayer(uuid));
        if (participant != null) {
            nameToParticipantIdMap.put(name, participant.getParticipantId());
            participant.addAccount(uuid, name);
        }
    }

    private void handleJoin(ParticipantData participant, LocalDateTime eventTime) {
        UUID playerUuid = participant.getAssociatedUuids().stream().findFirst().orElse(null);
        if (playerUuid == null) return;
        boolean wasPersonOffline = participant.getAccounts().values().stream().noneMatch(ParticipantData.AccountInfo::isOnline);
        participant.getAccounts().computeIfAbsent(playerUuid, u -> new ParticipantData.AccountInfo(plugin.getServer().getOfflinePlayer(u).getName(), false)).setOnline(true);
        accountLoginTimes.put(playerUuid, eventTime);
        if (wasPersonOffline) {
            participant.incrementStat("total_joins", 1);
            participant.addHistoryEvent("join", eventTime);
        }
    }

    private void handleLeave(ParticipantData participant, LocalDateTime eventTime) {
        UUID playerUuid = participant.getAssociatedUuids().stream().findFirst().orElse(null);
        if (playerUuid == null || !accountLoginTimes.containsKey(playerUuid)) return;
        LocalDateTime loginTime = accountLoginTimes.remove(playerUuid);
        long durationSeconds = java.time.Duration.between(loginTime, eventTime).getSeconds();
        if(durationSeconds > 0){
            participant.addPlaytime(durationSeconds);
            participant.addPlaytimeToHistory(durationSeconds);
        }
        if(participant.getAccounts().get(playerUuid) != null) {
            participant.getAccounts().get(playerUuid).setOnline(false);
        }
        boolean isPersonNowOffline = participant.getAccounts().values().stream().noneMatch(ParticipantData.AccountInfo::isOnline);
        if (isPersonNowOffline) {
            participant.setLastQuitTime(eventTime);
        }
    }

    private void handlePhotographOn(LocalDateTime eventTime) {
        sessionParticipants.values().stream()
                .filter(p -> p.getAccounts().values().stream().anyMatch(ParticipantData.AccountInfo::isOnline))
                .forEach(p -> {
                    p.incrementStat("photoshoot_participations", 1);
                    p.addHistoryEvent("photoshoot", eventTime);
                });
    }

    private void forceLogoutAll(LocalDateTime sessionEndTime) {
        for (UUID uuid : new ArrayList<>(accountLoginTimes.keySet())) {
            ParticipantData participant = participantManager.getParticipant(uuid);
            if (participant != null && sessionParticipants.containsKey(participant.getParticipantId())) {
                handleLeave(sessionParticipants.get(participant.getParticipantId()), sessionEndTime);
            }
        }
    }

    private long getThrottleMillis() {
        return switch (configManager.getProcessingIntensity().toLowerCase()) {
            case "medium" -> 20;
            case "low" -> 100;
            default -> 0;
        };
    }
}