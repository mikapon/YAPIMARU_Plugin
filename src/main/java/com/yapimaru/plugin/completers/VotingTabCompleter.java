package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class VotingTabCompleter implements TabCompleter {

    private final VoteManager voteManager;
    private static final List<String> SUBCOMMANDS = List.of("question", "evaluation", "answer", "end", "result", "average", "list");
    private static final List<String> RESULT_MODES = List.of("open", "anonymity");
    private static final List<String> AVG_PREFIX = List.of("k:");
    private static final List<String> LIST_PREFIX = List.of("k:");

    public VotingTabCompleter(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, completions);
            return completions;
        }

        String subCommand = args[0].toLowerCase();
        switch(subCommand) {
            case "answer":
            case "end":
            case "result":
                if (args.length == 2) {
                    List<String> activePollIds = voteManager.getActivePolls().values().stream()
                            .map(p -> String.valueOf(p.getNumericId()))
                            .collect(Collectors.toList());
                    StringUtil.copyPartialMatches(args[1], activePollIds, completions);
                } else if (args.length == 3 && (subCommand.equals("end") || subCommand.equals("result"))) {
                    StringUtil.copyPartialMatches(args[2], RESULT_MODES, completions);
                }
                break;
            case "average":
                if (args.length == 2) {
                    if (args[1].startsWith("k:")) {
                        getProjectNameCompletions(args[1].substring(2), completions);
                    } else {
                        StringUtil.copyPartialMatches(args[1], AVG_PREFIX, completions);
                    }
                }
                break;
            case "list":
                if (args.length == 2) {
                    if (args[1].startsWith("k:")) {
                        getProjectNameCompletions(args[1].substring(2), completions);
                    } else {
                        StringUtil.copyPartialMatches(args[1], LIST_PREFIX, completions);
                        getProjectNameCompletions(args[1], completions);
                    }
                }
                break;
        }

        return completions;
    }

    private void getProjectNameCompletions(String input, List<String> completions) {
        File[] directories = voteManager.getVotingFolder().listFiles(File::isDirectory);
        if (directories != null) {
            List<String> projectNames = Arrays.stream(directories).map(File::getName).collect(Collectors.toList());
            StringUtil.copyPartialMatches(input, projectNames, completions);
        }
    }
}