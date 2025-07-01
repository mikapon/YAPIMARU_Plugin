package com.yapimaru.plugin.commands;
import com.yapimaru.plugin.managers.GuiManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class CreatorCommand implements CommandExecutor {
    private final GuiManager guiManager;
    public CreatorCommand(GuiManager guiManager) { this.guiManager = guiManager; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            guiManager.getPlugin().getAdventure().sender(sender).sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            guiManager.openMainMenu(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "tp" -> guiManager.openTeleportMenuAndResetMode(p);
            case "effect" -> guiManager.openEffectMenu(p);
            case "gamemode", "gm" -> guiManager.openGamemodeMenu(p);
            default -> guiManager.getPlugin().getAdventure().player(p).sendMessage(Component.text("不明なサブコマンドです。/c <tp|effect|gamemode>", NamedTextColor.RED));
        }
        return true;
    }
}
