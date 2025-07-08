package com.yapimaru.plugin.logic;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.ConfigManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogPatternMatcher {

    private static final Pattern JOIN_PATTERN = Pattern.compile("(.+?)(?:\\[.+])? (joined the game|logged in with entity id|がマッチングしました)");
    private static final Pattern LEFT_GAME_PATTERN = Pattern.compile("(.+?) (left the game|が退出しました)");
    private static final Pattern DEATH_PATTERN = Pattern.compile("(?<!<)(?<!\\w)(?!Villager\\b)(?!Librarian\\b)(?!Farmer\\b)(?!Shepherd\\b)(?!Nitwit\\b)(?!Leatherworker\\b)(?!Weaponsmith\\b)(?!Fisherman\\b)(?!You\\b)(?!adomin\\b)([a-zA-Z0-9_]{3,16})(?![>\\w]) (was shot|was pummeled|was pricked|walked into a cactus|drowned|experienced kinetic energy|blew up|was blown up|was killed|hit the ground|fell|went up in flames|burned|walked into fire|was burnt|froze to death|starved to death|suffocated in a wall|was squashed|was impaled|died|withered away|tried to swim in lava)");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<(.+?)> (.+)");
    private static final Pattern PHOTOGRAPHING_PATTERN = Pattern.compile(".+? issued server command: /photographing on");
    private static final Pattern UUID_PATTERN = Pattern.compile("UUID of player (.+?) is (.+)");
    private static final Pattern FLOODGATE_UUID_PATTERN = Pattern.compile("\\[floodgate] Floodgate player \\[(.+?)] with XUID \\[.+] logged in for (.+?) \\(online\\) using account with username .+? and UUID: (.+)");


    private final List<Pattern> customDeathPatterns = new ArrayList<>();
    private final List<Pattern> customChatPatterns = new ArrayList<>();

    public LogPatternMatcher(YAPIMARU_Plugin plugin) {
        ConfigManager configManager = plugin.getConfigManager();

        for (String patternStr : configManager.getCustomPatterns().getOrDefault("death", Collections.emptyList())) {
            try {
                customDeathPatterns.add(Pattern.compile(patternStr));
            } catch (Exception e) {
                plugin.getLogger().warning("カスタムDeathパターンが無効です: " + patternStr);
            }
        }
        for (String patternStr : configManager.getCustomPatterns().getOrDefault("chat", Collections.emptyList())) {
            try {
                customChatPatterns.add(Pattern.compile(patternStr));
            } catch (Exception e) {
                plugin.getLogger().warning("カスタムChatパターンが無効です: " + patternStr);
            }
        }
    }

    public enum EventType {
        JOIN, LEAVE, DEATH, CHAT, PHOTOGRAPH_ON, UUID_INFO, FLOODGATE_INFO, UNKNOWN
    }

    public static class MatchResult {
        public final EventType type;
        public final String primaryGroup;
        public final String secondaryGroup;

        public MatchResult(EventType type, String primaryGroup, String secondaryGroup) {
            this.type = type;
            this.primaryGroup = primaryGroup;
            this.secondaryGroup = secondaryGroup;
        }
    }

    public MatchResult match(String content) {
        Matcher matcher;

        matcher = UUID_PATTERN.matcher(content);
        if (matcher.find()) {
            return new MatchResult(EventType.UUID_INFO, matcher.group(1), matcher.group(2));
        }
        matcher = FLOODGATE_UUID_PATTERN.matcher(content);
        if(matcher.find()){
            return new MatchResult(EventType.FLOODGATE_INFO, matcher.group(2), matcher.group(3));
        }
        matcher = JOIN_PATTERN.matcher(content);
        if (matcher.find()) {
            return new MatchResult(EventType.JOIN, matcher.group(1), null);
        }
        matcher = LEFT_GAME_PATTERN.matcher(content);
        if (matcher.find()) {
            return new MatchResult(EventType.LEAVE, matcher.group(1), null);
        }
        matcher = PHOTOGRAPHING_PATTERN.matcher(content);
        if (matcher.find()) {
            return new MatchResult(EventType.PHOTOGRAPH_ON, null, null);
        }
        matcher = CHAT_PATTERN.matcher(content);
        if (matcher.find()) {
            return new MatchResult(EventType.CHAT, matcher.group(1), matcher.group(2));
        }
        for (Pattern p : customChatPatterns) {
            matcher = p.matcher(content);
            if (matcher.find()) {
                return new MatchResult(EventType.CHAT, matcher.group(1), matcher.group(2));
            }
        }
        matcher = DEATH_PATTERN.matcher(content);
        if (matcher.find()) {
            return new MatchResult(EventType.DEATH, matcher.group(1), null);
        }
        for (Pattern p : customDeathPatterns) {
            matcher = p.matcher(content);
            if (matcher.find()) {
                return new MatchResult(EventType.DEATH, matcher.group(1), null);
            }
        }

        return new MatchResult(EventType.UNKNOWN, null, null);
    }
}