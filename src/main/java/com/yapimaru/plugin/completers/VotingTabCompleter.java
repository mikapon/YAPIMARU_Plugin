package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class VotingTabCompleter implements TabCompleter {

    private final VoteManager voteManager;
    private static final List<String> SUBCOMMANDS = List.of("question", "answer", "end_poll");
    private static final List<String> QUESTION_MODES = List.of("open", "anonymity");
    private static final List<String> END_POLL_MODES = List.of("open", "anonymity");


    public VotingTabCompleter(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, completions);
            return completions;
        }

        String subCommand = args[0].toLowerCase();
        switch(subCommand) {
            case "question":
                if (args.length == 3) {
                    StringUtil.copyPartialMatches(args[2], QUESTION_MODES, completions);
                }
                break;
            case "answer":
                if (args.length == 2) {
                    List<String> pollIds = new ArrayList<>(voteManager.getActivePolls().keySet());
                    StringUtil.copyPartialMatches(args[1], pollIds, completions);
                }
                break;
            case "end_poll":
                if (args.length == 2) {
                    List<String> pollIds = new ArrayList<>(voteManager.getActivePolls().keySet());
                    StringUtil.copyPartialMatches(args[1], pollIds, completions);
                } else if (args.length == 3) {
                    StringUtil.copyPartialMatches(args[2], END_POLL_MODES, completions);
                }
                break;
        }

        return completions;
    }
}