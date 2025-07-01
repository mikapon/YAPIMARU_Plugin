package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.managers.ParticipantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PhotographingCommand implements CommandExecutor {
    private final ParticipantManager participantManager;

    public PhotographingCommand(ParticipantManager participantManager) {
        this.participantManager = participantManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage(ChatColor.RED + "このコマンドを使用する権限がありません。");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("on")) {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                participantManager.incrementPhotoshootParticipations(player.getUniqueId());
                count++;
            }
            sender.sendMessage(ChatColor.GREEN + "オンラインの " + count + " 人の撮影参加回数を+1しました。");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "使い方: /photographing on");
        return true;
    }
}