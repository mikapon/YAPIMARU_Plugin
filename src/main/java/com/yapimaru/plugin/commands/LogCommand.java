package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.logic.LogAddExecutor;
import com.yapimaru.plugin.logic.LogRestoreExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class LogCommand implements CommandExecutor, TabCompleter {

    private final YAPIMARU_Plugin plugin;

    public LogCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§c使用法: /log <add|restore> [オプション]");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "add" -> handleAddCommand(sender, args);
            case "restore" -> handleRestoreCommand(sender);
            default -> sender.sendMessage("§c不明なサブコマンドです。/log <add|restore> を使用してください。");
        }
        return true;
    }

    private void handleAddCommand(CommandSender sender, String[] args) {
        boolean isDryRun = false;
        String fromDate = null;
        String toDate = null;
        String reason = null;

        List<String> argList = new ArrayList<>(Arrays.asList(args));
        argList.remove(0);

        for (int i = 0; i < argList.size(); i++) {
            String arg = argList.get(i);
            if (arg.equalsIgnoreCase("--dry-run")) {
                isDryRun = true;
            } else if (arg.equalsIgnoreCase("--from") && i + 1 < argList.size()) {
                fromDate = argList.get(++i);
            } else if (arg.equalsIgnoreCase("--to") && i + 1 < argList.size()) {
                toDate = argList.get(++i);
            } else if (arg.equalsIgnoreCase("--reason") && i + 1 < argList.size()) {
                reason = argList.subList(i + 1, argList.size()).stream()
                        .collect(Collectors.joining(" "))
                        .replace("\"", "");
                break;
            }
        }

        if (fromDate != null) {
            try {
                LocalDate.parse(fromDate);
            } catch (DateTimeParseException e) {
                sender.sendMessage("§c--from の日付形式が無効です。YYYY-MM-DD形式で指定してください。");
                return;
            }
        }
        if (toDate != null) {
            try {
                LocalDate.parse(toDate);
            } catch (DateTimeParseException e) {
                sender.sendMessage("§c--to の日付形式が無効です。YYYY-MM-DD形式で指定してください。");
                return;
            }
        }

        new LogAddExecutor(plugin, sender, isDryRun, fromDate, toDate, reason).execute();
    }

    private void handleRestoreCommand(CommandSender sender) {
        new LogRestoreExecutor(plugin, sender).execute();
    }


    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        List<String> candidates = new ArrayList<>();

        if (args.length == 1) {
            candidates.add("add");
            candidates.add("restore");
        } else if (args.length > 1 && args[0].equalsIgnoreCase("add")) {
            List<String> usedArgs = new ArrayList<>(Arrays.asList(args));

            if (!usedArgs.contains("--dry-run")) candidates.add("--dry-run");
            if (!usedArgs.contains("--from")) candidates.add("--from");
            if (!usedArgs.contains("--to")) candidates.add("--to");
            if (!usedArgs.contains("--reason")) candidates.add("--reason");

            String lastArg = args[args.length - 2];
            if(lastArg.equalsIgnoreCase("--from") || lastArg.equalsIgnoreCase("--to")) {
                candidates.clear();
                candidates.add("YYYY-MM-DD");
            }
            if(lastArg.equalsIgnoreCase("--reason")) {
                candidates.clear();
                candidates.add("\"実行理由\"");
            }
        }

        String currentArg = args[args.length - 1];
        for (String s : candidates) {
            if (s.toLowerCase().startsWith(currentArg.toLowerCase())) {
                completions.add(s);
            }
        }

        return completions;
    }
}