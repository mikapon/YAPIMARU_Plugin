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
        this.adventure = plugin.getAdventure();
    }

    // --- ログイン・接続イベント (変更なし) ---
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> nameManager.updatePlayerName(player, false), 5L);
        restrictionManager.applyModeToPlayer(player);

        joinInvinciblePlayers.put(player.getUniqueId(), System.currentTimeMillis() + 60000);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    return;
                }
                boolean isOnGround = player.isOnGround();
                boolean isInLiquid = player.getLocation().getBlock().isLiquid();

                if (isOnGround || isInLiquid) {
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
        joinInvinciblePlayers.remove(player.getUniqueId());
        if(joinInvincibilityTasks.containsKey(player.getUniqueId())) {
            joinInvincibilityTasks.get(player.getUniqueId()).cancel();
            joinInvincibilityTasks.remove(player.getUniqueId());
        }
        pvpManager.handlePlayerQuit(player);
        guiManager.handlePlayerQuit(player);
    }


    // ★★★ 変更点 ★★★
    // ダメージイベントの保護ロジックを全面的に更新
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // PvPゲーム中か判定
        if (pvpManager.isGameRunning()) {
            // 準備時間中は全員ダメージ無効
            if (pvpManager.isGracePeriodActive()) {
                event.setCancelled(true);
                return;
            }
            // 3x3x5のスポーン保護エリア内は全員ダメージ無効
            if (pvpManager.isLocationInSpawnProtection(player.getLocation())) {
                event.setCancelled(true);
                return;
            }
            // 自分のチームの広い保護エリア内ではダメージ無効
            if (pvpManager.isPlayerInOwnTeamProtectedArea(player)) {
                event.setCancelled(true);
                return;
            }
            // デス待機エリア内ではダメージ無効
            if (pvpManager.isLocationInProtectedDedArea(player.getLocation())) {
                event.setCancelled(true);
                return;
            }
            // PvP中のリスポーン無敵
            if (pvpManager.getInvinciblePlayers().containsKey(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }

        // PvP外のログイン時無敵
        if (joinInvinciblePlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
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


    // ★★★ 変更点 ★★★
    // ブロック破壊・設置の保護ロジックを更新
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!pvpManager.isGameRunning()) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内ではブロックを破壊できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!pvpManager.isGameRunning()) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内ではブロックを設置できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!pvpManager.isGameRunning()) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内では液体を設置できません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!pvpManager.isGameRunning()) return;
        if (pvpManager.isLocationInProtectedArea(event.getBlock().getLocation()) || pvpManager.isLocationInSpawnProtection(event.getBlock().getLocation())) {
            adventure.player(event.getPlayer()).sendMessage(Component.text("保護エリア内から液体を汲むことはできません。", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    // ★★★ 追加 ★★★
    // 液体が保護エリアに流れないようにする
    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!pvpManager.isGameRunning()) return;
        if (pvpManager.isLocationInSpawnProtection(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ★★★ 追加 ★★★
    // 爆発が保護エリアに影響しないようにする
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!pvpManager.isGameRunning()) return;
        // 爆発で破壊されるブロックリストから、保護エリア内のものを削除
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