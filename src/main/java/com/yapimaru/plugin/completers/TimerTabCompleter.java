package com.yapimaru.plugin.completers;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TimerTabCompleter implements TabCompleter {
    private static final List<String> COMMANDS = List.of("on", "off", "reset", "set", "start", "pause", "restart", "stop", "add", "mode", "display", "onend", "prestart");
    private static final List<String> MODE_ARGS = List.of("countdown", "countup");
    private static final List<String> DISPLAY_ARGS = List.of("actionbar", "bossbar", "title", "off");
    private static final List<String> ONEND_ARGS = List.of("add", "remove", "list");
    private static final List<String> ONEND_TYPE_ARGS = List.of("msg", "cmd");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        // ★★★ 修正箇所 ★★★
        if (!sender.hasPermission("yapimaru.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], COMMANDS, new ArrayList<>());
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "mode" -> {
                    return StringUtil.copyPartialMatches(args[1], MODE_ARGS, new ArrayList<>());
                }
                case "display" -> {
                    return StringUtil.copyPartialMatches(args[1], DISPLAY_ARGS, new ArrayList<>());
                }
                case "onend" -> {
                    return StringUtil.copyPartialMatches(args[1], ONEND_ARGS, new ArrayList<>());
                }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("onend")) {
            switch(args[1].toLowerCase()) {
                case "add", "remove", "list" -> {
                    return StringUtil.copyPartialMatches(args[2], ONEND_TYPE_ARGS, new ArrayList<>());
                }
            }
        }
        return Collections.emptyList();
    }
}