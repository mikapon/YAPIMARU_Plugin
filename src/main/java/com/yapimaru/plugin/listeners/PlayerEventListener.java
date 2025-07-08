package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.*;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;

public class PlayerEventListener implements Listener {

    private final YAPIMARU_Plugin plugin;
    private final NameManager nameManager;
    private final GuiManager guiManager;
    private final PvpManager pvpManager;
    private final WhitelistManager whitelistManager;
    private final PlayerRestrictionManager restrictionManager;
    private final ParticipantManager participantManager;
    private final BukkitAudiences adventure;

    public PlayerEventListener(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.nameManager = plugin.getNameManager();
        this.guiManager = plugin.getCreatorGuiManager();
        this.pvpManager = plugin.getPvpManager();
        this.whitelistManager = plugin.getWhitelistManager();
        this.restrictionManager = plugin.getRestrictionManager();
        this.participantManager = plugin.getParticipantManager();
        this.adventure = plugin.getAdventure();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        String kickMessage = whitelistManager.checkLogin(event.getUniqueId());
        if (kickMessage != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, kickMessage);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        participantManager.handlePlayerLogin(player.getUniqueId(), false);
        nameManager.updatePlayerName(player);
        restrictionManager.applyModeToPlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        participantManager.incrementChats(event.getPlayer().getUniqueId());
        int w_count = participantManager.calculateWCount(event.getMessage());
        participantManager.incrementWCount(event.getPlayer().getUniqueId(), w_count);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.getMessage().toLowerCase().startsWith("/skin")) {
            return;
        }
        participantManager.incrementChats(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        participantManager.handlePlayerLogout(player.getUniqueId());
        pvpManager.handlePlayerQuit(player);
        guiManager.handlePlayerQuit(player);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (pvpManager.getGameState() != PvpManager.GameState.IDLE) {
            if (pvpManager.isGracePeriodActive() || pvpManager.isPlayerInOwnSpawnProtection(player) || pvpManager.isPlayerInOwnTeamProtectedArea(player) || pvpManager.isLocationInProtectedDedArea(player.getLocation()) || pvpManager.getInvinciblePlayers().containsKey(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        participantManager.incrementDeaths(event.getEntity().getUniqueId());
        pvpManager.handlePlayerDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        new BukkitRunnable() {
            @Override
            public void run() {
                Set<PotionEffect> effectsToApply = guiManager.getStickyEffectsForPlayer(player.getUniqueId());
                if (effectsToApply != null && !effectsToApply.isEmpty()) {
                    effectsToApply.forEach(player::addPotionEffect);
                    adventure.player(player).sendMessage(Component.text("設定されていたエフェクトを再付与しました。", NamedTextColor.AQUA));
                }
            }
        }.runTaskLater(plugin, 1L);

        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;

        if (pvpManager.getZeroLivesWaitRespawnPlayers().contains(player.getUniqueId())) {
            Location dedSpawn = pvpManager.getDedSpawn();
            if (dedSpawn != null) event.setRespawnLocation(dedSpawn);
            return;
        }

        if(pvpManager.getZeroLivesSpectatorPlayers().contains(player.getUniqueId())) {
            String teamTag = pvpManager.getPlayerTeamTag(player);
            if(teamTag != null) {
                Location teamSpawn = pvpManager.getTeamSpawn(teamTag);
                if(teamSpawn != null) event.setRespawnLocation(teamSpawn);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.setGameMode(GameMode.SPECTATOR);
                pvpManager.getZeroLivesSpectatorPlayers().remove(player.getUniqueId());
            }, 1L);
            return;
        }

        if (pvpManager.isDedFeatureEnabled()){
            Location dedSpawn = pvpManager.getDedSpawn();
            if (dedSpawn != null) {
                event.setRespawnLocation(dedSpawn);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        pvpManager.startRespawnTimer(player, pvpManager.getDedTime());
                    }
                }.runTaskLater(plugin, 1L);
            }
            return;
        }

        String teamTag = pvpManager.getPlayerTeamTag(player);
        if (teamTag == null) return;
        Location spawnLocation = pvpManager.getTeamSpawn(teamTag);
        if (spawnLocation != null) {
            event.setRespawnLocation(spawnLocation);
            pvpManager.giveInvincibilityOnGrounded(player);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (pvpManager.getGameState() != PvpManager.GameState.IDLE && pvpManager.isLocationInProtectedArea(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内ではブロックを破壊できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (pvpManager.getGameState() != PvpManager.GameState.IDLE && pvpManager.isLocationInProtectedArea(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内ではブロックを設置できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (pvpManager.getGameState() != PvpManager.GameState.IDLE && pvpManager.isLocationInProtectedArea(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内では液体を設置できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (pvpManager.getGameState() != PvpManager.GameState.IDLE && pvpManager.isLocationInProtectedArea(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内から液体を汲むことはできません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        if (pvpManager.getGameState() != PvpManager.GameState.IDLE && pvpManager.isLocationInSpawnProtection(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (pvpManager.getGameState() != PvpManager.GameState.IDLE) {
            event.blockList().removeIf(block -> pvpManager.isLocationInProtectedArea(block.getLocation()));
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode stickyGameMode = guiManager.getStickyGameMode(event.getPlayer().getUniqueId());
        if (stickyGameMode != null && event.getNewGameMode() != stickyGameMode) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player player = event.getPlayer();
                if (player.isOnline() && player.getGameMode() != stickyGameMode) {
                    player.setGameMode(stickyGameMode);
                }
            }, 1L);
        }
    }
}