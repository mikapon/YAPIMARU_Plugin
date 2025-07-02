package com.yapimaru.plugin.commands;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HubCommand implements CommandExecutor {
    private final YAPIMARU_Plugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final long COOLDOWN_SECONDS = 10;

    public HubCommand(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("プレイヤーのみ実行できます。");
            return true;
        }

        if (cooldowns.containsKey(p.getUniqueId())) {
            long secondsLeft = ((cooldowns.get(p.getUniqueId()) / 1000) + COOLDOWN_SECONDS) - (System.currentTimeMillis() / 1000);
            if (secondsLeft > 0) {
                plugin.getAdventure().player(p).sendMessage(
                        Component.text("連投しないでください。" + secondsLeft + "秒後に再度実行してください。", NamedTextColor.RED)
                );
                return true;
            }
        }

        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("hub-location");
        if (cs == null) {
            plugin.getAdventure().player(p).sendMessage(Component.text("ハブの場所がconfig.ymlで設定されていません。", NamedTextColor.RED));
            return true;
        }
        try {
            Location hubLoc = Location.deserialize(cs.getValues(true));
            p.teleport(hubLoc);
            plugin.getAdventure().player(p).sendMessage(Component.text("ハブにテレポートしました！", NamedTextColor.GREEN));
            cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getAdventure().player(p).sendMessage(Component.text("config.ymlのハブの場所の設定が不正です。", NamedTextColor.RED));
            plugin.getLogger().warning("Failed to teleport player to hub: " + e.getMessage());
        }
        return true;
    }
}