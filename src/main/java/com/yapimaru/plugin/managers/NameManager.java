package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NameManager {

    private final YAPIMARU_Plugin plugin;
    private FileConfiguration config;
    private final Map<UUID, String> baseNames = new HashMap<>();
    private final Map<UUID, String> linkedNames = new HashMap<>();
    private final Map<UUID, String> frozenDisplayNames = new HashMap<>();

    private VoteManager voteManager;

    public static final List<String> WOOL_COLOR_NAMES = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    public NameManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        reloadData();
    }

    public void setVoteManager(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    public void loadNames() {
        baseNames.clear();
        linkedNames.clear();
        frozenDisplayNames.clear();

        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection == null) return;

        for (String uuidString : playersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                String path = "players." + uuidString;
                baseNames.put(uuid, config.getString(path + ".base_name"));
                linkedNames.put(uuid, config.getString(path + ".linked_name"));
                frozenDisplayNames.put(uuid, config.getString(path + ".frozen_display_name"));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in config.yml: " + uuidString);
            }
        }
    }

    public void cacheFrozenData(OfflinePlayer player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        String currentDisplayName = getDisplayName(uuid);
        frozenDisplayNames.put(uuid, currentDisplayName);
        config.set("players." + uuid + ".frozen_display_name", currentDisplayName);
        plugin.saveConfig();
    }

    public void removeFrozenData(UUID uuid) {
        config.set("players." + uuid.toString() + ".frozen_display_name", null);
        frozenDisplayNames.remove(uuid);
        plugin.saveConfig();
    }


    public String getFrozenDisplayName(UUID uuid) {
        return frozenDisplayNames.get(uuid);
    }

    public void setBaseName(UUID uuid, String name) {
        baseNames.put(uuid, name);
        config.set("players." + uuid.toString() + ".base_name", name);
        plugin.saveConfig();
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            updatePlayerName(onlinePlayer, false);
        }
    }

    public void setLinkedName(UUID uuid, String name) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase("remove")) {
            linkedNames.remove(uuid);
            config.set("players." + uuid.toString() + ".linked_name", null);
        } else {
            linkedNames.put(uuid, name);
            config.set("players." + uuid.toString() + ".linked_name", name);
        }
        plugin.saveConfig();
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null) {
            updatePlayerName(onlinePlayer, false);
        }
    }

    public String getBaseName(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return baseNames.getOrDefault(uuid, player.getName());
    }

    public String getLinkedName(UUID uuid) {
        return linkedNames.get(uuid);
    }

    public String getDisplayName(UUID uuid) {
        String linked = getLinkedName(uuid);
        if (linked != null && !linked.isEmpty()) {
            return linked;
        }
        return getBaseName(uuid);
    }

    @SuppressWarnings("deprecation")
    public void updatePlayerName(Player targetPlayer, boolean isReset) {
        if (targetPlayer == null) return;

        Team team = getPlayerTeam(targetPlayer.getUniqueId());
        if (!team.hasEntry(targetPlayer.getName())) {
            team.addEntry(targetPlayer.getName());
        }

        if (isReset) {
            targetPlayer.setDisplayName(targetPlayer.getName());
            targetPlayer.setPlayerListName(targetPlayer.getName());
            team.setPrefix("");
            team.setSuffix("");
            team.setColor(ChatColor.WHITE);
            return;
        }

        String votePrefix = "";
        if (this.voteManager != null && voteManager.isAnyPollActive()) {
            boolean hasVoted = voteManager.hasPlayerVoted(targetPlayer.getUniqueId());
            votePrefix = hasVoted ? "§f[§a✓§f] " : "§f[§c✗§f] ";
        }

        ChatColor teamColor = team.getColor();
        if (teamColor == null || teamColor == ChatColor.RESET) {
            teamColor = ChatColor.WHITE;
        }

        String linkedName = getLinkedName(targetPlayer.getUniqueId());
        String baseName = getBaseName(targetPlayer.getUniqueId());

        String listName;
        if (linkedName != null && !linkedName.isEmpty()) {
            listName = teamColor + linkedName + ChatColor.GRAY + "(" + baseName + ")" + ChatColor.RESET;
            team.setPrefix(votePrefix + teamColor + linkedName + ChatColor.GRAY + "(");
            team.setSuffix(ChatColor.GRAY + ")");
        } else {
            listName = teamColor + baseName + ChatColor.RESET;
            team.setPrefix(votePrefix + teamColor.toString());
            team.setSuffix("");
        }

        targetPlayer.setDisplayName(targetPlayer.getName());
        targetPlayer.setPlayerListName(votePrefix + listName);
    }


    public void reloadData() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadNames();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerName(player, false);
        }
    }

    // ★★★ 修正点: 警告を抑制する @SuppressWarnings を追加 ★★★
    @SuppressWarnings("deprecation")
    public boolean setPlayerColor(String playerName, String colorName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (!player.hasPlayedBefore() && !player.isOnline()) return false;

        Team team = getPlayerTeam(player.getUniqueId());

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
            updatePlayerName(onlinePlayer, false);
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
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
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