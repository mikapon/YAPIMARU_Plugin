package com.yapimaru.plugin.completers;

import com.yapimaru.plugin.managers.PvpManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PvpTabCompleter implements TabCompleter {
    private final PvpManager pvpManager;
    private static final List<String> ROOT_COMMANDS = List.of("on", "off", "set", "remove", "reset", "status", "confirm", "ded", "lives", "invincible", "grace");
    private static final List<String> DED_COMMANDS = List.of("on", "off", "set", "time");
    private static final List<String> LIVES_COMMANDS = List.of("on", "off", "mode", "set", "onzero");
    private static final List<String> LIVES_MODE_ARGS = List.of("team", "player");
    private static final List<String> LIVES_ONZERO_ARGS = List.of("spectator", "wait");
    private static final List<String> ON_OFF_TIME = List.of("on", "off", "time");

    public PvpTabCompleter(PvpManager pvpManager) { this.pvpManager = pvpManager; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], ROOT_COMMANDS, new ArrayList<>());
        }
        if (args.length > 1) {
            switch (args[0].toLowerCase()) {
                case "set":
                case "remove":
                    if (args.length == 2) return StringUtil.copyPartialMatches(args[1], pvpManager.getDefaultTeamColors(), new ArrayList<>());
                    break;
                case "ded":
                    if (args.length == 2) return StringUtil.copyPartialMatches(args[1], DED_COMMANDS, new ArrayList<>());
                    break;
                case "lives":
                    return handleLivesCompletion(args);
                case "invincible":
                case "grace":
                    if (args.length == 2) return StringUtil.copyPartialMatches(args[1], ON_OFF_TIME, new ArrayList<>());
                    break;
            }
        }
        return new ArrayList<>();
    }

    private List<String> handleLivesCompletion(String[] args) {
        if (args.length == 2) return StringUtil.copyPartialMatches(args[1], LIVES_COMMANDS, new ArrayList<>());
        if (args.length == 3) {
            switch(args[1].toLowerCase()){
                case "mode": return StringUtil.copyPartialMatches(args[2], LIVES_MODE_ARGS, new ArrayList<>());
                case "set": return StringUtil.copyPartialMatches(args[2], LIVES_MODE_ARGS, new ArrayList<>());
                case "onzero": return StringUtil.copyPartialMatches(args[2], LIVES_ONZERO_ARGS, new ArrayList<>());
            }
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            if(args[2].equalsIgnoreCase("team")) return StringUtil.copyPartialMatches(args[3], pvpManager.getDefaultTeamColors(), new ArrayList<>());
            if(args[2].equalsIgnoreCase("player")) {
                List<String> playerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                return StringUtil.copyPartialMatches(args[3], playerNames, new ArrayList<>());
            }
        }
        return new ArrayList<>();
    }
}