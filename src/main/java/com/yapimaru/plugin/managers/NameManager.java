package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.data.ParticipantData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.Set;
import java.util.UUID;

public class NameManager {
    private final ParticipantManager participantManager;
    private VoteManager voteManager;

    public static final Set<String> WOOL_COLOR_NAMES = Set.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    // ★★★ 修正箇所 ★★★
    public NameManager(ParticipantManager participantManager) {
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

    public String getDisplayName(UUID uuid) {
        ParticipantData data = participantManager.getParticipant(uuid);
        if (data != null) {
            return data.getDisplayName();
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString();
    }

    // ★★★ 修正箇所 ★★★
    // 他のクラスから呼び出されるため、新しい仕様で再実装
    public String getLinkedName(UUID uuid) {
        ParticipantData data = participantManager.getParticipant(uuid);
        return (data != null) ? data.getLinkedName() : "";
    }

    public void setBaseName(UUID uuid, String name) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        ParticipantData data = participantManager.findOrCreateParticipant(player);
        participantManager.changePlayerName(uuid, name, data.getLinkedName());
        updatePlayerName(player);
    }

    public void setLinkedName(UUID uuid, String name) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        ParticipantData data = participantManager.findOrCreateParticipant(player);
        participantManager.changePlayerName(uuid, data.getBaseName(), name == null || name.equalsIgnoreCase("remove") ? "" : name);
        updatePlayerName(player);
    }


    public void updatePlayerName(Player targetPlayer) {
        if (targetPlayer == null) return;

        Team team = getPlayerTeam(targetPlayer.getUniqueId());
        if (team == null) return;

        if (!team.hasEntry(targetPlayer.getName())) {
            team.addEntry(targetPlayer.getName());
        }

        String votePrefix = "";
        if (this.voteManager != null && voteManager.isAnyPollActive()) {
            boolean hasVoted = voteManager.hasPlayerVoted(targetPlayer.getUniqueId());
            votePrefix = hasVoted ? "§f[§a✓§f] " : "§f[§c✗§f] ";
        }

        ChatColor teamColor = team.getColor();
        if (teamColor == ChatColor.RESET) {
            teamColor = ChatColor.WHITE;
        }

        ParticipantData data = participantManager.findOrCreateParticipant(targetPlayer);

        String linkedName = data.getLinkedName();
        String baseName = data.getBaseName();
        String displayName = data.getDisplayName();

        String listName;
        if (linkedName != null && !linkedName.isEmpty()) {
            listName = teamColor + linkedName + ChatColor.GRAY + "(" + baseName + ")" + ChatColor.RESET;
            team.setPrefix(votePrefix + teamColor + linkedName + ChatColor.GRAY + "(");
            team.setSuffix(ChatColor.GRAY + ")");
        } else {
            listName = teamColor + baseName + ChatColor.RESET;
            team.setPrefix(votePrefix + teamColor);
            team.setSuffix("");
        }

        targetPlayer.setDisplayName(displayName);
        targetPlayer.setPlayerListName(votePrefix + listName);
    }

    public void resetPlayerName(Player player) {
        if (player == null) return;
        Team team = getPlayerTeam(player.getUniqueId());
        if (team != null) {
            team.removeEntry(player.getName());
        }
        player.setDisplayName(player.getName());
        player.setPlayerListName(player.getName());
    }

    public boolean setPlayerColor(String playerName, String colorName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (!player.hasPlayedBefore() && !player.isOnline()) return false;

        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) return false;

        if (colorName.equalsIgnoreCase("reset")) {
            team.setColor(ChatColor.WHITE);
        } else {
            ChatColor chatColor = mapWoolColorToChatColor(colorName);
            if(chatColor == null) return false;
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