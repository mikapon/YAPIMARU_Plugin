package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.managers.SpectatorManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class SpectatorCommand implements CommandExecutor {

    private final SpectatorManager spectatorManager;
    private final BukkitAudiences adventure;

    public SpectatorCommand(SpectatorManager spectatorManager, BukkitAudiences adventure) {
        this.spectatorManager = spectatorManager;
        this.adventure = adventure;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            boolean currentState = spectatorManager.isEnabled();
            adventure.sender(sender).sendMessage(Component.text("スペクテイター非表示機能は現在 " + (currentState ? "有効" : "無効") + " です。", NamedTextColor.YELLOW));
            adventure.sender(sender).sendMessage(Component.text("使い方: /spectator <on|off>", NamedTextColor.GRAY));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on":
                spectatorManager.setEnabled(true, sender);
                break;
            case "off":
                spectatorManager.setEnabled(false, sender);
                break;
            default:
                adventure.sender(sender).sendMessage(Component.text("使い方: /spectator <on|off>", NamedTextColor.RED));
                break;
        }
        return true;
    }
}