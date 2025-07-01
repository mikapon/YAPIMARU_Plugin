package com.yapimaru.plugin.commands;
import com.yapimaru.plugin.managers.GuiManager;
import org.bukkit.ChatColor;
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
            sender.sendMessage("プレイヤーのみ実行できます。");
            return true;
        }

        // ★★★ 修正箇所 ★★★
        // plugin.ymlで権限を設定したため、ここでの権限チェックは不要
        // if (!p.hasPermission("yapimaru.admin") && !p.hasPermission("yapimaru.creator")) { ... } を削除

        if (args.length == 0) {
            guiManager.openMainMenu(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "tp" -> guiManager.openTeleportMenuAndResetMode(p);
            case "effect" -> guiManager.openEffectMenu(p);
            case "gamemode", "gm" -> guiManager.openGamemodeMenu(p);
            default -> p.sendMessage(ChatColor.RED + "不明なサブコマンドです。/c <tp|effect|gamemode>");
        }
        return true;
    }
}