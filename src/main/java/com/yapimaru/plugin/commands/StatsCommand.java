package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.NameManager;
import com.yapimaru.plugin.managers.ParticipantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays; // ★★★ この行を追加 ★★★
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class StatsCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final NameManager nameManager;

    public StatsCommand(YAPIMARU_Plugin plugin, ParticipantManager participantManager, NameManager nameManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
        this.nameManager = nameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("このコマンドを使用する権限がありません。", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player p) {
                ParticipantData data = participantManager.getParticipant(p.getUniqueId());
                if (data != null) {
                    displayPlayerStats(sender, data.getParticipantId());
                } else {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("あなたの参加者情報が見つかりませんでした。", NamedTextColor.RED));
                }
            } else {
                sendHelp(sender);
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "player":
                if (args.length < 2) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("参加者IDを指定してください。 /stats player <参加者ID>", NamedTextColor.RED));
                    return true;
                }
                String participantId = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                displayPlayerStats(sender, participantId);
                break;
            case "list":
                if (args.length < 2) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("統計項目を指定してください。 /stats list <項目>", NamedTextColor.RED));
                    return true;
                }
                String statName = args[1].toLowerCase();
                boolean worst = args.length > 2 && args[2].equalsIgnoreCase("worst");

                int page = 0;
                String pageArg = worst ? (args.length > 3 ? args[3] : "1") : (args.length > 2 ? args[2] : "1");
                try {
                    page = Integer.parseInt(pageArg) - 1;
                } catch (NumberFormatException ignored) {
                }
                if (page < 0) page = 0;

                displayLeaderboard(sender, statName, worst, page);
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void displayPlayerStats(CommandSender sender, String participantId) {
        ParticipantData data = participantManager.findParticipantByAnyName(participantId).orElse(null);
        if (data == null) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("参加者「" + participantId + "」は見つかりませんでした。", NamedTextColor.RED));
            return;
        }

        plugin.getAdventure().sender(sender).sendMessage(Component.text("§6--- " + data.getDisplayName() + " の統計情報 ---"));
        Map<String, Number> stats = data.getStatistics();
        plugin.getAdventure().sender(sender).sendMessage(formatStat("デス合計数", stats.get("total_deaths")));
        plugin.getAdventure().sender(sender).sendMessage(formatStat("サーバー入室合計回数", stats.get("total_joins")));
        plugin.getAdventure().sender(sender).sendMessage(formatStat("サーバー参加合計時間", formatDuration(stats.get("total_playtime_seconds").longValue())));
        plugin.getAdventure().sender(sender).sendMessage(formatStat("撮影参加合計回数", stats.get("photoshoot_participations")));
        plugin.getAdventure().sender(sender).sendMessage(formatStat("チャット合計回数", stats.get("total_chats")));
        plugin.getAdventure().sender(sender).sendMessage(formatStat("w合計数", stats.get("w_count")));
    }

    private Component formatStat(String name, Object value) {
        return Component.text("§e" + name + ": §b" + value);
    }

    private String formatDuration(long totalSeconds) {
        long days = TimeUnit.SECONDS.toDays(totalSeconds);
        long hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24;
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
        long seconds = totalSeconds % 60;
        return String.format("%d日 %02d時間 %02d分 %02d秒", days, hours, minutes, seconds);
    }

    private void displayLeaderboard(CommandSender sender, String statName, boolean worst, int page) {
        List<ParticipantData> allData = new ArrayList<>(participantManager.getActiveParticipants());

        if (allData.isEmpty() || allData.get(0).getStatistics().get(statName) == null) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("無効な統計項目です: " + statName, NamedTextColor.RED));
            return;
        }

        Comparator<ParticipantData> comparator = Comparator.comparingDouble(p -> p.getStatistics().get(statName).doubleValue());
        if (!worst) {
            comparator = comparator.reversed();
        }
        allData.sort(comparator);

        int pageSize = 10;
        int totalPages = (int) Math.ceil((double) allData.size() / pageSize);
        if (page >= totalPages && totalPages > 0) page = totalPages - 1;

        plugin.getAdventure().sender(sender).sendMessage(Component.text("§6ーーーーーーー " + getStatDisplayName(statName) + " ランキング" + (worst ? " (ワースト)" : "") + " ーーーーーーー"));

        int start = page * pageSize;
        int end = Math.min(start + pageSize, allData.size());

        for (int i = start; i < end; i++) {
            ParticipantData data = allData.get(i);
            Number value = data.getStatistics().get(statName);

            String valueStr = (statName.equals("total_playtime_seconds")) ? formatDuration(value.longValue()) : value.toString();
            plugin.getAdventure().sender(sender).sendMessage(Component.text("  §e" + (i + 1) + "位 - §b" + valueStr + " §e- §f" + data.getDisplayName()));
        }

        TextComponent.Builder footerBuilder = Component.text();
        footerBuilder.append(Component.text("§6ーーーーーーーー＜"));
        if (page > 0) {
            String cmd = "/stats list " + statName + (worst ? " worst " : " ") + page;
            footerBuilder.append(Component.text("戻る", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(cmd))
                    .hoverEvent(HoverEvent.showText(Component.text((page) + "ページ目へ"))));
        } else {
            footerBuilder.append(Component.text("戻る", NamedTextColor.GRAY));
        }
        footerBuilder.append(Component.text("ーーー (" + (page + 1) + "/" + totalPages + ") ーーー", NamedTextColor.GOLD));
        if ((page + 1) < totalPages) {
            String cmd = "/stats list " + statName + (worst ? " worst " : " ") + (page + 2);
            footerBuilder.append(Component.text("次へ", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(cmd))
                    .hoverEvent(HoverEvent.showText(Component.text((page + 2) + "ページ目へ"))));
        } else {
            footerBuilder.append(Component.text("次へ", NamedTextColor.GRAY));
        }
        footerBuilder.append(Component.text("＞ーーーーーーーー", NamedTextColor.GOLD));

        plugin.getAdventure().sender(sender).sendMessage(footerBuilder.build());

        nameManager.setGloballyViewedStat(statName);
    }

    private String getStatDisplayName(String statName) {
        return switch (statName) {
            case "total_deaths" -> "デス合計数";
            case "total_joins" -> "サーバー入室合計回数";
            case "total_playtime_seconds" -> "サーバー参加合計時間";
            case "photoshoot_participations" -> "撮影参加合計回数";
            case "total_chats" -> "チャット合計回数";
            case "w_count" -> "w合計数";
            default -> statName;
        };
    }

    private void sendHelp(CommandSender sender) {
        plugin.getAdventure().sender(sender).sendMessage(Component.text("§6--- Stats Command Help ---"));
        plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/stats §7- 自分の統計情報を表示"));
        plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/stats player <参加者ID> §7- 個人の統計情報を表示"));
        plugin.getAdventure().sender(sender).sendMessage(Component.text("§e/stats list <項目> [worst] [ページ] §7- ランキングを表示"));
    }
}