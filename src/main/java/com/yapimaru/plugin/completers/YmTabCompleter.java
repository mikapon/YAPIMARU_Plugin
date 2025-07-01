package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ParticipantManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class YmTabCompleter implements TabCompleter {
    private final ParticipantManager participantManager;
    private static final List<String> SUBCOMMANDS = List.of("reload", "list", "cmlist", "participant");
    private static final List<String> PARTICIPANT_SUB = List.of("add", "remove", "list");
    private static final List<String> PARTICIPANT_LIST_SUB = List.of("participant", "discharge");

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

        // /ym participant ... のタブ補完
        if (args.length > 1 && args[0].equalsIgnoreCase("participant")) {
            if (args.length == 2) {
                return StringUtil.copyPartialMatches(args[1], PARTICIPANT_SUB, new ArrayList<>());
            }
            if (args.length == 3) {
                if (args[1].equalsIgnoreCase("add")) {
                    List<String> dischargedNames = participantManager.getDischargedParticipants().stream()
                            .map(ParticipantData::getParticipantId)
                            .collect(Collectors.toList());
                    return StringUtil.copyPartialMatches(args[2], dischargedNames, new ArrayList<>());
                }
                if (args[1].equalsIgnoreCase("remove")) {
                    List<String> activeNames = participantManager.getActiveParticipants().stream()
                            .map(ParticipantData::getParticipantId)
                            .collect(Collectors.toList());
                    return StringUtil.copyPartialMatches(args[2], activeNames, new ArrayList<>());
                }
                if (args[1].equalsIgnoreCase("list")) {
                    return StringUtil.copyPartialMatches(args[2], PARTICIPANT_LIST_SUB, new ArrayList<>());
                }
            }
        }

        return Collections.emptyList();
    }
}