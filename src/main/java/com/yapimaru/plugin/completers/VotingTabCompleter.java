package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class VotingTabCompleter implements TabCompleter {

    private final VoteManager voteManager;

    // ★★★ 修正箇所 ★★★
    // サブコマンドリストから "answer" を削除
    private static final List<String> SUBCOMMANDS = List.of("question", "evaluation", "end", "result", "average", "list");
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
                // case "answer" は削除
                case "end" -> voteManager.getActivePolls().values().stream()
                        .map(p -> String.valueOf(p.getNumericId()))
                        .forEach(completions::add);
                case "result" -> getCompletedPollIds(completions);
                case "average" -> getEvaluationProjectNames(currentArg, completions);
                case "list" -> getProjectNameCompletions(currentArg, completions);
            }
        } else if (args.length == 3) {
            if (subCommand.equals("result")) {
                StringUtil.copyPartialMatches(currentArg, RESULT_MODES, completions);
            }
        }

        return StringUtil.copyPartialMatches(currentArg, completions, new ArrayList<>());
    }

    private void getProjectNameCompletions(String input, List<String> completions) {
        File[] directories = voteManager.getVotingFolder().listFiles(File::isDirectory);
        if (directories != null) {
            List<String> projectNames = Arrays.stream(directories).map(File::getName).collect(Collectors.toList());
            StringUtil.copyPartialMatches(input, projectNames, completions);
        }
    }

    private void getEvaluationProjectNames(String input, List<String> completions) {
        List<String> projectNames = new ArrayList<>();
        File[] directories = voteManager.getVotingFolder().listFiles(File::isDirectory);
        if (directories == null) {
            StringUtil.copyPartialMatches(input, projectNames, completions);
            return;
        }

        for (File dir : directories) {
            File[] resultFiles = dir.listFiles((d, name) -> name.endsWith(".yml"));
            if (resultFiles == null) continue;

            boolean isEvaluationProject = false;
            for (File file : resultFiles) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                if (config.getBoolean("is-evaluation")) {
                    isEvaluationProject = true;
                    break;
                }
            }
            if (isEvaluationProject) {
                projectNames.add(dir.getName());
            }
        }
        StringUtil.copyPartialMatches(input, projectNames, completions);
    }


    private void getCompletedPollIds(List<String> completions) {
        File[] projectDirs = voteManager.getVotingFolder().listFiles(File::isDirectory);
        if (projectDirs == null) return;

        for (File dir : projectDirs) {
            File[] resultFiles = dir.listFiles((d, name) -> name.endsWith(".yml"));
            if (resultFiles == null) continue;

            for (File file : resultFiles) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                int id = config.getInt("numeric-id", 0);
                if (id != 0) {
                    completions.add(String.valueOf(id));
                }
            }
        }
    }
}