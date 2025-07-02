package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.managers.ParticipantManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class YmTabCompleter implements TabCompleter {
    private final ParticipantManager participantManager;
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "list", "cmlist", "participant");

    public YmTabCompleter(ParticipantManager participantManager) {
        this.participantManager = participantManager;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }

        return Collections.emptyList();
    }
}