package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.LinkManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class LinkCommand implements CommandExecutor {

    private final LinkManager linkManager;
    private final BukkitAudiences adventure;

    public LinkCommand(YAPIMARU_Plugin plugin) {
        this.linkManager = plugin.getLinkManager();
        this.adventure = plugin.getAdventure();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "create" -> handleCreate(player, subArgs);
            case "delete" -> handleDelete(player, subArgs);
            case "list" -> handleList(player);
            case "add" -> handleAdd(player, subArgs);
            case "remove" -> handleRemove(player);
            case "info" -> handleInfo(player, subArgs);
            case "open" -> handleOpen(player, subArgs);
            case "addmod" -> handleAddMod(player, subArgs);
            case "delmod" -> handleDelMod(player, subArgs);
            case "mode" -> handleMode(player);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (!player.isOp()) {
            adventure.player(player).sendMessage(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED));
            return;
        }
        if (args.length < 1) {
            adventure.player(player).sendMessage(Component.text("使い方: /link create <名前>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        linkManager.createGroup(player, groupName);
    }

    private void handleDelete(Player player, String[] args) {
        if (!player.isOp()) {
            adventure.player(player).sendMessage(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED));
            return;
        }
        if (args.length < 1) {
            adventure.player(player).sendMessage(Component.text("使い方: /link delete <名前>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        linkManager.deleteGroup(player, groupName);
    }

    private void handleList(Player player) {
        if (!player.isOp()) {
            adventure.player(player).sendMessage(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED));
            return;
        }
        linkManager.listGroups(player);
    }

    private void handleAdd(Player player, String[] args) {
        if (args.length < 1) {
            adventure.player(player).sendMessage(Component.text("使い方: /link add <名前>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        if (!player.isOp() && !linkManager.isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このグループを編集する権限がありません。", NamedTextColor.RED));
            return;
        }
        linkManager.startAddProcess(player, groupName);
    }

    private void handleRemove(Player player) {
        if (!player.isOp() && !linkManager.canManageAnyGroup(player.getUniqueId())) {
            adventure.player(player).sendMessage(Component.text("いずれかのグループの管理者である必要があります。", NamedTextColor.RED));
            return;
        }
        linkManager.startRemoveProcess(player);
    }


    private void handleInfo(Player player, String[] args) {
        if (args.length < 1) {
            adventure.player(player).sendMessage(Component.text("使い方: /link info <名前>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        if (!player.isOp() && !linkManager.isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このグループの情報を閲覧する権限がありません。", NamedTextColor.RED));
            return;
        }
        linkManager.displayGroupInfo(player, groupName);
    }

    private void handleOpen(Player player, String[] args) {
        if (args.length < 1) {
            adventure.player(player).sendMessage(Component.text("使い方: /link open <名前>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        if (!player.isOp() && !linkManager.isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このグループを開く権限がありません。", NamedTextColor.RED));
            return;
        }
        linkManager.openVirtualInventory(player, groupName);
    }

    private void handleAddMod(Player player, String[] args) {
        if (args.length < 2) {
            adventure.player(player).sendMessage(Component.text("使い方: /link addmod <名前> <プレイヤー名>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        if (!player.isOp() && !linkManager.isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このグループの管理者を設定する権限がありません。", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            adventure.player(player).sendMessage(Component.text("プレイヤー「" + args[1] + "」が見つかりません。", NamedTextColor.RED));
            return;
        }
        linkManager.addModerator(player, groupName, target);
    }

    private void handleDelMod(Player player, String[] args) {
        if (args.length < 2) {
            adventure.player(player).sendMessage(Component.text("使い方: /link delmod <名前> <プレイヤー名>", NamedTextColor.RED));
            return;
        }
        String groupName = args[0];
        if (!player.isOp() && !linkManager.isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このグループの管理者を設定する権限がありません。", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            adventure.player(player).sendMessage(Component.text("プレイヤー「" + args[1] + "」が見つかりません。", NamedTextColor.RED));
            return;
        }
        linkManager.removeModerator(player, groupName, target);
    }

    private void handleMode(Player player) {
        if (!player.isOp() && !linkManager.canManageAnyGroup(player.getUniqueId())) {
            adventure.player(player).sendMessage(Component.text("いずれかのグループの管理者である必要があります。", NamedTextColor.RED));
            return;
        }
        linkManager.toggleLinkEditMode(player);
    }


    private void sendHelp(Player player) {
        adventure.player(player).sendMessage(Component.text("--- 共有チェスト機能 ヘルプ ---", NamedTextColor.GOLD));
        if (player.isOp() || linkManager.canManageAnyGroup(player.getUniqueId())) {
            adventure.player(player).sendMessage(Component.text("/link mode - リンク編集モード切替", NamedTextColor.YELLOW));
        }
        if (player.isOp()) {
            adventure.player(player).sendMessage(Component.text("/link create <名前> - 新規グループ作成", NamedTextColor.AQUA));
            adventure.player(player).sendMessage(Component.text("/link delete <名前> - グループ削除", NamedTextColor.AQUA));
            adventure.player(player).sendMessage(Component.text("/link list - 全グループ一覧", NamedTextColor.AQUA));
        }
        adventure.player(player).sendMessage(Component.text("/link add <名前> - チェスト追加", NamedTextColor.AQUA));
        adventure.player(player).sendMessage(Component.text("/link remove - チェスト解除", NamedTextColor.AQUA));
        adventure.player(player).sendMessage(Component.text("/link info <名前> - グループ情報", NamedTextColor.AQUA));
        adventure.player(player).sendMessage(Component.text("/link open <名前> - 仮想インベントリを開く", NamedTextColor.AQUA));
        adventure.player(player).sendMessage(Component.text("/link addmod <名前> <プレイヤー> - 管理者任命", NamedTextColor.AQUA));
        adventure.player(player).sendMessage(Component.text("/link delmod <名前> <プレイヤー> - 管理者解任", NamedTextColor.AQUA));
    }
}