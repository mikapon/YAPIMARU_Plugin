package com.yapimaru.plugin.commands;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.NameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NameCommand implements CommandExecutor {
    private final YAPIMARU_Plugin plugin;
    private final NameManager nameManager;

    public NameCommand(YAPIMARU_Plugin plugin, NameManager nameManager) {
        this.plugin = plugin;
        this.nameManager = nameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("yapimaru.admin")) {
            plugin.getAdventure().sender(sender).sendMessage(Component.text("このコマンドを使用する権限がありません。", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "set", "link" -> {
                if (!(sender instanceof Player p)) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("このコマンドはプレイヤーのみが実行できます。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    plugin.getAdventure().player(p).sendMessage(Component.text("使い方: /name " + sub + " <名前>", NamedTextColor.RED));
                    return true;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                if (sub.equals("set")) {
                    nameManager.setBaseName(p.getUniqueId(), text);
                    plugin.getAdventure().player(p).sendMessage(Component.text("基本の名前を「" + text + "」に設定しました。", NamedTextColor.GREEN));
                } else { // link
                    nameManager.setLinkedName(p.getUniqueId(), text);
                    plugin.getAdventure().player(p).sendMessage(Component.text("頭上の名前を「" + text + "」に設定しました。", NamedTextColor.GREEN));
                }
                return true;
            }
            case "color" -> {
                if (args.length < 3) {
                    sendColorSubHelp(sender);
                    return true;
                }
                String colorName = args[1];
                if (!colorName.equalsIgnoreCase("reset") && !NameManager.WOOL_COLOR_NAMES.contains(colorName.toLowerCase())) {
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("エラー: '" + colorName + "' は無効な色名です。羊毛のある色を指定してください。", NamedTextColor.RED));
                    return true;
                }

                Set<String> success = new HashSet<>();
                List<String> failed = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    String target = args[i];
                    if (target.equalsIgnoreCase("//sel") && sender instanceof Player p && plugin.getWorldEditHook() != null) {
                        try {
                            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(p));
                            Region sel = session.getSelection(session.getSelectionWorld());
                            Bukkit.getOnlinePlayers().stream().filter(pl -> sel.contains(BukkitAdapter.asBlockVector(pl.getLocation()))).forEach(pl -> {
                                if (nameManager.setPlayerColor(pl.getName(), colorName)) success.add(pl.getName());
                            });
                        } catch (IncompleteRegionException e) {
                            failed.add("//sel (範囲未選択)");
                        }
                    } else if (target.startsWith("@")) {
                        try {
                            List<Entity> entities = Bukkit.selectEntities(sender, target);
                            if (entities.isEmpty()) failed.add(target + " (対象なし)");
                            entities.stream().filter(e -> e instanceof Player).forEach(e -> {
                                if (nameManager.setPlayerColor(e.getName(), colorName)) success.add(e.getName());
                            });
                        } catch (IllegalArgumentException e) {
                            failed.add(target + " (無効なセレクタ)");
                        }
                    } else {
                        if (nameManager.setPlayerColor(target, colorName)) success.add(target);
                        else failed.add(target);
                    }
                }
                if (!success.isEmpty())
                    plugin.getAdventure().sender(sender).sendMessage(Component.text(String.join(", ", success) + "の色を" + colorName + "に変更しました。", NamedTextColor.GREEN));
                if (!failed.isEmpty())
                    plugin.getAdventure().sender(sender).sendMessage(Component.text("失敗: " + String.join(", ", failed), NamedTextColor.RED));
                return true;
            }
            case "reload" -> {
                nameManager.reloadData();
                plugin.getAdventure().sender(sender).sendMessage(Component.text("参加者情報をリロードしました。", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender s) {
        plugin.getAdventure().sender(s).sendMessage(Component.text("§6--- Name Command Help ---"));
        plugin.getAdventure().sender(s).sendMessage(Component.text("§e/name set <name> §7- チャット等での表示名を設定"));
        plugin.getAdventure().sender(s).sendMessage(Component.text("§e/name link <name|remove> §7- 頭上の表示名を設定（removeで削除）"));
        plugin.getAdventure().sender(s).sendMessage(Component.text("§e/name color <color> <target> §7- 名前の色を変更"));
        plugin.getAdventure().sender(s).sendMessage(Component.text("§e/name reload §7- 設定ファイルをリロード"));
    }

    private void sendColorSubHelp(CommandSender s) {
        plugin.getAdventure().sender(s).sendMessage(Component.text("引数が不足しています。", NamedTextColor.RED));
        plugin.getAdventure().sender(s).sendMessage(Component.text("§e使い方: /name color <色> <プレイヤー名|@a|//sel...>"));
        plugin.getAdventure().sender(s).sendMessage(Component.text("§7利用可能な色: " + String.join(", ", NameManager.WOOL_COLOR_NAMES)));
    }
}
