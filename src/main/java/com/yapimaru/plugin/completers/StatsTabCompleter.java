package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.data.ParticipantData;
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
import java.util.stream.Collectors;

public class StatsTabCompleter implements TabCompleter {
    private final ParticipantManager participantManager;
    private static final List<String> SUBCOMMANDS = Arrays.asList("player", "list");
    private static final List<String> STAT_NAMES = Arrays.asList(
            "total_deaths", "total_joins", "total_playtime_seconds",
            "photoshoot_participations", "total_chats", "w_count"
    );

    public StatsTabCompleter(ParticipantManager participantManager) {
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

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("player")) {
                List<String> participantIds = participantManager.getActiveParticipants().stream()
                        .map(ParticipantData::getParticipantId)
                        .collect(Collectors.toList());
                return StringUtil.copyPartialMatches(args[1], participantIds, new ArrayList<>());
            }
            if (args[0].equalsIgnoreCase("list")) {
                return StringUtil.copyPartialMatches(args[1], STAT_NAMES, new ArrayList<>());
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("list")) {
                if (STAT_NAMES.contains(args[1].toLowerCase())) {
                    return StringUtil.copyPartialMatches(args[2], Collections.singletonList("worst"), new ArrayList<>());
                }
            }
        }

        return Collections.emptyList();
    }
}