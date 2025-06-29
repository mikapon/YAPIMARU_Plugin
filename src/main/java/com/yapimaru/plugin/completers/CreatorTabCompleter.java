package com.yapimaru.plugin.completers;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import java.util.ArrayList;
import java.util.List;

public class CreatorTabCompleter implements TabCompleter {
    private static final List<String> SUB = List.of("tp", "effect", "gamemode", "gm");

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return StringUtil.copyPartialMatches(args[0], SUB, new ArrayList<>());
        return new ArrayList<>();
    }
}