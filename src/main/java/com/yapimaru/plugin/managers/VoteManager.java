package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.VoteData;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class VoteManager {

    private final YAPIMARU_Plugin plugin;
    private final BukkitAudiences adventure;
    private final Map<String, VoteData> activePolls = new ConcurrentHashMap<>();
    private final Map<Integer, String> numericIdToFullIdMap = new ConcurrentHashMap<>();
    private final Map<String, BossBar> pollBossBars = new ConcurrentHashMap<>();
    private final File votingFolder;
    private final File idCounterFile;

    private NameManager nameManager;
    private final AtomicInteger nextPollNumericId;

    public enum ResultDisplayMode { OPEN, ANONYMITY }

    public VoteManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.votingFolder = new File(plugin.getDataFolder(), "voting");
        if (!votingFolder.exists()) {
            votingFolder.mkdirs();
        }
        this.idCounterFile = new File(this.votingFolder, "id_counter.dat");
        this.nextPollNumericId = new AtomicInteger(loadNextPollId());
    }

    private int loadNextPollId() {
        if (idCounterFile.exists()) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(idCounterFile))) {
                return dis.readInt() + 1;
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not read poll ID counter file. Starting from 1.", e);
            }
        }
        return 1;
    }

    private void saveCurrentPollId(int id) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(idCounterFile))) {
            dos.writeInt(id);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save poll ID counter file.", e);
        }
    }


    public void setNameManager(NameManager nameManager) {
        this.nameManager = nameManager;
    }

    public boolean isAnyPollActive() {
        return !activePolls.isEmpty();
    }

    public boolean hasPlayerVoted(UUID playerUuid) {
        return activePolls.values().stream().anyMatch(voteData -> voteData.hasVoted(playerUuid));
    }

    public void updatePlayerVoteStatus(Player player) {
        if (nameManager != null) {
            // ★★★ 修正箇所 ★★★
            // updatePlayerNameの引数を修正
            nameManager.updatePlayerName(player);
        }
    }

    public VoteData createPoll(String directoryName, String question, List<String> options, boolean multiChoice, boolean isEvaluation, long durationMillis) {
        String fullPollId = directoryName + "::" + question;
        if (activePolls.containsKey(fullPollId)) {
            return null;
        }

        int numericId = nextPollNumericId.getAndIncrement();
        saveCurrentPollId(numericId);

        VoteData voteData = new VoteData(numericId, directoryName, question, options, multiChoice, isEvaluation, durationMillis);
        activePolls.put(fullPollId, voteData);
        numericIdToFullIdMap.put(numericId, fullPollId);

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
                    endPoll(fullPollId);
                    this.cancel();
                } else {
                    updateBossBar(fullPollId);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        if (nameManager != null) {
            // ★★★ 修正箇所 ★★★
            Bukkit.getOnlinePlayers().forEach(nameManager::updatePlayerName);
        }

        return voteData;
    }

    public VoteData endPoll(String fullPollId) {
        VoteData voteData = activePolls.remove(fullPollId);
        if (voteData == null) {
            return null;
        }
        numericIdToFullIdMap.remove(voteData.getNumericId());

        BossBar bossBar = pollBossBars.remove(fullPollId);
        if (bossBar != null) {
            adventure.players().hideBossBar(bossBar);
        }

        saveResultFile(voteData);

        if (nameManager != null && activePolls.isEmpty()) {
            // ★★★ 修正箇所 ★★★
            Bukkit.getOnlinePlayers().forEach(nameManager::updatePlayerName);
        }
        return voteData;
    }

    public Map<String, VoteData> getActivePolls() {
        return activePolls;
    }

    public VoteData getPollByNumericId(int numericId) {
        String fullPollId = numericIdToFullIdMap.get(numericId);
        return (fullPollId != null) ? activePolls.get(fullPollId) : null;
    }

    public void showPollToPlayer(Player player, VoteData voteData) {
        adventure.player(player).sendMessage(Component.text("------ 投票開始 (ID: " + voteData.getNumericId() + ") ------", NamedTextColor.GOLD));
        adventure.player(player).sendMessage(Component.text("Q. " + voteData.getQuestion(), NamedTextColor.AQUA));
        for (int i = 0; i < voteData.getOptions().size(); i++) {
            adventure.player(player).sendMessage(Component.text((i + 1) + ". " + voteData.getOptions().get(i), NamedTextColor.YELLOW));
        }
        adventure.player(player).sendMessage(Component.text("チャットで /ans " + voteData.getNumericId() + " <番号> で投票", NamedTextColor.GRAY));
        if (voteData.isMultiChoice()) {
            adventure.player(player).sendMessage(Component.text("(複数選択可)", NamedTextColor.GRAY));
        }
    }

    private void saveResultFile(VoteData voteData) {
        File dir = new File(this.votingFolder, voteData.getDirectoryName());
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String fileName = voteData.getQuestion().replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + voteData.getNumericId() + "_" + date + ".yml";
        File resultFile = new File(dir, fileName);

        YamlConfiguration config = new YamlConfiguration();

        config.set("numeric-id", voteData.getNumericId());
        config.set("directory-name", voteData.getDirectoryName());
        config.set("question", voteData.getQuestion());
        config.set("options", voteData.getOptions());
        config.set("is-evaluation", voteData.isEvaluation());
        config.set("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        double totalScore = 0;
        int totalVotes = 0;
        Map<String, List<String>> detailedResults = new LinkedHashMap<>();
        for (int i = 0; i < voteData.getOptions().size(); i++) {
            int choiceNumber = i + 1;
            String optionText = voteData.getOptions().get(i);
            Set<UUID> voterUuids = voteData.getVotes().get(choiceNumber);
            if (voterUuids == null) continue;

            if (voteData.isEvaluation()) {
                totalScore += (double) choiceNumber * voterUuids.size();
                totalVotes += voterUuids.size();
            }

            List<String> voterInfo = new ArrayList<>();
            for (UUID uuid : voterUuids) {
                String displayName = (nameManager != null) ? nameManager.getDisplayName(uuid) : Bukkit.getOfflinePlayer(uuid).getName();
                voterInfo.add(displayName + " (" + uuid + ")");
            }
            detailedResults.put(choiceNumber + ". " + optionText, voterInfo);
        }
        config.set("results", detailedResults);

        if (voteData.isEvaluation() && totalVotes > 0) {
            config.set("average-rating", totalScore / totalVotes);
        }

        try {
            config.save(resultFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save poll result file: " + resultFile.getName(), e);
        }
    }

    public void displayResults(YamlConfiguration resultConfig, ResultDisplayMode displayMode) {
        adventure.all().sendMessage(Component.text("------ 投票結果 (ID: " + resultConfig.getInt("numeric-id") + ") ------", NamedTextColor.GOLD));
        adventure.all().sendMessage(Component.text("Q. " + resultConfig.getString("question"), NamedTextColor.AQUA));

        ConfigurationSection resultsSection = resultConfig.getConfigurationSection("results");
        if (resultsSection != null) {
            List<String> options = resultConfig.getStringList("options");
            if (options.isEmpty()) return;

            for (int i = 0; i < options.size(); i++) {
                String optionText = options.get(i);
                String key = (i + 1) + ". " + optionText;

                List<String> voters = resultsSection.getStringList(key);

                adventure.all().sendMessage(Component.text(key + " (" + voters.size() + "票)", NamedTextColor.YELLOW));

                if (displayMode == ResultDisplayMode.OPEN && !voters.isEmpty()) {
                    for (String voterInfo : voters) {
                        try {
                            String uuidString = voterInfo.substring(voterInfo.lastIndexOf('(') + 1, voterInfo.lastIndexOf(')'));
                            UUID uuid = UUID.fromString(uuidString);
                            String displayName = (nameManager != null) ? nameManager.getDisplayName(uuid) : Bukkit.getOfflinePlayer(uuid).getName();
                            adventure.all().sendMessage(Component.text("- " + displayName, NamedTextColor.GRAY));
                        } catch (Exception e) {
                            String fallbackName = voterInfo.split(" \\(")[0];
                            adventure.all().sendMessage(Component.text("- " + fallbackName, NamedTextColor.GRAY));
                        }
                    }
                }
            }
        }

        if (resultConfig.getBoolean("is-evaluation")) {
            adventure.all().sendMessage(Component.text("--------------------", NamedTextColor.GOLD));
            adventure.all().sendMessage(Component.text("平均評価: " + String.format("%.2f", resultConfig.getDouble("average-rating")), NamedTextColor.AQUA));
        }
        adventure.all().sendMessage(Component.text("--------------------", NamedTextColor.GOLD));
    }


    public Integer getMaxStarsForProject(String projectName) {
        return plugin.getConfig().getInt("voting-settings.project-max-stars." + projectName, 0);
    }

    public void setMaxStarsForProject(String projectName, int stars) {
        plugin.getConfig().set("voting-settings.project-max-stars." + projectName, stars);
        plugin.saveConfig();
    }

    public File getVotingFolder() {
        return votingFolder;
    }

    private void updateBossBar(String fullPollId) {
        VoteData voteData = activePolls.get(fullPollId);
        BossBar bossBar = pollBossBars.get(fullPollId);
        if (voteData == null || bossBar == null) return;

        int totalVotes = (int) voteData.getPlayerVotes().keySet().stream().distinct().count();
        Component title = Component.text("Q. " + voteData.getQuestion() + " (" + totalVotes + "票)", NamedTextColor.WHITE);

        if (voteData.getOptions().size() == 2 && !voteData.isEvaluation()) {
            int votes1 = voteData.getVotes().get(1).size();
            int votes2 = voteData.getVotes().get(2).size();
            int total = votes1 + votes2;
            float progress = (total == 0) ? 0.5f : (float) votes1 / total;

            title = Component.text(voteData.getOptions().get(0) + " " + votes1 + " - " + votes2 + " " + voteData.getOptions().get(1));
            bossBar.progress(progress);
        }

        bossBar.name(title);
    }
}