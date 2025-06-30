package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.data.VoteData;
import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class VotingCommand implements CommandExecutor {

    private final VoteManager voteManager;

    public VotingCommand(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (!subCommand.equals("answer")) {
            if (!sender.hasPermission("yapimaru.admin")) {
                sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
                return true;
            }
        }

        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        // ★★★ 修正箇所 ★★★
        // switch文を拡張switch式に置換
        switch (subCommand) {
            case "question" -> handleQuestion(sender, subArgs);
            case "evaluation" -> handleEvaluation(sender, subArgs);
            case "answer" -> handleAnswer(sender, subArgs);
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
            sender.sendMessage(ChatColor.RED + "使い方: /voting question <企画名> <\"質問文\"> <選択肢1> <選択肢2> ...");
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
                        sender.sendMessage(ChatColor.RED + "無効な時間形式です。例: 1h, 30m, 2d");
                        return;
                    }
                    currentArgIndex += 2;
                } else {
                    sender.sendMessage(ChatColor.RED + "-duration の後には時間を指定してください。");
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
            sender.sendMessage(ChatColor.RED + "選択肢は少なくとも2つ必要です。");
            return;
        }

        VoteData newVote = voteManager.createPoll(directoryName, question, options, multiChoice, false, durationMillis);
        if (newVote != null) {
            sender.sendMessage(ChatColor.GREEN + "投票「" + question + "」(ID: " + newVote.getNumericId() + ") を開始しました。");
        } else {
            sender.sendMessage(ChatColor.RED + "同じ企画・質問の投票「" + question + "」は既に存在します。");
        }
    }

    private void handleEvaluation(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting evaluation <企画名> <\"質問文\"> [最大評価]");
            return;
        }

        String projectName = args[0];
        String question = args[1];

        Integer maxStars = voteManager.getMaxStarsForProject(projectName);
        if (maxStars == 0) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "エラー: この企画で最初の採点投票です。最大評価（星の数）を指定してください。");
                sender.sendMessage(ChatColor.GRAY + "例: /voting evaluation " + projectName + " \"" + question + "\" 5");
                return;
            }
            try {
                maxStars = Integer.parseInt(args[2]);
                if (maxStars <= 0 || maxStars > 10) {
                    sender.sendMessage(ChatColor.RED + "最大評価は1から10の間で指定してください。");
                    return;
                }
                voteManager.setMaxStarsForProject(projectName, maxStars);
                sender.sendMessage(ChatColor.GREEN + "企画「" + projectName + "」の最大評価を星" + maxStars + "に設定しました。");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "最大評価には数字を指定してください。");
                return;
            }
        }

        List<String> options = IntStream.rangeClosed(1, maxStars)
                .mapToObj(i -> "☆".repeat(i))
                .collect(Collectors.toList());

        VoteData newVote = voteManager.createPoll(projectName, question, options, false, true, 0);
        if (newVote != null) {
            sender.sendMessage(ChatColor.GREEN + "採点投票「" + question + "」(ID: " + newVote.getNumericId() + ") を開始しました。");
        } else {
            sender.sendMessage(ChatColor.RED + "同じ企画・質問の投票「" + question + "」は既に存在します。");
        }
    }

    private void handleAnswer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "使い方: /voting answer <投票ID> <番号>");
            return;
        }

        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "投票IDは数字で指定してください。");
            return;
        }

        VoteData voteData = voteManager.getPollByNumericId(numericId);

        if (voteData == null) {
            player.sendMessage(ChatColor.RED + "アクティブな投票ID「" + numericId + "」は見つかりません。");
            return;
        }

        int choice;
        try {
            choice = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "選択肢は番号で指定してください。");
            return;
        }

        if (voteData.vote(player.getUniqueId(), choice)) {
            player.sendMessage(ChatColor.GREEN + "投票「" + voteData.getQuestion() + "」の選択肢 " + choice + " に投票しました。");
            voteManager.updatePlayerVoteStatus(player);
        } else {
            player.sendMessage(ChatColor.RED + "無効な選択肢です。");
        }
    }

    private void handleEnd(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting end <投票ID>");
            return;
        }

        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "投票IDは数字で指定してください。");
            return;
        }

        VoteData voteData = voteManager.getPollByNumericId(numericId);
        if (voteData == null) {
            sender.sendMessage(ChatColor.RED + "アクティブな投票ID「" + numericId + "」は見つかりません。");
            return;
        }

        String fullPollId = voteData.getDirectoryName() + "::" + voteData.getQuestion();
        VoteData endedVote = voteManager.endPoll(fullPollId);

        if (endedVote != null) {
            sender.sendMessage(ChatColor.GREEN + "投票「" + endedVote.getQuestion() + "」(ID: " + endedVote.getNumericId() + ") を終了しました。");
        }
    }

    private void handleResult(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting result <投票ID> [open|anonymity]");
            return;
        }
        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "投票IDは数字で指定してください。");
            return;
        }

        File resultFile = findResultFile(numericId);
        if (resultFile == null) {
            sender.sendMessage(ChatColor.RED + "終了した投票ID「" + numericId + "」の結果ファイルが見つかりません。");
            return;
        }

        YamlConfiguration resultConfig = YamlConfiguration.loadConfiguration(resultFile);
        VoteManager.ResultDisplayMode displayMode = (args.length > 1 && args[1].equalsIgnoreCase("open")) ? VoteManager.ResultDisplayMode.OPEN : VoteManager.ResultDisplayMode.ANONYMITY;

        voteManager.displayResults(resultConfig, displayMode);
    }

    private void handleAverage(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting average <投票ID | 企画名>");
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
            sender.sendMessage(ChatColor.RED + "終了した投票ID「" + numericId + "」の結果ファイルが見つかりません。");
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(resultFile);
        if (!config.getBoolean("is-evaluation")) {
            sender.sendMessage(ChatColor.RED + "投票ID「" + numericId + "」は採点投票ではありません。");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "--- 平均評価 (ID: " + numericId + ") ---");
        sender.sendMessage(ChatColor.AQUA + config.getString("question") + ": " + ChatColor.YELLOW + String.format("%.2f", config.getDouble("average-rating")));
    }

    private void handleProjectAverage(CommandSender sender, String projectName) {
        File projectDir = new File(voteManager.getVotingFolder(), projectName);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            sender.sendMessage(ChatColor.RED + "企画名「" + projectName + "」の投票結果が見つかりません。");
            return;
        }
        File[] resultFiles = projectDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (resultFiles == null || resultFiles.length == 0) {
            sender.sendMessage(ChatColor.RED + "企画名「" + projectName + "」の投票結果が見つかりません。");
            return;
        }

        List<Map.Entry<String, Double>> averages = new ArrayList<>();
        for (File file : resultFiles) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (config.getBoolean("is-evaluation")) {
                averages.add(new AbstractMap.SimpleEntry<>(config.getString("question"), config.getDouble("average-rating")));
            }
        }

        if (averages.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "企画名「" + projectName + "」には採点投票の結果がありません。");
            return;
        }

        averages.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        sender.sendMessage(ChatColor.GOLD + "--- 平均評価ランキング: " + projectName + " ---");
        for (int i = 0; i < averages.size(); i++) {
            Map.Entry<String, Double> entry = averages.get(i);
            sender.sendMessage(ChatColor.YELLOW + "" + (i+1) + ". " + String.format("%.2f", entry.getValue()) + " - " + ChatColor.AQUA + entry.getKey());
        }
    }

    // ★★★ 修正箇所 ★★★
    // 返り値が常にtrueだったため、voidに変更
    private void handleList(CommandSender sender, String[] args) {
        String projectName = (args.length > 0) ? String.join(" ", args) : null;

        sender.sendMessage(ChatColor.GOLD + "--- 投票一覧" + (projectName != null ? ": " + projectName : "") + " ---");

        sender.sendMessage(ChatColor.GREEN + "● 進行中の投票");
        boolean hasActive = false;
        for (VoteData vote : voteManager.getActivePolls().values()) {
            if (projectName == null || vote.getDirectoryName().equalsIgnoreCase(projectName)) {
                sender.sendMessage(ChatColor.WHITE + "  ID: " + vote.getNumericId() + " - " + vote.getQuestion());
                hasActive = true;
            }
        }
        if (!hasActive) {
            sender.sendMessage(ChatColor.GRAY + "  (なし)");
        }

        sender.sendMessage(ChatColor.RED + "● 終了した投票");
        File searchDir = (projectName != null) ? new File(voteManager.getVotingFolder(), projectName) : voteManager.getVotingFolder();
        if (!searchDir.exists()) {
            sender.sendMessage(ChatColor.GRAY + "  (なし)");
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
            sender.sendMessage(ChatColor.GRAY + "  (なし)");
        } else {
            allFiles.sort(Comparator.comparing(File::lastModified).reversed());
            for (File file : allFiles) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                sender.sendMessage(ChatColor.GRAY + "  ID: " + config.getInt("numeric-id") + " - " + config.getString("question"));
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
        sender.sendMessage(ChatColor.GOLD + "--- Voting Command Help ---");
        sender.sendMessage(ChatColor.AQUA + "/voting question <企画名> <\"質問文\"> <選択肢...> [-duration 時間] [-multi]");
        sender.sendMessage(ChatColor.AQUA + "/voting evaluation <企画名> <\"質問文\"> [最大評価]");
        sender.sendMessage(ChatColor.AQUA + "/voting answer <投票ID> <番号>");
        sender.sendMessage(ChatColor.AQUA + "/voting end <投票ID>");
        sender.sendMessage(ChatColor.AQUA + "/voting result <投票ID> [open|anonymity]");
        sender.sendMessage(ChatColor.AQUA + "/voting average <投票ID | 企画名>");
        sender.sendMessage(ChatColor.AQUA + "/voting list [企画名]");
        sender.sendMessage(ChatColor.GRAY + "スペースを含む引数は \"\" で囲ってください。");
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