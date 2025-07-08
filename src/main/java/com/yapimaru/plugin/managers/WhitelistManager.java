package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class WhitelistManager {

    private final YAPIMARU_Plugin plugin;
    private final ParticipantManager participantManager;
    private final BukkitAudiences adventure;

    private Mode currentMode = Mode.OFF;
    private final Set<UUID> allowedPlayers = new HashSet<>();
    private final Set<UUID> lockdownPlayers = new HashSet<>();
    private final List<UUID> ownerUUIDs = new ArrayList<>();

    public enum Mode {
        OFF,
        OWNER_ONLY,
        WHITELIST_ONLY,
        LOCKDOWN
    }

    public WhitelistManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.participantManager = plugin.getParticipantManager();
        this.adventure = plugin.getAdventure();
        load();
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        String modeString = config.getString("whitelist.mode", "OFF").toUpperCase();
        try {
            this.currentMode = Mode.valueOf(modeString);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid whitelist mode '" + modeString + "' in config.yml. Defaulting to OFF.");
            this.currentMode = Mode.OFF;
        }

        ownerUUIDs.clear();
        config.getStringList("whitelist.owners").forEach(uuidString -> {
            try {
                ownerUUIDs.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid owner UUID in config.yml: " + uuidString);
            }
        });

        syncAllowedPlayers();
    }

    public void save() {
        FileConfiguration config = plugin.getConfig();
        config.set("whitelist.mode", currentMode.name());
        plugin.saveConfig();
    }

    public void syncAllowedPlayers() {
        if(participantManager == null) return;
        allowedPlayers.clear();
        allowedPlayers.addAll(participantManager.getAllAssociatedUuidsFromActive());
    }

    public void setMode(Mode mode, CommandSender sender) {
        this.currentMode = mode;
        save();
        if (mode == Mode.LOCKDOWN) {
            startLockdown();
        }
        adventure.sender(sender).sendMessage(Component.text("ホワイトリストモードを「" + mode.name() + "」に設定しました。", NamedTextColor.GREEN));
    }

    public Mode getMode() {
        return currentMode;
    }

    public void startLockdown() {
        lockdownPlayers.clear();
        lockdownPlayers.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet()));
        plugin.getLogger().info("Lockdown mode enabled. " + lockdownPlayers.size() + " players are whitelisted.");
    }

    public String checkLogin(UUID playerUUID) {
        final String maintenanceMsg = "§c現在メンテナンス中のため参加できません\n§bDiscordにて最新情報をお待ち下さい。\n§7全体連絡Ch\n§7https://x.gd/9vv5l";
        final String lockdownMsg = "§c現在撮影中のため途中参加できません\n§bDiscord通話にて最新情報をお待ち下さい。\n§7動画撮影Vc\n§7https://x.gd/jqPd9";

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
        if (player.isOp() || ownerUUIDs.contains(playerUUID)) {
            return null;
        }

        return switch (currentMode) {
            case OFF -> null;
            case OWNER_ONLY -> maintenanceMsg;
            case WHITELIST_ONLY -> allowedPlayers.contains(playerUUID) ? null : maintenanceMsg;
            case LOCKDOWN -> lockdownPlayers.contains(playerUUID) ? null : lockdownMsg;
        };
    }
}