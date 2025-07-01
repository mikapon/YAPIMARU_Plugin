package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.managers.*;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerEventListener implements Listener {

    private final YAPIMARU_Plugin plugin;
    private final NameManager nameManager;
    private final GuiManager guiManager;
    private final PvpManager pvpManager;
    private final WhitelistManager whitelistManager;
    private final PlayerRestrictionManager restrictionManager;
    private final ParticipantManager participantManager;
    private final BukkitAudiences adventure;
    private final Map<UUID, Long> joinInvinciblePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> joinInvincibilityTasks = new ConcurrentHashMap<>();

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
    public void onPlayerLogin(AsyncPlayerPreLoginEvent event) {
        String kickMessage = whitelistManager.checkLogin(event.getUniqueId());
        if (kickMessage != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, kickMessage);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> nameManager.updatePlayerName(player), 5L);
        restrictionManager.applyModeToPlayer(player);

        participantManager.incrementJoins(player.getUniqueId());
        participantManager.recordLoginTime(player);

        joinInvinciblePlayers.put(player.getUniqueId(), System.currentTimeMillis() + 60000);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    return;
                }

                boolean isGrounded = !player.isFlying() && player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType().isSolid();
                boolean isInLiquid = player.getLocation().getBlock().isLiquid();

                if (isGrounded || isInLiquid) {
                    adventure.player(player).sendMessage(Component.text("サーバーへようこそ！3秒間の無敵時間があります。", NamedTextColor.GOLD));
                    joinInvinciblePlayers.put(player.getUniqueId(), System.currentTimeMillis() + 3000);
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            joinInvinciblePlayers.remove(player.getUniqueId());
                            if(player.isOnline()) adventure.player(player).sendMessage(Component.text("無敵時間が終了しました。", NamedTextColor.GRAY));
                        }
                    }.runTaskLater(plugin, 3 * 20L);
                    this.cancel();
                }
            }
        };
        task.runTaskTimer(plugin, 0L, 1L);
        joinInvincibilityTasks.put(player.getUniqueId(), task);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        participantManager.recordQuitTime(player);

        joinInvinciblePlayers.remove(player.getUniqueId());
        if(joinInvincibilityTasks.containsKey(player.getUniqueId())) {
            joinInvincibilityTasks.get(player.getUniqueId()).cancel();
            joinInvincibilityTasks.remove(player.getUniqueId());
        }
        pvpManager.handlePlayerQuit(player);
        guiManager.handlePlayerQuit(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (message.startsWith("/")) {
            return;
        }

        participantManager.incrementChats(player.getUniqueId());

        // ★★★ 修正箇所 ★★★
        // カウントする前に、正規表現で()とその中身を全て削除する
        String countableMessage = message.replaceAll("\\(.*?\\)", "");

        int wCount = StringUtils.countMatches(countableMessage.toLowerCase(), "w");
        wCount += StringUtils.countMatches(countableMessage, "草");
        wCount += StringUtils.countMatches(countableMessage.toLowerCase(), "kusa");

        if (wCount > 0) {
            participantManager.incrementWCount(player.getUniqueId(), wCount);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        // コマンドはチャット回数にのみカウントする
        participantManager.incrementChats(event.getPlayer().getUniqueId());
    }


    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (pvpManager.getGameState() != PvpManager.GameState.IDLE) {
            if (pvpManager.isGracePeriodActive()) {
                event.setCancelled(true);
                return;
            }
            if (pvpManager.isPlayerInOwnSpawnProtection(player)) {
                event.setCancelled(true);
                return;
            }
            if (pvpManager.isPlayerInOwnTeamProtectedArea(player)) {
                event.setCancelled(true);
                return;
            }
            if (pvpManager.isLocationInProtectedDedArea(player.getLocation())) {
                event.setCancelled(true);
                return;
            }
            if (pvpManager.getInvinciblePlayers().containsKey(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }

        if (joinInvinciblePlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
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
                    for (PotionEffect effect : effectsToApply) {
                        player.addPotionEffect(effect);
                    }
                    adventure.player(player).sendMessage(Component.text("設定されていたエフェクトを再付与しました。", NamedTextColor.AQUA));
                }
            }
        }.runTaskLater(plugin, 1L);

        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;

        if (pvpManager.getZeroLivesWaitRespawnPlayers().contains(player.getUniqueId())) {
            Location dedSpawn = pvpManager.getDedSpawn();
            if (dedSpawn != null) {
                event.setRespawnLocation(dedSpawn);
            }
            return;
        }

        if(pvpManager.getZeroLivesSpectatorPlayers().contains(player.getUniqueId())) {
            String teamTag = pvpManager.getPlayerTeamTag(player);
            if(teamTag != null) {
                Location teamSpawn = pvpManager.getTeamSpawn(teamTag);
                if(teamSpawn != null) {
                    event.setRespawnLocation(teamSpawn);
                }
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
        // ブロック破壊数の統計を削除したため、関連処理も削除
        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内ではブロックを破壊できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内ではブロックを設置できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内では液体を設置できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内から液体を汲むことはできません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;
        if (pvpManager.isLocationInSpawnProtection(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (pvpManager.getGameState() == PvpManager.GameState.IDLE) return;
        event.blockList().removeIf(block -> pvpManager.isLocationInSpawnProtection(block.getLocation()));
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode stickyGameMode = guiManager.getStickyGameMode(player.getUniqueId());
        if (stickyGameMode != null && event.getNewGameMode() != stickyGameMode) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getGameMode() != stickyGameMode) {
                    player.setGameMode(stickyGameMode);
                }
            }, 1L);
        }
    }
}