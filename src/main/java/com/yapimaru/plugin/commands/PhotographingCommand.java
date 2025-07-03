package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;

public class PhotographingCommand implements CommandExecutor {
    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;

    public PhotographingCommand(YAPIMARU_Plugin plugin, ParticipantManager participantManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("このコマンドを使用する権限がありません。", NamedTextColor.RED));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("on")) {
            int count = 0;
            LocalDateTime now = LocalDateTime.now();
            for (Player player : Bukkit.getOnlinePlayers()) {
                participantManager.incrementPhotoshootParticipations(player.getUniqueId(), now);
                count++;
            }
            plugin.getAdventure().sender(sender).sendMessage(Component.text("オンラインの " + count + " 人の撮影参加回数を+1しました。", NamedTextColor.GREEN));
            return true;
        }

        plugin.getAdventure().sender(sender).sendMessage(Component.text("使い方: /photographing on", NamedTextColor.RED));
        return true;
    }
}