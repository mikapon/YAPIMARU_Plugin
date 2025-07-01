package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NameManager {
    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private VoteManager voteManager;

    // ★★★ 新規追加 ★★★
    // どのプレイヤーがどの統計情報をTABに表示中か管理する
    private final Map<UUID, String> playerViewingStat = new HashMap<>();
    private final Map<UUID, BukkitTask> statViewTimeoutTasks = new HashMap<>();


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

    public String getDisplayName(UUID uuid) {
        ParticipantData data = participantManager.getParticipant(uuid);
        if (data != null) {
            return data.getDisplayName();
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString();
    }

    public void setBaseName(UUID uuid, String name) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        ParticipantData data = participantManager.findOrCreateParticipant(player);
        participantManager.changePlayerName(uuid, name, data.getLinkedName());
        setPlayerViewingStat(player, null); // TABの統計表示をリセット
        updatePlayerName(player);
    }

    public void setLinkedName(UUID uuid, String name) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        ParticipantData data = participantManager.findOrCreateParticipant(player);
        participantManager.changePlayerName(uuid, data.getBaseName(), name == null || name.equalsIgnoreCase("remove") ? "" : name);
        setPlayerViewingStat(player, null); // TABの統計表示をリセット
        updatePlayerName(player);
    }

    // ★★★ 新規追加メソッド ★★★
    public void setPlayerViewingStat(Player player, String statName) {
        // 既存のタイムアウトタスクがあればキャンセル
        if (statViewTimeoutTasks.containsKey(player.getUniqueId())) {
            statViewTimeoutTasks.remove(player.getUniqueId()).cancel();
        }

        if (statName == null) {
            // 表示をクリアする場合
            playerViewingStat.remove(player.getUniqueId());
        } else {
            // 新しい表示を設定する場合
            playerViewingStat.put(player.getUniqueId(), statName);
            // 60秒後に自動で表示をクリアするタスクを設定
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                setPlayerViewingStat(player, null); // 自分自身を呼び出してクリア
            }, 20L * 60); // 60秒
            statViewTimeoutTasks.put(player.getUniqueId(), task);
        }
        // 即座に名前表示を更新
        updatePlayerName(player);
    }

    public void updatePlayerName(Player targetPlayer) {
        if (targetPlayer == null) return;

        Team team = getPlayerTeam(targetPlayer.getUniqueId());
        if (team == null) return;

        if (!team.hasEntry(targetPlayer.getName())) {
            team.addEntry(targetPlayer.getName());
        }

        // --- プレフィックス(名前の前につく文字)の組み立て ---

        // 1. 投票ステータス
        String votePrefix = "";
        if (this.voteManager != null && voteManager.isAnyPollActive()) {
            boolean hasVoted = voteManager.hasPlayerVoted(targetPlayer.getUniqueId());
            votePrefix = hasVoted ? "§f[§a✓§f]" : "§f[§c✗§f]";
        }

        // 2. 統計情報表示 (★新規追加★)
        String statPrefix = "";
        String viewingStatName = playerViewingStat.get(targetPlayer.getUniqueId());
        ParticipantData data = participantManager.findOrCreateParticipant(targetPlayer);

        if (viewingStatName != null && data != null) {
            Number statValue = data.getStatistics().get(viewingStatName);
            if (statValue != null) {
                statPrefix = "§e[" + statValue + "§e] ";
            }
        }

        // 3. 名前の色
        ChatColor teamColor = team.getColor();
        if (teamColor == ChatColor.RESET) {
            teamColor = ChatColor.WHITE;
        }

        // 4. 名前本体
        String linkedName = data.getLinkedName();
        String baseName = data.getBaseName();
        String displayName = data.getDisplayName();

        // --- 組み立てたプレフィックスを適用 ---
        String finalPrefix = (votePrefix.isEmpty() ? "" : votePrefix + " ") + statPrefix;

        String listName;
        if (linkedName != null && !linkedName.isEmpty()) {
            listName = finalPrefix + teamColor + linkedName + ChatColor.GRAY + "(" + baseName + ")" + ChatColor.RESET;
            team.setPrefix(finalPrefix + teamColor + linkedName + ChatColor.GRAY + "(");
            team.setSuffix(ChatColor.GRAY + ")");
        } else {
            listName = finalPrefix + teamColor + baseName + ChatColor.RESET;
            team.setPrefix(finalPrefix + teamColor);
            team.setSuffix("");
        }

        targetPlayer.setDisplayName(displayName); // 頭上表示名は変えない
        targetPlayer.setPlayerListName(listName); // TABリストの表示を更新
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
            if (chatColor == null) return false;
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