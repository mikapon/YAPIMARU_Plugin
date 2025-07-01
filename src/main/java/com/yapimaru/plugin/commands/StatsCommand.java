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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class StatsCommand implements CommandExecutor {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final NameManager nameManager; // ★★★ 追加

    // ★★★ 修正箇所 ★★★
    public StatsCommand(YAPIMARU_Plugin plugin, ParticipantManager participantManager, NameManager nameManager) {
        this.plugin = plugin;
        this.participantManager = participantManager;
        this.nameManager = nameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            sender.sendMessage("§cこのコマンドを使用する権限がありません。");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player p) {
                ParticipantData data = participantManager.findOrCreateParticipant(p);
                displayPlayerStats(sender, data.getParticipantId());
            } else {
                sendHelp(sender);
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "player" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c参加者名を指定してください。 /stats player <参加者名>");
                    return true;
                }
                String participantId = args[1];
                displayPlayerStats(sender, participantId);
            }
            case "list" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c統計項目を指定してください。 /stats list <項目>");
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
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void displayPlayerStats(CommandSender sender, String participantId) {
        ParticipantData data = participantManager.getParticipant(participantId);
        if (data == null) {
            sender.sendMessage("§c参加者「" + participantId + "」は見つかりませんでした。");
            return;
        }

        sender.sendMessage("§6--- " + data.getDisplayName() + " の統計情報 ---");
        Map<String, Number> stats = data.getStatistics();
        sender.sendMessage(formatStat("デス合計数", stats.get("total_deaths")));
        sender.sendMessage(formatStat("サーバー入室合計回数", stats.get("total_joins")));
        sender.sendMessage(formatStat("サーバー参加合計時間", formatDuration(stats.get("total_playtime_seconds").longValue())));
        sender.sendMessage(formatStat("撮影参加合計回数", stats.get("photoshoot_participations")));
        sender.sendMessage(formatStat("チャット合計回数", stats.get("total_chats")));
        sender.sendMessage(formatStat("w合計数", stats.get("w_count")));
    }

    private String formatStat(String name, Object value) {
        return "§e" + name + ": §b" + value;
    }

    private String formatDuration(long totalSeconds) {
        long days = TimeUnit.SECONDS.toDays(totalSeconds);
        long hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24;
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
        long seconds = totalSeconds % 60;
        return String.format("%d日 %02d時間 %02d分 %02d秒", days, hours, minutes, seconds);
    }

    private void displayLeaderboard(CommandSender sender, String statName, boolean worst, int page) {
        List<ParticipantData> allData = new ArrayList<>(participantManager.getAllParticipants());

        if (allData.isEmpty() || allData.get(0).getStatistics().get(statName) == null) {
            sender.sendMessage("§c無効な統計項目です: " + statName);
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

        int currentRank = 0;
        Number lastValue = null;
        int rankOffset = 0;

        if (start > 0) {
            for (int i = 0; i < start; i++) {
                Number currentValue = allData.get(i).getStatistics().get(statName);
                if (lastValue == null || currentValue.doubleValue() != lastValue.doubleValue()) {
                    currentRank += (1 + rankOffset);
                    rankOffset = 0;
                } else {
                    rankOffset++;
                }
                lastValue = currentValue;
            }
        }

        for (int i = start; i < end; i++) {
            ParticipantData data = allData.get(i);
            Number value = data.getStatistics().get(statName);

            if (lastValue == null || value.doubleValue() != lastValue.doubleValue()) {
                currentRank += (1 + rankOffset);
                rankOffset = 0;
            } else {
                rankOffset++;
            }

            String valueStr = (statName.equals("total_playtime_seconds")) ? formatDuration(value.longValue()) : value.toString() + "回";
            plugin.getAdventure().sender(sender).sendMessage(Component.text("  §e" + currentRank + "位 - §b" + valueStr + " §e- §f" + data.getDisplayName()));
            lastValue = value;
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

        // ★★★ 新規追加 ★★★
        // コマンド実行者のTABリスト表示を更新する
        if (sender instanceof Player p) {
            nameManager.setPlayerViewingStat(p, statName);
        }
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
        sender.sendMessage("§6--- Stats Command Help ---");
        sender.sendMessage("§e/stats player <参加者名> §7- 個人の統計情報を表示");
        sender.sendMessage("§e/stats list <項目> [worst] §7- ランキングを表示");
    }
}