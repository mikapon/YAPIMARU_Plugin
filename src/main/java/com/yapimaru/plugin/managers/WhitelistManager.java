package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
    private final ParticipantManager participantManager;
    private Mode currentMode = Mode.OFF;

    private final Set<UUID> allowedPlayers = new HashSet<>();
    private final Set<UUID> candidatePlayers = new HashSet<>();
    private final Set<UUID> lockdownPlayers = new HashSet<>();
    private final List<UUID> ownerUUIDs = new ArrayList<>();

    public enum Mode {
        OFF,
        OWNER_ONLY,
        WHITELIST_ONLY,
        LOCKDOWN
    }

    public WhitelistManager(YAPIMARU_Plugin plugin, ParticipantManager participantManager) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.participantManager = participantManager;
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

        // candidatePlayersはもう使用しないため、読み込みを削除
        allowedPlayers.clear();
        config.getStringList("whitelist.allowed").forEach(uuidString -> allowedPlayers.add(UUID.fromString(uuidString)));

        syncWhitelistWithParticipants();
    }

    public void save() {
        FileConfiguration config = plugin.getConfig();
        config.set("whitelist.mode", currentMode.name());
        config.set("whitelist.allowed", allowedPlayers.stream().map(UUID::toString).collect(Collectors.toList()));
        // candidatePlayersはもう使用しないため、保存処理を削除
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

    public void addAllowed(UUID uuid) {
        if (!allowedPlayers.contains(uuid)) {
            allowedPlayers.add(uuid);
            save();
        }
    }

    public void removeAllowed(UUID uuid) {
        if (allowedPlayers.remove(uuid)) {
            save();
        }
    }

    public boolean isOwner(UUID uuid) { return ownerUUIDs.contains(uuid); }

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

    public void syncWhitelistWithParticipants() {
        Set<UUID> activeUuids = participantManager.getAllAssociatedUuidsFromActive();
        Set<UUID> dischargedUuids = participantManager.getAssociatedUuidsFromDischarged();

        boolean changed = false;

        for (UUID uuid : activeUuids) {
            if (!allowedPlayers.contains(uuid)) {
                allowedPlayers.add(uuid);
                changed = true;
            }
        }

        if (allowedPlayers.removeAll(dischargedUuids)) {
            changed = true;
        }

        if (changed) {
            plugin.getLogger().info("Synced whitelist with participant/discharge directories.");
            save();
        }
    }

    public void checkForUnregisteredPlayers(Player owner) {
        Set<UUID> allWhitelistedUuids = new HashSet<>(allowedPlayers);

        Set<UUID> allParticipantUuids = participantManager.getAllAssociatedUuidsFromActive();

        List<UUID> unregisteredUuids = allWhitelistedUuids.stream()
                .filter(uuid -> !allParticipantUuids.contains(uuid) && !isOwner(uuid))
                .toList();

        if (unregisteredUuids.isEmpty()) return;

        adventure.player(owner).sendMessage(Component.text("--- [未登録ホワイトリスト通知] ---", NamedTextColor.YELLOW));
        for (UUID uuid : unregisteredUuids) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);

            TextComponent message = Component.text()
                    .append(Component.text(uuid.toString(), NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text(p.getName() != null ? p.getName() : "不明なプレイヤー", NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("のプレイヤーが登録されていません。", NamedTextColor.RED))
                    .append(Component.newline())
                    .append(Component.text("参加者として登録するか、リストから削除してください。", NamedTextColor.RED))
                    .append(Component.newline())
                    .append(Component.text("(クリックでホワイトリストから削除)", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.runCommand("/ym remove-unregistered-wl " + uuid))
                            .hoverEvent(HoverEvent.showText(Component.text(p.getName() + " をホワイトリストから削除します"))))
                    .build();

            adventure.player(owner).sendMessage(message);
        }
        adventure.player(owner).sendMessage(Component.text("--------------------", NamedTextColor.YELLOW));
    }
}