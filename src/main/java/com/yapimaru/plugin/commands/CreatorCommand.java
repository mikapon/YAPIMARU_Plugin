package com.yapimaru.plugin.commands;
import com.yapimaru.plugin.managers.GuiManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CreatorCommand implements CommandExecutor {
    private final GuiManager guiManager;
    public CreatorCommand(GuiManager guiManager) { this.guiManager = guiManager; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("プレイヤーのみ実行できます。");
            return true;
        }
        if (args.length == 0) {
            guiManager.openMainMenu(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "tp" -> guiManager.openTeleportMenu(p);
            case "effect" -> guiManager.openEffectMenu(p);
            case "gamemode", "gm" -> guiManager.openGamemodeMenu(p);
            default -> p.sendMessage(ChatColor.RED + "不明なサブコマンドです。/c <tp|effect|gamemode>");
        }
        return true;
    }
}