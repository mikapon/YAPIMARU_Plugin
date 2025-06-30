package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.managers.NameManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class NameTabCompleter implements TabCompleter {
    private static final List<String> SUB1 = List.of("set", "link", "color", "reload");
    private static final List<String> SUB_LINK = List.of("remove");
    private static final List<String> SUB_TARGET = List.of("@a", "@p", "@r", "@s", "//sel");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command c, @NotNull String a, @NotNull String[] args) {
        // ★★★ 修正箇所 ★★★
        if (!sender.hasPermission("yapimaru.admin")) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUB1, completions);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("link")) {
                StringUtil.copyPartialMatches(args[1], SUB_LINK, completions);
            } else if (args[0].equalsIgnoreCase("color")) {
                List<String> colorSuggestions = new ArrayList<>(NameManager.WOOL_COLOR_NAMES);
                colorSuggestions.add("reset");
                StringUtil.copyPartialMatches(args[1], colorSuggestions, completions);
            }
        } else if (args.length >= 3 && args[0].equalsIgnoreCase("color")) {
            List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            players.addAll(SUB_TARGET);
            StringUtil.copyPartialMatches(args[args.length - 1], players, completions);
        }
        return completions;
    }
}