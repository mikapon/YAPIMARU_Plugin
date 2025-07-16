package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.LinkManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class LinkTabCompleter implements TabCompleter {

    private final LinkManager linkManager;
    private static final List<String> OP_COMMANDS = Arrays.asList("create", "delete", "list");
    private static final List<String> MOD_COMMANDS = Arrays.asList("add", "remove", "info", "open", "addmod", "delmod");

    public LinkTabCompleter(YAPIMARU_Plugin plugin) {
        this.linkManager = plugin.getLinkManager();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        List<String> completions = new ArrayList<>();
        String currentArg = args[args.length - 1];

        // /link <subcommand>
        if (args.length == 1) {
            if (player.isOp()) {
                completions.addAll(OP_COMMANDS);
            }
            // MOD_COMMANDS は全プレイヤーに補完候補として表示（権限チェックはコマンド実行時に行う）
            completions.addAll(MOD_COMMANDS);
            return StringUtil.copyPartialMatches(currentArg, completions, new ArrayList<>());
        }

        String subCommand = args[0].toLowerCase();

        // /link <subcommand> <groupName>
        if (args.length == 2) {
            switch (subCommand) {
                case "add", "info", "open", "delete", "addmod", "delmod" -> {
                    List<String> manageableGroups = linkManager.getManageableGroupNames(player);
                    return StringUtil.copyPartialMatches(currentArg, manageableGroups, new ArrayList<>());
                }
            }
        }

        // /link addmod <groupName> <player>
        // /link delmod <groupName> <player>
        if (args.length == 3 && (subCommand.equals("addmod") || subCommand.equals("delmod"))) {
            List<String> playerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            return StringUtil.copyPartialMatches(currentArg, playerNames, new ArrayList<>());
        }

        return List.of();
    }
}