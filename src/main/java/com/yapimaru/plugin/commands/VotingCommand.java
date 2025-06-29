package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.data.VoteData;
import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VotingCommand implements CommandExecutor {

    private final VoteManager voteManager;

    public VotingCommand(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, args.length - 1);

        switch (subCommand) {
            case "question":
                return handleQuestion(sender, subArgs);
            case "answer":
                return handleAnswer(sender, subArgs);
            case "end_poll":
                return handleEndPoll(sender, subArgs);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleQuestion(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting question <企画名> <質問文> <選択肢1> <選択肢2> ... [-duration <時間>] [-multi]");
            return true;
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
                        return true;
                    }
                    currentArgIndex += 2;
                } else {
                    sender.sendMessage(ChatColor.RED + "-duration の後には時間を指定してください。");
                    return true;
                }
            } else if (arg.equalsIgnoreCase("-multi")) {
                multiChoice = true;
                currentArgIndex++;
            } else {
                options.add(arg);
                currentArgIndex++;
            }
        }

        if (options.size() < 2) {
            sender.sendMessage(ChatColor.RED + "選択肢は少なくとも2つ必要です。");
            return true;
        }

        String fullPollId = directoryName + "::" + question;
        if (voteManager.createPoll(directoryName, question, options, multiChoice, durationMillis, sender)) {
            sender.sendMessage(ChatColor.GREEN + "投票「" + question + "」を開始しました。");
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
        if (args.length != 3) {
            player.sendMessage(ChatColor.RED + "使い方: /voting answer <企画名> <質問文> <番号>");
            player.sendMessage(ChatColor.GRAY + "企画名や質問文にスペースが含まれる場合は \"\" で囲ってください。");
            return true;
        }

        String directoryName = args[0];
        String question = args[1];
        String fullPollId = directoryName + "::" + question;
        VoteData voteData = voteManager.getPoll(fullPollId);

        if (voteData == null) {
            player.sendMessage(ChatColor.RED + "投票「" + question + "」は見つかりません。");
            return true;
        }

        int choice;
        try {
            choice = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "選択肢は番号で指定してください。");
            return true;
        }

        if (voteData.vote(player.getUniqueId(), choice)) {
            player.sendMessage(ChatColor.GREEN + "投票「" + question + "」の選択肢 " + choice + " に投票しました。");
            voteManager.updatePlayerVoteStatus(player);
        } else {
            player.sendMessage(ChatColor.RED + "無効な選択肢です。");
        }
        return true;
    }

    private boolean handleEndPoll(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "使い方: /voting end_poll <企画名> <質問文> <open|anonymity>");
            return true;
        }

        String directoryName = args[0];
        String question = args[1];
        String fullPollId = directoryName + "::" + question;
        if (voteManager.getPoll(fullPollId) == null) {
            sender.sendMessage(ChatColor.RED + "投票「" + question + "」は見つかりません。");
            return true;
        }

        VoteManager.ResultDisplayMode displayMode;
        try {
            displayMode = VoteManager.ResultDisplayMode.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "結果表示モードは 'open' または 'anonymity' を指定してください。");
            return true;
        }

        voteManager.endPoll(fullPollId, displayMode);
        sender.sendMessage(ChatColor.GREEN + "投票「" + question + "」を終了しました。");
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- Voting Command Help ---");
        sender.sendMessage(ChatColor.AQUA + "/voting question <企画名> <質問文> <選択肢...> [-duration 時間] [-multi]");
        sender.sendMessage(ChatColor.AQUA + "/voting answer <企画名> <質問文> <番号>");
        sender.sendMessage(ChatColor.AQUA + "/voting end_poll <企画名> <質問文> <open|anonymity>");
        sender.sendMessage(ChatColor.GRAY + "企画名や質問文にスペースが含まれる場合は \"\" で囲ってください。");
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