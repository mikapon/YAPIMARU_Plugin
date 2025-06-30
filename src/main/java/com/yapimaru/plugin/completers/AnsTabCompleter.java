package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.data.VoteData;
import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AnsTabCompleter implements TabCompleter {
    private final VoteManager voteManager;

    public AnsTabCompleter(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            // アクティブな投票のIDを補完
            List<String> activePollIds = voteManager.getActivePolls().values().stream()
                    .map(p -> String.valueOf(p.getNumericId()))
                    .collect(Collectors.toList());
            StringUtil.copyPartialMatches(args[0], activePollIds, completions);
        } else if (args.length == 2) {
            // 指定された投票IDの選択肢番号を補完
            try {
                int numericId = Integer.parseInt(args[0]);
                VoteData voteData = voteManager.getPollByNumericId(numericId);
                if (voteData != null) {
                    List<String> optionNumbers = IntStream.rangeClosed(1, voteData.getOptions().size())
                            .mapToObj(String::valueOf)
                            .collect(Collectors.toList());
                    StringUtil.copyPartialMatches(args[1], optionNumbers, completions);
                }
            } catch (NumberFormatException e) {
                // 最初の引数が数字でなければ何もしない
            }
        }
        return completions;
    }
}