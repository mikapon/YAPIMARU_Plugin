package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class WhitelistManager {

    private final YAPIMARU_Plugin plugin;
    private final BukkitAudiences adventure;
    private final NameManager nameManager;
    private Mode currentMode = Mode.OFF;

    private final Set<UUID> candidatePlayers = new HashSet<>();
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
        this.adventure = plugin.getAdventure();
        this.nameManager = plugin.getNameManager();
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

        candidatePlayers.clear();
        config.getStringList("whitelist.candidates").forEach(uuidString -> candidatePlayers.add(UUID.fromString(uuidString)));
        allowedPlayers.clear();
        config.getStringList("whitelist.allowed").forEach(uuidString -> allowedPlayers.add(UUID.fromString(uuidString)));
    }

    public void save() {
        FileConfiguration config = plugin.getConfig();
        config.set("whitelist.mode", currentMode.name());
        config.set("whitelist.candidates", candidatePlayers.stream().map(UUID::toString).collect(Collectors.toList()));
        config.set("whitelist.allowed", allowedPlayers.stream().map(UUID::toString).collect(Collectors.toList()));
        plugin.saveConfig();
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

    public void addCandidate(OfflinePlayer player) {
        if (player != null && !isAllowed(player.getUniqueId()) && !isCandidate(player.getUniqueId())) {
            candidatePlayers.add(player.getUniqueId());
            nameManager.cacheFrozenData(player);
            save();
        }
    }

    public void promoteCandidateToAllowed(UUID uuid) {
        if (candidatePlayers.remove(uuid)) {
            allowedPlayers.add(uuid);
            save();
        }
    }

    public void demoteCandidateToSource(UUID uuid) {
        if (candidatePlayers.remove(uuid)) {
            nameManager.removeFrozenData(uuid);
            save();
        }
    }

    public void demoteAllowedToCandidate(UUID uuid) {
        if (allowedPlayers.remove(uuid)) {
            candidatePlayers.add(uuid);
            save();
        }
    }

    public boolean isCandidate(UUID uuid) { return candidatePlayers.contains(uuid); }
    public boolean isAllowed(UUID uuid) { return allowedPlayers.contains(uuid); }
    public Set<UUID> getCandidatePlayers() { return Collections.unmodifiableSet(candidatePlayers); }
    public Set<UUID> getAllowedPlayers() { return Collections.unmodifiableSet(allowedPlayers); }

    public void startLockdown() {
        lockdownPlayers.clear();
        lockdownPlayers.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet()));
        plugin.getLogger().info("Lockdown mode enabled. " + lockdownPlayers.size() + " players are whitelisted.");
    }

    public String checkLogin(UUID playerUUID) {
        final String maintenanceMsg = ChatColor.RED + "現在メンテナンス中のため参加できません\n" + ChatColor.AQUA + "Discordにて最新情報をお待ち下さい。\n" + ChatColor.GRAY + "全体連絡Ch\n" + "https://x.gd/9vv5l";
        final String lockdownMsg = ChatColor.RED + "現在撮影中のため途中参加できません\n" + ChatColor.AQUA + "Discord通話にて最新情報をお待ち下さい。\n" + ChatColor.GRAY + "動画撮影Vc\n" + "https://x.gd/jqPd9";

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
