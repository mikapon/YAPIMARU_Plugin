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
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        return switch (subCommand) {
            case "question" -> handleQuestion(sender, subArgs);
            case "evaluation" -> handleEvaluation(sender, subArgs);
            case "answer" -> handleAnswer(sender, subArgs);
            case "end" -> handleEnd(sender, subArgs);
            case "result" -> handleResult(sender, subArgs);
            case "average" -> handleAverage(sender, subArgs);
            case "list" -> handleList(sender, subArgs);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleQuestion(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting question <企画名> <\"質問文\"> <選択肢1> <選択肢2> ...");
            return false;
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
                        return false;
                    }
                    currentArgIndex += 2;
                } else {
                    sender.sendMessage(ChatColor.RED + "-duration の後には時間を指定してください。");
                    return false;
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
            return false;
        }

        VoteData newVote = voteManager.createPoll(directoryName, question, options, multiChoice, false, durationMillis);
        if (newVote != null) {
            sender.sendMessage(ChatColor.GREEN + "投票「" + question + "」(ID: " + newVote.getNumericId() + ") を開始しました。");
        } else {
            sender.sendMessage(ChatColor.RED + "同じ企画・質問の投票「" + question + "」は既に存在します。");
        }
        return true;
    }

    private boolean handleEvaluation(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting evaluation <企画名> <\"質問文\"> [最大評価]");
            return false;
        }

        String projectName = args[0];
        String question = args[1];

        Integer maxStars = voteManager.getMaxStarsForProject(projectName);
        if (maxStars == 0) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "エラー: この企画で最初の採点投票です。最大評価（星の数）を指定してください。");
                sender.sendMessage(ChatColor.GRAY + "例: /voting evaluation " + projectName + " \"" + question + "\" 5");
                return false;
            }
            try {
                maxStars = Integer.parseInt(args[2]);
                if (maxStars <= 0 || maxStars > 10) {
                    sender.sendMessage(ChatColor.RED + "最大評価は1から10の間で指定してください。");
                    return false;
                }
                voteManager.setMaxStarsForProject(projectName, maxStars);
                sender.sendMessage(ChatColor.GREEN + "企画「" + projectName + "」の最大評価を星" + maxStars + "に設定しました。");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "最大評価には数字を指定してください。");
                return false;
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
        return true;
    }

    private boolean handleAnswer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "使い方: /voting answer <投票ID> <番号>");
            return false;
        }

        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "投票IDは数字で指定してください。");
            return true;
        }

        VoteData voteData = voteManager.getPollByNumericId(numericId);

        if (voteData == null) {
            player.sendMessage(ChatColor.RED + "アクティブな投票ID「" + numericId + "」は見つかりません。");
            return true;
        }

        int choice;
        try {
            choice = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "選択肢は番号で指定してください。");
            return true;
        }

        if (voteData.vote(player.getUniqueId(), choice)) {
            player.sendMessage(ChatColor.GREEN + "投票「" + voteData.getQuestion() + "」の選択肢 " + choice + " に投票しました。");
            voteManager.updatePlayerVoteStatus(player);
        } else {
            player.sendMessage(ChatColor.RED + "無効な選択肢です。");
        }
        return true;
    }

    private boolean handleEnd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting end <投票ID> [open]");
            return false;
        }

        int numericId;
        try {
            numericId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "投票IDは数字で指定してください。");
            return true;
        }

        VoteData voteData = voteManager.getPollByNumericId(numericId);
        if (voteData == null) {
            sender.sendMessage(ChatColor.RED + "アクティブな投票ID「" + numericId + "」は見つかりません。");
            return true;
        }

        String fullPollId = voteData.getDirectoryName() + "::" + voteData.getQuestion();
        VoteData endedVote = voteManager.endPoll(fullPollId);

        if (endedVote != null) {
            sender.sendMessage(ChatColor.GREEN + "投票「" + endedVote.getQuestion() + "」を終了しました。");
            VoteManager.ResultDisplayMode displayMode = (args.length > 1 && args[1].equalsIgnoreCase("open")) ? VoteManager.ResultDisplayMode.OPEN : VoteManager.ResultDisplayMode.ANONYMITY;
            // 結果表示はブロードキャストされるのでここでは不要
        }
        return true;
    }

    private boolean handleResult(CommandSender sender, String[] args) {
        // ... (実装)
        return true;
    }

    private boolean handleAverage(CommandSender sender, String[] args) {
        // ... (実装)
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        // ... (実装)
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- Voting Command Help ---");
        sender.sendMessage(ChatColor.AQUA + "/voting question <企画名> <\"質問文\"> <選択肢...> [-duration] [-multi]");
        sender.sendMessage(ChatColor.AQUA + "/voting evaluation <企画名> <\"質問文\"> [最大評価]");
        sender.sendMessage(ChatColor.AQUA + "/voting answer <投票ID> <番号>");
        sender.sendMessage(ChatColor.AQUA + "/voting end <投票ID> [open]");
        sender.sendMessage(ChatColor.AQUA + "/voting result <投票ID> [open]");
        sender.sendMessage(ChatColor.AQUA + "/voting average <投票ID | k:企画名>");
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