package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.data.VoteData;
import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class AnsCommand implements CommandExecutor {

    private final VoteManager voteManager;

    public AnsCommand(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "使い方: /ans <投票ID> <番号>");
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
}