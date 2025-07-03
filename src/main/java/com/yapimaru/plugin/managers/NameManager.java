package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.*;

public class NameManager {
    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;

    private VoteManager voteManager;
    private String globallyViewedStat = null;

    public static final Set<String> WOOL_COLOR_NAMES = Set.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    public NameManager(YAPIMARU_Plugin plugin, ParticipantManager participantManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
    }

    public void setVoteManager(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    public void reloadData() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerName(player);
        }
    }

    public void setGloballyViewedStat(String statName) {
        this.globallyViewedStat = statName;
        reloadData();
    }

    public String getDisplayName(UUID uuid) {
        ParticipantData data = participantManager.getParticipant(uuid);
        if (data != null) {
            return data.getDisplayName();
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString();
    }

    public void resetPlayerName(Player targetPlayer) {
        if (targetPlayer == null) return;
        Team team = getPlayerTeam(targetPlayer.getUniqueId());
        if (team == null) return;

        targetPlayer.setDisplayName(targetPlayer.getName());
        targetPlayer.setPlayerListName(targetPlayer.getName());
        team.setPrefix("");
        team.setSuffix("");
        team.setColor(ChatColor.WHITE);
    }

    public void updatePlayerName(Player targetPlayer) {
        if (targetPlayer == null) return;

        ParticipantData data = participantManager.findOrCreateParticipant(targetPlayer);
        if (data == null) return;

        Team team = getPlayerTeam(targetPlayer.getUniqueId());
        if (team == null) return;

        if (!team.hasEntry(targetPlayer.getName())) {
            team.addEntry(targetPlayer.getName());
        }

        String prefix = "";
        // 1. Vote Prefix
        if (this.voteManager != null && voteManager.isAnyPollActive()) {
            boolean hasVoted = voteManager.hasPlayerVoted(targetPlayer.getUniqueId());
            prefix = hasVoted ? "§f[§a✓§f] " : "§f[§c✗§f] ";
        }
        // 2. Stats Prefix (overrides vote prefix)
        if (this.globallyViewedStat != null) {
            Number statValue = data.getStatistics().get(globallyViewedStat);
            if (statValue != null) {
                prefix = "§e[" + statValue + "] ";
            }
        }

        ChatColor teamColor = team.getColor();
        if (teamColor == null || teamColor == ChatColor.RESET) {
            teamColor = ChatColor.WHITE;
        }

        String displayName = data.getDisplayName();
        String linkedName = data.getLinkedName();

        String listName;
        if (linkedName != null && !linkedName.isEmpty()) {
            listName = teamColor + displayName;
            team.setPrefix(prefix + teamColor + linkedName + ChatColor.GRAY + "(");
            team.setSuffix(ChatColor.GRAY + ")");
        } else {
            listName = teamColor + displayName;
            team.setPrefix(prefix + teamColor);
            team.setSuffix("");
        }

        targetPlayer.setDisplayName(listName);
        targetPlayer.setPlayerListName(prefix + listName);
    }

    @SuppressWarnings("deprecation")
    public boolean setPlayerColor(String playerName, String colorName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (!player.hasPlayedBefore() && !player.isOnline()) return false;

        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) return false;

        if (colorName.equalsIgnoreCase("reset")) {
            team.setColor(ChatColor.WHITE);
        } else {
            ChatColor chatColor = mapWoolColorToChatColor(colorName);
            team.setColor(chatColor);
        }

        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer != null) {
            onlinePlayer.getScoreboardTags().removeIf(WOOL_COLOR_NAMES::contains);
            if (!colorName.equalsIgnoreCase("reset")) {
                onlinePlayer.addScoreboardTag(colorName.toLowerCase());
            }
            updatePlayerName(onlinePlayer);
        }
        return true;
    }

    private ChatColor mapWoolColorToChatColor(String woolColorName) {
        return switch (woolColorName.toLowerCase()) {
            case "orange" -> ChatColor.GOLD;
            case "magenta" -> ChatColor.LIGHT_PURPLE;
            case "light_blue" -> ChatColor.AQUA;
            case "yellow" -> ChatColor.YELLOW;
            case "lime" -> ChatColor.GREEN;
            case "pink" -> ChatColor.LIGHT_PURPLE;
            case "gray" -> ChatColor.DARK_GRAY;
            case "light_gray" -> ChatColor.GRAY;
            case "cyan" -> ChatColor.DARK_AQUA;
            case "purple" -> ChatColor.DARK_PURPLE;
            case "blue" -> ChatColor.BLUE;
            case "brown" -> ChatColor.GOLD;
            case "green" -> ChatColor.DARK_GREEN;
            case "red" -> ChatColor.RED;
            case "black" -> ChatColor.BLACK;
            default -> ChatColor.WHITE;
        };
    }

    public Team getPlayerTeam(UUID uuid) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return null;
        Scoreboard scoreboard = manager.getMainScoreboard();

        String uuidString = uuid.toString();
        String uniquePart = uuidString.substring(uuidString.length() - 12);
        String teamName = "name_" + uniquePart;

        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        return team;
    }
}