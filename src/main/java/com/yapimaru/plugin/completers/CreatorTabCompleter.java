package com.yapimaru.plugin.completers;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CreatorTabCompleter implements TabCompleter {
    private static final List<String> SUB = List.of("tp", "effect", "gamemode", "gm");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String a, @NotNull String[] args) {
        // ★★★ 修正箇所 ★★★
        // コマンドの実行権限がない場合は、候補を何も表示しない
        if (!s.hasPermission("yapimaru.admin") && !s.hasPermission("yapimaru.creator")) {
            return Collections.emptyList();
        }

        if (args.length == 1) return StringUtil.copyPartialMatches(args[0], SUB, new ArrayList<>());
        return Collections.emptyList();
    }
}