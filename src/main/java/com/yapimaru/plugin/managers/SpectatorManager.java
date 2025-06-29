package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Team;

public class SpectatorManager implements Listener {
    private final YAPIMARU_Plugin plugin;
    private boolean enabled = false;
    private final BukkitAudiences adventure;
    private final NameManager nameManager;
    private final PvpManager pvpManager;

    public SpectatorManager(YAPIMARU_Plugin plugin, PvpManager pvpManager) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.nameManager = plugin.getNameManager();
        this.pvpManager = pvpManager;
    }

    public void setEnabled(boolean enabled, CommandSender sender) {
        if (this.enabled == enabled && sender != null) {
            adventure.sender(sender).sendMessage(Component.text("スペクテイター非表示機能は既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.enabled = enabled;

        for (Player player : Bukkit.getOnlinePlayers()) {
            handlePlayerState(player);
        }

        if(sender != null) {
            adventure.sender(sender).sendMessage(Component.text("スペクテイター非表示機能を" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> handlePlayerState(event.getPlayer()), 1L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        handlePlayerState(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 退出時には特に処理は不要
    }

    private void handlePlayerState(Player player) {
        if (player == null || !player.isOnline()) return;

        Team team = nameManager.getPlayerTeam(player.getUniqueId());

        if (enabled && player.getGameMode() == GameMode.SPECTATOR) {
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        } else {
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        }

        if (pvpManager.getGameState() != PvpManager.GameState.IDLE) {
            pvpManager.updatePlayerScoreboard(player);
        }
    }
}