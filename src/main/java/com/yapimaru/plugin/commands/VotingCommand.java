package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.VoteData;
import com.yapimaru.plugin.managers.VoteManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class VotingCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final VoteManager voteManager;
    private final BukkitAudiences adventure;

    public VotingCommand(YAPIMARU_Plugin plugin, VoteManager voteManager) {
        this.plugin = plugin;
        this.voteManager = voteManager;
        this.adventure = plugin.getAdventure();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "question" -> handleQuestion(sender, subArgs);
            case "evaluation" -> handleEvaluation(sender, subArgs);
            case "end" -> handleEnd(sender, subArgs);
            case "result" -> handleResult(sender, subArgs);
            case "average" -> handleAverage(sender, subArgs);
            case "list" -> handleList(sender, subArgs);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleQuestion(CommandSender sender, String[] args) {
        if (args.length < 3) {
            adventure.sender(sender).sendMessage(Component.text("使い方: /voting question <企画名> <\"質問文\"> <選択肢1> <選択肢2> ...", NamedTextColor.RED));
            return;
        }

        String directoryName = args[0];
        String question = args[1];
        List<String> options = new ArrayList<>();
        long durationMillis = 0;
        boolean multiChoice = false;

        int currentArgIndex = 2;
        while (currentArgIndex < args.length) {
            String arg = args[currentArgIndex];
            if (arg.equalsIgnoreCase("-duration")) {
                if (currentArgIndex + 1 < args.length) {
                    durationMillis = parseDuration(args[currentArgIndex + 1]);
                    if (durationMillis == -1) {
                        adventure.sender(sender).sendMessage(Component.text("無効な時間形式です。例: 1h, 30m, 2d", NamedTextColor.RED));
                        return;
                    }
                    currentArgIndex += 2;
                } else {
                    adventure.sender(sender).sendMessage(Component.text("-duration の後には時間を指定してください。", NamedTextColor.RED));
                    return;
                }
            } else if (arg.equalsIgnoreCase("-multi")) {
                multiChoice = true;
                currentArgIndex++;
            } else {
                options.add(arg);
                currentArgIndex++;
            }
        }

        if (options.isEmpty() || options.size() < 2) {
            adventure.sender(sender).sendMessage(Component.text("選択肢は少なくとも2つ必要です。", NamedTextColor.RED));
            return;
        }

        VoteData newVote = voteManager.createPoll(directoryName, question, options, multiChoice, false, durationMillis);
        if (newVote != null) {
            adventure.sender(sender).sendMessage(Component.text("投票「" + question + "」(ID: " + newVote.getNumericId() + ") を開始しました。", NamedTextColor.GREEN));
        } else {
            adventure.sender(sender).sendMessage(Component.text("同じ企画・質問の投票「" + question + "」は既に存在します。", NamedTextColor.RED));
        }
    }

    private void handleEvaluation(CommandSender sender, String[] args) {
        if (args.length < 2) {
            adventure.sender(sender).sendMessage(Component.text("使い方: /voting evaluation <企画名> <\"質問文\"> [最大評価]", NamedTextColor.RED));
            return;
        }

        String projectName = args[0];
        String question = args[1];

        Integer maxStars = voteManager.getMaxStarsForProject(projectName);
        if (maxStars == 0) {
            if (args.length < 3) {
                adventure.sender(sender).sendMessage(Component.text("エラー: この企画で最初の採点投票です。最大評価（星の数）を指定してください。", NamedTextColor.RED));
                adventure.sender(sender).sendMessage(Component.text("例: /voting evaluation " + projectName + " \"" + question + "\" 5", NamedTextColor.GRAY));
                return;
            }
            try {
                maxStars = Integer.parseInt(args[2]);
                if (maxStars <= 0 || maxStars > 10) {
                    adventure.sender(sender).sendMessage(Component.text("最大評価は1から10の間で指定してください。", NamedTextColor.RED));
                    return;
                }
                voteManager.setMaxStarsForProject(projectName, maxStars);
                adventure.sender(sender).sendMessage(Component.text("企画「" + projectName + "」の最大評価を星" + maxStars + "に設定しました。", NamedTextColor.GREEN));
            } catch (NumberFormatException e) {
                adventure.sender(sender).sendMessage(Component.text("最大評価には数字を指定してください。", NamedTextColor.RED));
                return;
            }
        }

        List<String> options = IntStream.rangeClosed(1, maxStars)
                .mapToObj(i -> "☆".repeat(i))
                .collect(Collectors.toList());

        VoteData newVote = voteManager.createPoll(projectName, question, options, false, true, 0);
        if (newVote != null) {
            adventure.sender(sender).sendMessage(Component.text("採点投票「" + question + "」(ID: " + newVote.getNumericId() + ") を開始しました。", NamedTextColor.GREEN));
        } else {
            adventure.sender(sender).sendMessage(Component.text("同じ企画・質問の投票「" + question + "」は既に存在します。", NamedTextColor.RED));
        }
    }

    private void handleEnd(CommandSender sender, String[] args) {
        if (args.length < 1) {
            adventure.sender(sender).sendMessage(Component.text("使い方: /voting end <投票ID>", NamedTextColor.RED));
            return;
        }

        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            adventure.sender(sender).sendMessage(Component.text("投票IDは数字で指定してください。", NamedTextColor.RED));
            return;
        }

        VoteData voteData = voteManager.getPollByNumericId(numericId);
        if (voteData == null) {
            adventure.sender(sender).sendMessage(Component.text("アクティブな投票ID「" + numericId + "」は見つかりません。", NamedTextColor.RED));
            return;
        }

        String fullPollId = voteData.getDirectoryName() + "::" + voteData.getQuestion();
        VoteData endedVote = voteManager.endPoll(fullPollId);

        if (endedVote != null) {
            adventure.sender(sender).sendMessage(Component.text("投票「" + endedVote.getQuestion() + "」(ID: " + endedVote.getNumericId() + ") を終了しました。", NamedTextColor.GREEN));
        }
    }

    private void handleResult(CommandSender sender, String[] args) {
        if (args.length < 1) {
            adventure.sender(sender).sendMessage(Component.text("使い方: /voting result <投票ID> [open|anonymity]", NamedTextColor.RED));
            return;
        }
        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            adventure.sender(sender).sendMessage(Component.text("投票IDは数字で指定してください。", NamedTextColor.RED));
            return;
        }

        File resultFile = findResultFile(numericId);
        if (resultFile == null) {
            adventure.sender(sender).sendMessage(Component.text("終了した投票ID「" + numericId + "」の結果ファイルが見つかりません。", NamedTextColor.RED));
            return;
        }

        YamlConfiguration resultConfig = YamlConfiguration.loadConfiguration(resultFile);
        VoteManager.ResultDisplayMode displayMode = (args.length > 1 && args[1].equalsIgnoreCase("open")) ? VoteManager.ResultDisplayMode.OPEN : VoteManager.ResultDisplayMode.ANONYMITY;

        voteManager.displayResults(resultConfig, displayMode);
    }

    private void handleAverage(CommandSender sender, String[] args) {
        if (args.length < 1) {
            adventure.sender(sender).sendMessage(Component.text("使い方: /voting average <投票ID | 企画名>", NamedTextColor.RED));
            return;
        }

        String input = String.join(" ", args);
        try {
            int numericId = Integer.parseInt(input);
            handleSingleAverage(sender, numericId);
        } catch (NumberFormatException e) {
            handleProjectAverage(sender, input);
        }
    }

    private void handleSingleAverage(CommandSender sender, int numericId) {
        File resultFile = findResultFile(numericId);
        if (resultFile == null) {
            adventure.sender(sender).sendMessage(Component.text("終了した投票ID「" + numericId + "」の結果ファイルが見つかりません。", NamedTextColor.RED));
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(resultFile);
        if (!config.getBoolean("is-evaluation")) {
            adventure.sender(sender).sendMessage(Component.text("投票ID「" + numericId + "」は採点投票ではありません。", NamedTextColor.RED));
            return;
        }
        adventure.all().sendMessage(Component.text("§6--- 平均評価 (ID: " + numericId + ") ---"));
        adventure.all().sendMessage(Component.text("§b" + config.getString("question") + ": §e" + String.format("%.2f", config.getDouble("average-rating"))));
    }

    private void handleProjectAverage(CommandSender sender, String projectName) {
        File projectDir = new File(voteManager.getVotingFolder(), projectName);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            adventure.sender(sender).sendMessage(Component.text("企画名「" + projectName + "」の投票結果が見つかりません。", NamedTextColor.RED));
            return;
        }
        File[] resultFiles = projectDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (resultFiles == null || resultFiles.length == 0) {
            adventure.sender(sender).sendMessage(Component.text("企画名「" + projectName + "」の投票結果が見つかりません。", NamedTextColor.RED));
            return;
        }

        List<Map.Entry<String, Double>> averages = new ArrayList<>();
        for (File file : resultFiles) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (config.getBoolean("is-evaluation")) {
                averages.add(new java.util.AbstractMap.SimpleEntry<>(config.getString("question"), config.getDouble("average-rating")));
            }
        }

        if (averages.isEmpty()) {
            adventure.sender(sender).sendMessage(Component.text("企画名「" + projectName + "」には採点投票の結果がありません。", NamedTextColor.YELLOW));
            return;
        }

        averages.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        adventure.all().sendMessage(Component.text("§6--- 平均評価ランキング: " + projectName + " ---"));
        for (int i = 0; i < averages.size(); i++) {
            Map.Entry<String, Double> entry = averages.get(i);
            adventure.all().sendMessage(Component.text("§e" + (i+1) + ". " + String.format("%.2f", entry.getValue()) + " - §b" + entry.getKey()));
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        String projectName = (args.length > 0) ? String.join(" ", args) : null;

        adventure.sender(sender).sendMessage(Component.text("§6--- 投票一覧" + (projectName != null ? ": " + projectName : "") + " ---"));

        adventure.sender(sender).sendMessage(Component.text("§a● 進行中の投票"));
        boolean hasActive = false;
        for (VoteData vote : voteManager.getActivePolls().values()) {
            if (projectName == null || vote.getDirectoryName().equalsIgnoreCase(projectName)) {
                adventure.sender(sender).sendMessage(Component.text("  ID: " + vote.getNumericId() + " - " + vote.getQuestion(), NamedTextColor.WHITE));
                hasActive = true;
            }
        }
        if (!hasActive) {
            adventure.sender(sender).sendMessage(Component.text("  (なし)", NamedTextColor.GRAY));
        }

        adventure.sender(sender).sendMessage(Component.text("§c● 終了した投票"));
        File searchDir = (projectName != null) ? new File(voteManager.getVotingFolder(), projectName) : voteManager.getVotingFolder();
        if (!searchDir.exists()) {
            adventure.sender(sender).sendMessage(Component.text("  (なし)", NamedTextColor.GRAY));
            return;
        }

        List<File> allFiles = new ArrayList<>();
        if (searchDir.isDirectory()) {
            if (projectName != null) {
                File[] files = searchDir.listFiles((d, n) -> n.endsWith(".yml"));
                if (files != null) allFiles.addAll(Arrays.asList(files));
            } else {
                File[] projectDirs = searchDir.listFiles(File::isDirectory);
                if (projectDirs != null) {
                    for (File pDir : projectDirs) {
                        File[] files = pDir.listFiles((d, n) -> n.endsWith(".yml"));
                        if (files != null) allFiles.addAll(Arrays.asList(files));
                    }
                }
            }
        }

        if (allFiles.isEmpty()) {
            adventure.sender(sender).sendMessage(Component.text("  (なし)", NamedTextColor.GRAY));
        } else {
            allFiles.sort(Comparator.comparing(File::lastModified).reversed());
            for (File file : allFiles) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                adventure.sender(sender).sendMessage(Component.text("  ID: " + config.getInt("numeric-id") + " - " + config.getString("question"), NamedTextColor.GRAY));
            }
        }
    }

    private File findResultFile(int numericId) {
        File[] projectDirs = voteManager.getVotingFolder().listFiles(File::isDirectory);
        if (projectDirs == null) return null;

        for (File dir : projectDirs) {
            File[] resultFiles = dir.listFiles((d, name) -> name.contains("_" + numericId + "_") && name.endsWith(".yml"));
            if (resultFiles != null && resultFiles.length > 0) {
                return resultFiles[0];
            }
        }
        return null;
    }

    private void sendHelp(CommandSender sender) {
        adventure.sender(sender).sendMessage(Component.text("§6--- Voting Command Help ---"));
        adventure.sender(sender).sendMessage(Component.text("§e/voting question <企画名> <\"質問文\"> <選択肢...> [-duration 時間] [-multi]"));
        adventure.sender(sender).sendMessage(Component.text("§e/voting evaluation <企画名> <\"質問文\"> [最大評価]"));
        adventure.sender(sender).sendMessage(Component.text("§e/ans <投票ID> <番号>"));
        adventure.sender(sender).sendMessage(Component.text("§e/voting end <投票ID>"));
        adventure.sender(sender).sendMessage(Component.text("§e/voting result <投票ID> [open|anonymity]"));
        adventure.sender(sender).sendMessage(Component.text("§e/voting average <投票ID | 企画名>"));
        adventure.sender(sender).sendMessage(Component.text("§e/voting list [企画名]"));
        adventure.sender(sender).sendMessage(Component.text("§7スペースを含む引数は \"\" で囲ってください。"));
    }

    private long parseDuration(String durationStr) {
        Pattern pattern = Pattern.compile("(\\d+)([hmd])");
        Matcher matcher = pattern.matcher(durationStr.toLowerCase());
        if (matcher.matches()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            return switch (unit) {
                case "d" -> TimeUnit.DAYS.toMillis(value);
                case "h" -> TimeUnit.HOURS.toMillis(value);
                case "m" -> TimeUnit.MINUTES.toMillis(value);
                default -> -1;
            };
        }
        return -1;
    }
}
