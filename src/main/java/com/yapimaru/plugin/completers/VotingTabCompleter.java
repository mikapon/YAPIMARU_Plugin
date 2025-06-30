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

    public VotingTabCompleter(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        String currentArg = args[args.length - 1];

        if (args.length == 1) {
            StringUtil.copyPartialMatches(currentArg, SUBCOMMANDS, completions);
            return completions;
        }

        String subCommand = args[0].toLowerCase();
        if (args.length == 2) {
            switch (subCommand) {
                case "answer":
                case "end":
                    List<String> activePollIds = voteManager.getActivePolls().values().stream()
                            .map(p -> String.valueOf(p.getNumericId()))
                            .collect(Collectors.toList());
                    StringUtil.copyPartialMatches(currentArg, activePollIds, completions);
                    break;
                case "list":
                case "average":
                    getProjectNameCompletions(currentArg, completions);
                    break;
            }
        } else if (args.length == 3) {
            if (subCommand.equals("end") || subCommand.equals("result")) {
                StringUtil.copyPartialMatches(currentArg, RESULT_MODES, completions);
            }
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