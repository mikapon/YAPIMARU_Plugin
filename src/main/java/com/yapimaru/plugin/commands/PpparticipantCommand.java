package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.MigrationManager;
import com.yapimaru.plugin.managers.NameManager;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class PpparticipantCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final NameManager nameManager;

    public PpparticipantCommand(YAPIMARU_Plugin plugin, ParticipantManager participantManager, NameManager nameManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
        this.nameManager = nameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("このコマンドを使用する権限がありません。", NamedTextColor.RED));
            return true;
        }

        plugin.getAdventure().sender(sender).sendMessage(Component.text("config.ymlからのデータ移行を開始します...", NamedTextColor.YELLOW));

        // MigrationManagerをインスタンス化して実行
        MigrationManager migrationManager = new MigrationManager();
        migrationManager.migrate(plugin, participantManager);

        // 移行後にマネージャーのデータを再読み込みして即時反映させる
        participantManager.reloadAllParticipants();
        nameManager.reloadData();

        plugin.getAdventure().sender(sender).sendMessage(Component.text("データ移行が完了し、全参加者情報が再読み込みされました。", NamedTextColor.GREEN));

        return true;
    }
}