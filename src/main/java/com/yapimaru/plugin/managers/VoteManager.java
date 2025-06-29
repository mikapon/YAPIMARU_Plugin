package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.VoteData;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VoteManager {

    private final YAPIMARU_Plugin plugin;
    private final BukkitAudiences adventure;
    private final Map<String, VoteData> activePolls = new ConcurrentHashMap<>();
    private final Map<String, BossBar> pollBossBars = new ConcurrentHashMap<>();
    private final File votingFolder;

    private NameManager nameManager;

    public enum ResultDisplayMode { OPEN, ANONYMITY }

    public VoteManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.votingFolder = new File(plugin.getDataFolder(), "voting");
        if (!votingFolder.exists()) {
            votingFolder.mkdirs();
        }
    }

    public void setNameManager(NameManager nameManager) {
        this.nameManager = nameManager;
    }

    public boolean isAnyPollActive() {
        return !activePolls.isEmpty();
    }

    public boolean hasPlayerVoted(UUID playerUuid) {
        for (VoteData voteData : activePolls.values()) {
            if (voteData.hasVoted(playerUuid)) {
                return true;
            }
        }
        return false;
    }

    public void updatePlayerVoteStatus(Player player) {
        if (nameManager != null) {
            nameManager.updatePlayerName(player, false);
        }
    }

    public boolean createPoll(String directoryName, String question, List<String> options, boolean multiChoice, long durationMillis, CommandSender creator) {
        String fullPollId = directoryName + "::" + question;
        if (activePolls.containsKey(fullPollId)) {
            return false;
        }

        VoteData voteData = new VoteData(directoryName, question, options, multiChoice, durationMillis);
        activePolls.put(fullPollId, voteData);

        BossBar bossBar = BossBar.bossBar(Component.text("Q. " + question), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        pollBossBars.put(fullPollId, bossBar);

        Bukkit.getOnlinePlayers().forEach(player -> {
            adventure.player(player).showBossBar(bossBar);
            showPollToPlayer(player, voteData);
        });

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!activePolls.containsKey(fullPollId)) {
                    this.cancel();
                    return;
                }
                if (voteData.isExpired()) {
                    endPoll(fullPollId, ResultDisplayMode.ANONYMITY);
                    this.cancel();
                } else {
                    updateBossBar(fullPollId);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        if (nameManager != null) {
            Bukkit.getOnlinePlayers().forEach(p -> nameManager.updatePlayerName(p, false));
        }

        return true;
    }

    public void endPoll(String fullPollId, ResultDisplayMode displayMode) {
        VoteData voteData = activePolls.remove(fullPollId);
        if (voteData == null) {
            return;
        }

        BossBar bossBar = pollBossBars.remove(fullPollId);
        if (bossBar != null) {
            adventure.players().hideBossBar(bossBar);
        }

        saveResultFile(voteData);
        displayResults(voteData, displayMode);

        if (nameManager != null && activePolls.isEmpty()) {
            Bukkit.getOnlinePlayers().forEach(p -> nameManager.updatePlayerName(p, false));
        }
    }

    public Map<String, VoteData> getActivePolls() {
        return activePolls;
    }

    public VoteData getPoll(String fullPollId) {
        return activePolls.get(fullPollId);
    }

    public void showPollToPlayer(Player player, VoteData voteData) {
        adventure.player(player).sendMessage(Component.text("------ 投票開始 ------", NamedTextColor.GOLD));
        adventure.player(player).sendMessage(Component.text("Q. " + voteData.getQuestion(), NamedTextColor.AQUA));
        for (int i = 0; i < voteData.getOptions().size(); i++) {
            adventure.player(player).sendMessage(Component.text((i + 1) + ". " + voteData.getOptions().get(i), NamedTextColor.YELLOW));
        }
        adventure.player(player).sendMessage(Component.text("チャットで /voting answer \"" + voteData.getDirectoryName() + "\" \"" + voteData.getQuestion() + "\" <番号> で投票", NamedTextColor.GRAY));
        if (voteData.isMultiChoice()) {
            adventure.player(player).sendMessage(Component.text("(複数選択可)", NamedTextColor.GRAY));
        }
    }

    private void updateBossBar(String fullPollId) {
        VoteData voteData = activePolls.get(fullPollId);
        BossBar bossBar = pollBossBars.get(fullPollId);
        if (voteData == null || bossBar == null) return;

        int totalVotes = (int) voteData.getPlayerVotes().keySet().stream().distinct().count();
        Component title = Component.text("Q. " + voteData.getQuestion() + " (" + totalVotes + "票)", NamedTextColor.WHITE);

        if (voteData.getOptions().size() == 2) {
            int votes1 = voteData.getVotes().get(1).size();
            int votes2 = voteData.getVotes().get(2).size();
            int total = votes1 + votes2;
            float progress = (total == 0) ? 0.5f : (float) votes1 / total;

            title = Component.text(voteData.getOptions().get(0) + " " + votes1 + " - " + votes2 + " " + voteData.getOptions().get(1));
            bossBar.progress(progress);
        }

        bossBar.name(title);
    }

    private void saveResultFile(VoteData voteData) {
        File dir = new File(this.votingFolder, voteData.getDirectoryName());
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String fileName = voteData.getQuestion().replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + date + ".yml";
        File resultFile = new File(dir, fileName);

        YamlConfiguration config = new YamlConfiguration();

        config.set("directory-name", voteData.getDirectoryName());
        config.set("question", voteData.getQuestion());
        config.set("options", voteData.getOptions());
        config.set("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        Map<String, List<String>> detailedResults = new LinkedHashMap<>();
        for (int i = 0; i < voteData.getOptions().size(); i++) {
            int choiceNumber = i + 1;
            String optionText = voteData.getOptions().get(i);
            Set<UUID> voterUuids = voteData.getVotes().get(choiceNumber);
            if (voterUuids == null) continue;

            List<String> voterInfo = new ArrayList<>();
            for (UUID uuid : voterUuids) {
                String displayName = (nameManager != null) ? nameManager.getDisplayName(uuid) : Bukkit.getOfflinePlayer(uuid).getName();
                if (displayName == null) displayName = uuid.toString();
                voterInfo.add(displayName + " (" + uuid + ")");
            }
            detailedResults.put(choiceNumber + ". " + optionText, voterInfo);
        }
        config.set("results", detailedResults);

        try {
            config.save(resultFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save poll result file: " + resultFile.getName());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
    private void displayResults(VoteData voteData, ResultDisplayMode displayMode) {
        Bukkit.getOnlinePlayers().forEach(p -> {
            adventure.player(p).sendMessage(Component.text("------ 投票結果 ------", NamedTextColor.GOLD));
            adventure.player(p).sendMessage(Component.text("Q. " + voteData.getQuestion(), NamedTextColor.AQUA));

            for (int i = 0; i < voteData.getOptions().size(); i++) {
                int choiceNumber = i + 1;
                String optionText = voteData.getOptions().get(i);
                Set<UUID> voters = voteData.getVotes().get(choiceNumber);
                int voteCount = (voters != null) ? voters.size() : 0;

                adventure.player(p).sendMessage(Component.text((i + 1) + ". " + optionText + " (" + voteCount + "票)", NamedTextColor.YELLOW));

                if (displayMode == ResultDisplayMode.OPEN && voters != null && !voters.isEmpty()) {
                    List<String> voterNames = new ArrayList<>();
                    for(UUID uuid : voters) {
                        String name = (nameManager != null) ? nameManager.getDisplayName(uuid) : Bukkit.getOfflinePlayer(uuid).getName();
                        if (name == null) name = "Unknown";
                        voterNames.add(name);
                    }
                    adventure.player(p).sendMessage(Component.text("    " + String.join(", ", voterNames), NamedTextColor.GRAY));
                }
            }
            adventure.player(p).sendMessage(Component.text("--------------------", NamedTextColor.GOLD));
        });
    }
}