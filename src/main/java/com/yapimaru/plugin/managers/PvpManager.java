package com.yapimaru.plugin.managers;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ArenaData;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class PvpManager {

    private final YAPIMARU_Plugin plugin;
    private final BukkitAudiences adventure;
    private final NameManager nameManager;

    public enum GameState { IDLE, PRE_GAME, RUNNING }
    public enum LivesMode { TEAM, PLAYER }
    public enum OnZeroAction { SPECTATOR, WAIT }

    private boolean featureEnabled = false;
    private GameState gameState = GameState.IDLE;
    private final Map<String, ArenaData> teamDataMap = new LinkedHashMap<>();

    private final Map<UUID, Long> invinciblePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> playerStateTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> respawnTimers = new ConcurrentHashMap<>();
    private final Set<UUID> zeroLivesWaitRespawn = new HashSet<>();
    private final Set<UUID> zeroLivesSpectator = new HashSet<>();

    private boolean dedFeatureEnabled = false;
    private ArenaData dedArenaData = new ArenaData();
    private int dedTime = 3;

    private boolean livesFeatureEnabled = false;
    private LivesMode livesMode = LivesMode.TEAM;
    private final Map<String, Integer> teamLives = new HashMap<>();
    private final Map<UUID, Integer> playerLives = new HashMap<>();
    private OnZeroAction onZeroAction = OnZeroAction.SPECTATOR;

    private boolean respawnInvincibleEnabled = true;
    private int respawnInvincibleTime = 3;

    private boolean gracePeriodEnabled = true;
    private int gracePeriodTime = 3;
    private boolean isGracePeriodActive = false;

    private BukkitTask gracePeriodTask;
    private BukkitTask hostileAreaDamageTask;
    private int gracePeriodCountdown;

    private static final List<String> DEFAULT_TEAM_COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    public PvpManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.nameManager = plugin.getNameManager();
        for (String colorName : DEFAULT_TEAM_COLORS) {
            teamDataMap.put(colorName, new ArenaData());
        }
    }

    public BukkitAudiences getAdventure() {
        return this.adventure;
    }

    // --- Getters ---
    public GameState getGameState() { return gameState; }
    public Map<UUID, Long> getInvinciblePlayers() { return invinciblePlayers; }
    public Set<UUID> getZeroLivesWaitRespawnPlayers() { return zeroLivesWaitRespawn; }
    public Set<UUID> getZeroLivesSpectatorPlayers() { return zeroLivesSpectator; }
    public Location getDedSpawn() { return dedArenaData.getSpawnLocation(); }
    public int getDedTime() { return dedTime; }
    public boolean isFeatureEnabled() { return featureEnabled; }
    public boolean isGracePeriodActive() { return isGracePeriodActive; }
    public List<String> getDefaultTeamColors() { return DEFAULT_TEAM_COLORS; }
    public boolean isDedFeatureEnabled() { return dedFeatureEnabled; }
    public boolean isLivesFeatureEnabled() { return livesFeatureEnabled; }
    public boolean isRespawnInvincibleEnabled() { return respawnInvincibleEnabled; }
    public boolean isGracePeriodEnabled() { return gracePeriodEnabled; }
    public int getRespawnInvincibleTime() { return respawnInvincibleTime; }
    public int getGracePeriodTime() { return gracePeriodTime; }

    private BoundingBox getSpawnProtectionBox(Location spawn) {
        if (spawn == null) return null;
        Location l = spawn.getBlock().getLocation();
        // 3x3 (x, z) and 5 high (y)
        return new BoundingBox(l.getX() - 1, l.getY(), l.getZ() - 1, l.getX() + 2, l.getY() + 5, l.getZ() + 2);
    }

    public boolean isLocationInSpawnProtection(Location loc) {
        if (gameState == GameState.IDLE) return false;
        for (ArenaData data : teamDataMap.values()) {
            BoundingBox box = getSpawnProtectionBox(data.getSpawnLocation());
            if (box != null && box.contains(loc.toVector())) {
                return true;
            }
        }
        return false;
    }

    public boolean isPlayerInEnemySpawnProtection(Player player) {
        if (gameState != GameState.RUNNING) return false;
        String playerTeam = getPlayerTeamTag(player);

        for (Map.Entry<String, ArenaData> entry : teamDataMap.entrySet()) {
            String teamColor = entry.getKey();
            ArenaData data = entry.getValue();

            if (teamColor.equals(playerTeam)) continue;

            BoundingBox box = getSpawnProtectionBox(data.getSpawnLocation());
            if (box != null && box.contains(player.getLocation().toVector())) {
                return true;
            }
        }
        return false;
    }


    // --- Setters with feedback ---
    public void setFeatureEnabled(boolean enabled, CommandSender sender) {
        if (this.featureEnabled == enabled) {
            adventure.sender(sender).sendMessage(Component.text("PvPモードは既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.featureEnabled = enabled;
        if (!enabled) {
            if(gameState != GameState.IDLE) stopGame(sender);
            resetSettings(sender);
        }
        adventure.sender(sender).sendMessage(Component.text("PvPモードを" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));
    }

    public void setDedFeatureEnabled(boolean enabled, CommandSender sender) {
        if(this.dedFeatureEnabled == enabled) {
            adventure.sender(sender).sendMessage(Component.text("デススポーン機能は既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.dedFeatureEnabled = enabled;
        adventure.sender(sender).sendMessage(Component.text("デススポーン機能を" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));
    }

    public void setLivesFeatureEnabled(boolean enabled, CommandSender sender) {
        if(this.livesFeatureEnabled == enabled) {
            adventure.sender(sender).sendMessage(Component.text("残機システムは既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.livesFeatureEnabled = enabled;
        if (enabled && !isDedFeatureEnabled()) {
            setDedFeatureEnabled(true, sender);
            adventure.sender(sender).sendMessage(Component.text("残機システム有効化のため、デススポーン機能が自動で有効になりました。", NamedTextColor.AQUA));
        }
        adventure.sender(sender).sendMessage(Component.text("残機システムを" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));

        if (enabled) {
            updateAllScoreboards();
        } else {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                Bukkit.getOnlinePlayers().forEach(p -> p.setScoreboard(manager.getMainScoreboard()));
            }
        }
    }

    public void setRespawnInvincibleEnabled(boolean enabled, CommandSender sender) {
        if (this.respawnInvincibleEnabled == enabled) {
            adventure.sender(sender).sendMessage(Component.text("リスポーン時無敵は既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.respawnInvincibleEnabled = enabled;
        adventure.sender(sender).sendMessage(Component.text("リスポーン時無敵を" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));
    }

    public void setGracePeriodEnabled(boolean enabled, CommandSender sender) {
        if (this.gracePeriodEnabled == enabled) {
            adventure.sender(sender).sendMessage(Component.text("準備時間は既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.gracePeriodEnabled = enabled;
        adventure.sender(sender).sendMessage(Component.text("準備時間を" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));
    }

    public void setDedTime(int time, CommandSender sender) { this.dedTime = time; adventure.sender(sender).sendMessage(Component.text("デススポーンの待機時間を " + time + " 秒に設定しました。", NamedTextColor.GREEN)); }
    public void setRespawnInvincibleTime(int time, CommandSender sender) { this.respawnInvincibleTime = time; adventure.sender(sender).sendMessage(Component.text("リスポーン時無敵の時間を " + time + " 秒に設定しました。", NamedTextColor.GREEN)); }
    public void setGracePeriodTime(int time, CommandSender sender) { this.gracePeriodTime = time; adventure.sender(sender).sendMessage(Component.text("準備時間を " + time + " 秒に設定しました。", NamedTextColor.GREEN)); }
    public void setOnZeroAction(OnZeroAction action, CommandSender sender) { this.onZeroAction = action; adventure.sender(sender).sendMessage(Component.text("残機0時のアクションを " + action.name() + " に変更しました。", NamedTextColor.GREEN)); }
    public void setLivesMode(LivesMode mode, CommandSender sender) { if (gameState != GameState.IDLE) { adventure.sender(sender).sendMessage(Component.text("ゲーム中はモードを変更できません。", NamedTextColor.RED)); return; } this.livesMode = mode; adventure.sender(sender).sendMessage(Component.text("残機モードを " + mode.name() + " に変更しました。", NamedTextColor.GREEN)); }

    public void prepareGameForTimer() {
        if (!featureEnabled) return;
        this.gameState = GameState.PRE_GAME;
        teleportPlayersAndCreateBoxes();
    }

    public void startGame() {
        if (!featureEnabled || gameState == GameState.RUNNING) return;
        this.gameState = GameState.RUNNING;
        updateAllScoreboards();
        createArenaWalls();
        unleashPlayers();

        if (gracePeriodEnabled && gracePeriodTime > 0) {
            isGracePeriodActive = true;
            gracePeriodCountdown = gracePeriodTime;
            adventure.all().sendMessage(Component.text("[PvP] " + gracePeriodTime + "秒間の準備時間(PvP無効)が開始されました。", NamedTextColor.AQUA));

            gracePeriodTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (gracePeriodCountdown <= 0 || gameState != GameState.RUNNING) {
                        isGracePeriodActive = false;
                        adventure.all().sendMessage(Component.text("[PvP] 準備時間が終了しました。PvPが有効になります。", NamedTextColor.RED));
                        updateAllScoreboards();
                        cancel();
                        return;
                    }
                    gracePeriodCountdown--;
                    updateAllScoreboards();
                }
            }.runTaskTimer(plugin, 20L, 20L);
        }

        hostileAreaDamageTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (gameState != GameState.RUNNING) {
                    cancel();
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isPlayerInEnemySpawnProtection(player)) {
                        player.damage(2.0);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 20L);
    }

    public void stopGame(CommandSender sender) {
        if (gameState == GameState.IDLE) return;

        this.gameState = GameState.IDLE;
        this.isGracePeriodActive = false;

        if (gracePeriodTask != null && !gracePeriodTask.isCancelled()) gracePeriodTask.cancel();
        if (hostileAreaDamageTask != null && !hostileAreaDamageTask.isCancelled()) hostileAreaDamageTask.cancel();


        new ArrayList<>(playerStateTasks.keySet()).forEach(uuid -> {
            if (playerStateTasks.containsKey(uuid)) {
                playerStateTasks.get(uuid).cancel();
            }
            playerStateTasks.remove(uuid);
        });

        invinciblePlayers.clear();
        respawnTimers.clear();
        zeroLivesWaitRespawn.clear();
        zeroLivesSpectator.clear();

        removeArenaWalls();
        removeSpawnBoxes();

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.setScoreboard(manager.getMainScoreboard()));
        }

        if (sender != null) {
            adventure.all().sendMessage(Component.text("[PvP] ゲームが終了しました！", NamedTextColor.AQUA));
        }
    }

    public void unleashPlayers() {
        if (gameState == GameState.IDLE) return;
        removeSpawnBoxes();
    }

    public void setCombined(Player player, String teamColor) {
        setArena(player, teamColor);
        setSpawn(player, teamColor);
        Material woolMaterial;
        try {
            woolMaterial = Material.valueOf(teamColor.toUpperCase() + "_WOOL");
        } catch (IllegalArgumentException e) {
            woolMaterial = Material.WHITE_WOOL;
        }
        Location baseLoc = player.getLocation().getBlock().getLocation();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                baseLoc.clone().add(x, -1, z).getBlock().setType(woolMaterial);
            }
        }
        adventure.player(player).sendMessage(Component.text("足元に3x3のマーカーブロックを設置しました。", NamedTextColor.GREEN));
    }

    public void removeTeamSettings(CommandSender sender, String teamColor) {
        if (!teamDataMap.containsKey(teamColor)) {
            adventure.sender(sender).sendMessage(Component.text("チーム '" + teamColor + "' は存在しません。", NamedTextColor.RED));
            return;
        }
        teamDataMap.put(teamColor, new ArenaData());
        adventure.sender(sender).sendMessage(Component.text("チーム '" + teamColor + "' の設定を削除しました。", NamedTextColor.GOLD));
    }

    public void resetSettings(CommandSender sender) {
        if (gameState != GameState.IDLE) {
            if(sender != null) adventure.sender(sender).sendMessage(Component.text("ゲーム中は設定をリセットできません。", NamedTextColor.RED));
            return;
        }
        teamDataMap.clear();
        for (String colorName : DEFAULT_TEAM_COLORS) {
            teamDataMap.put(colorName, new ArenaData());
        }
        dedArenaData = new ArenaData();
        teamLives.clear();
        playerLives.clear();
        if(sender != null) adventure.sender(sender).sendMessage(Component.text("PvPモードの全設定をリセットしました。", NamedTextColor.GOLD));
    }

    public boolean prepareGame(CommandSender sender) {
        if (!featureEnabled) return true;
        boolean isSafe = true;
        List<Player> participants = getRegisteredPlayers();
        Set<String> participantTeams = participants.stream().map(this::getPlayerTeamTag).filter(Objects::nonNull).collect(Collectors.toSet());
        for (String teamTag : participantTeams) {
            ArenaData data = teamDataMap.get(teamTag);
            if (data == null || data.getSpawnLocation() == null) {
                adventure.sender(sender).sendMessage(Component.text("警告: チーム '" + teamTag + "' のスポーン地点が未設定です。", NamedTextColor.RED));
                isSafe = false;
            }
        }
        if (!isSafe) {
            adventure.sender(sender).sendMessage(Component.text("設定をやり直してください。 /pvp set <色>", NamedTextColor.RED));
            return false;
        }
        if (sender instanceof Player) {
            adventure.sender(sender).sendMessage(Component.text("最終確認完了。問題は見つかりませんでした。", NamedTextColor.GREEN));
        }
        return true;
    }

    public void setDedCombined(Player player) {
        if (plugin.getWorldEditHook() == null) {
            adventure.player(player).sendMessage(Component.text("WorldEditが必須です。", NamedTextColor.RED));
            return;
        }
        setDedArena(player);
        setDedSpawn(player);
        if (dedArenaData.getArenaRegion() != null) {
            Region region = dedArenaData.getArenaRegion();
            World world = region.getWorld() != null ? BukkitAdapter.adapt(region.getWorld()) : null;
            if (world == null) {
                adventure.player(player).sendMessage(Component.text("デス待機場所のワールドが見つかりません。", NamedTextColor.RED));
                return;
            }

            for (BlockVector3 point : region) {
                if (point.x() == region.getMinimumPoint().x() || point.x() == region.getMaximumPoint().x() ||
                        point.z() == region.getMinimumPoint().z() || point.z() == region.getMaximumPoint().z() ||
                        point.y() == region.getMinimumPoint().y() || point.y() == region.getMaximumPoint().y())
                {
                    Block block = world.getBlockAt(point.x(), point.y(), point.z());
                    if (block.getType().isAir()) {
                        block.setType(Material.BARRIER);
                    }
                }
            }
            adventure.player(player).sendMessage(Component.text("デス待機場所にバリアの壁を設置しました。", NamedTextColor.GREEN));
        }
    }

    public void setTeamLives(String color, int lives, CommandSender sender) {
        teamLives.put(color, lives);
        adventure.sender(sender).sendMessage(Component.text("チーム " + color + " の残機を " + lives + " に設定しました。", NamedTextColor.GREEN));
        updateAllScoreboards();
    }

    public void setPlayerLives(Player player, int lives, CommandSender sender) {
        playerLives.put(player.getUniqueId(), lives);
        adventure.sender(sender).sendMessage(Component.text("プレイヤー " + player.getName() + " の残機を " + lives + " に設定しました。", NamedTextColor.GREEN));
        updateAllScoreboards();
    }

    public void handlePlayerDeath(Player deadPlayer) {
        if (gameState == GameState.IDLE || !featureEnabled) return;
        if (livesFeatureEnabled) {
            String teamTag = getPlayerTeamTag(deadPlayer);
            int currentLives = -1;
            if (livesMode == LivesMode.TEAM && teamTag != null) {
                currentLives = teamLives.getOrDefault(teamTag, 1) - 1;
                teamLives.put(teamTag, Math.max(0, currentLives));
            } else if (livesMode == LivesMode.PLAYER) {
                currentLives = playerLives.getOrDefault(deadPlayer.getUniqueId(), 1) - 1;
                playerLives.put(deadPlayer.getUniqueId(), Math.max(0, currentLives));
            }

            updateAllScoreboards();
            if (currentLives == 0) {
                handleZeroLives(deadPlayer);
                return;
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!deadPlayer.isOnline()) return;
                deadPlayer.spigot().respawn();
            }
        }.runTaskLater(plugin, 1L);
    }

    public void startRespawnTimer(Player player, int seconds) {
        respawnTimers.put(player.getUniqueId(), seconds);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                int timeLeft = respawnTimers.getOrDefault(player.getUniqueId(), 0);
                if (timeLeft <= 0 || !player.isOnline() || gameState != GameState.RUNNING) {
                    respawnTimers.remove(player.getUniqueId());
                    playerStateTasks.remove(player.getUniqueId());
                    if (player.isOnline() && gameState == GameState.RUNNING) {
                        String teamTag = getPlayerTeamTag(player);
                        if (teamTag != null && teamDataMap.get(teamTag).getSpawnLocation() != null) {
                            player.teleport(teamDataMap.get(teamTag).getSpawnLocation());
                            giveInvincibilityOnGrounded(player);
                        }
                    }
                    updatePlayerScoreboard(player);
                    cancel();
                    return;
                }
                respawnTimers.put(player.getUniqueId(), timeLeft - 1);
                updatePlayerScoreboard(player);
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        playerStateTasks.put(player.getUniqueId(), task);
    }

    @SuppressWarnings("deprecation")
    public void giveInvincibilityOnGrounded(Player player) {
        if (!respawnInvincibleEnabled) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !player.isOnGround()) return;
                makePlayerInvincible(player, respawnInvincibleTime);
                cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void handlePlayerQuit(Player player) {
        if (playerStateTasks.containsKey(player.getUniqueId())) {
            playerStateTasks.get(player.getUniqueId()).cancel();
            playerStateTasks.remove(player.getUniqueId());
        }
        invinciblePlayers.remove(player.getUniqueId());
        respawnTimers.remove(player.getUniqueId());
    }

    public boolean isLocationInProtectedArea(Location location) {
        if (gameState == GameState.IDLE) {
            return false;
        }

        BlockVector3 locVector = BlockVector3.at(location.getX(), location.getY(), location.getZ());

        for (ArenaData data : teamDataMap.values()) {
            if (data.getArenaRegion() != null && data.getArenaRegion().contains(locVector)) {
                return true;
            }
        }

        return dedArenaData.getArenaRegion() != null && dedArenaData.getArenaRegion().contains(locVector);
    }

    public boolean isPlayerInOwnTeamProtectedArea(Player player) {
        if (gameState != GameState.RUNNING) {
            return false;
        }

        String teamTag = getPlayerTeamTag(player);
        if (teamTag == null) {
            return false;
        }

        ArenaData teamArenaData = teamDataMap.get(teamTag);
        if (teamArenaData == null || teamArenaData.getArenaRegion() == null) {
            return false;
        }

        Region region = teamArenaData.getArenaRegion();
        BlockVector3 playerLocationVector = BlockVector3.at(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());

        return region.contains(playerLocationVector);
    }

    public boolean isLocationInProtectedDedArea(Location location) {
        if (gameState != GameState.IDLE && dedFeatureEnabled && dedArenaData.getArenaRegion() != null) {
            Region region = dedArenaData.getArenaRegion();
            return region.contains(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        }
        return false;
    }

    public Location getTeamSpawn(String tagName) {
        ArenaData data = teamDataMap.get(tagName);
        return (data != null) ? data.getSpawnLocation() : null;
    }

    public String getPlayerTeamTag(Player player) {
        for (String tagName : DEFAULT_TEAM_COLORS) {
            if (player.getScoreboardTags().contains(tagName)) return tagName;
        }
        return null;
    }

    public void showStatus(CommandSender sender) {
        if (!featureEnabled) {
            sender.sendMessage(ChatColor.RED + "PvPモードは現在無効です。");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "--- PvPモード設定状況 ---");
        boolean hasAnySetting = teamDataMap.values().stream().anyMatch(d -> d.getSpawnLocation() != null);

        if (!hasAnySetting) {
            sender.sendMessage(ChatColor.YELLOW + "設定が登録されているチームはありません。");
            return;
        }

        for (Map.Entry<String, ArenaData> entry : teamDataMap.entrySet()) {
            if (entry.getValue().getSpawnLocation() == null) continue;

            String teamColorName = entry.getKey();
            char colorCode = getColorCode(teamColorName);
            String teamString = "§" + colorCode + teamColorName;

            String spawnStatus = (entry.getValue().getSpawnLocation() != null) ? (ChatColor.GREEN + " ✔") : (ChatColor.RED + " ✖");
            sender.sendMessage(teamString + ChatColor.GRAY + " | リス地:" + spawnStatus);
        }
    }

    private void setDedArena(Player player) {
        if (plugin.getWorldEditHook() == null) return;
        try {
            com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
            Region selection = plugin.getWorldEditHook().getSessionManager().get(wePlayer).getSelection(wePlayer.getWorld());
            dedArenaData.setArenaRegion(selection);
        } catch (Exception e) {
            adventure.player(player).sendMessage(Component.text("デス待機場所(壁)の設定に失敗。範囲を選択してください。", NamedTextColor.RED));
        }
    }

    private void setSpawn(Player player, String teamColorName) {
        if (!teamDataMap.containsKey(teamColorName)) {
            adventure.player(player).sendMessage(Component.text("チーム '" + teamColorName + "' は存在しません。", NamedTextColor.RED));
            return;
        }
        Location centeredLoc = player.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
        centeredLoc.setPitch(player.getLocation().getPitch());
        centeredLoc.setYaw(player.getLocation().getYaw());
        teamDataMap.get(teamColorName).setSpawnLocation(centeredLoc);
        adventure.player(player).sendMessage(Component.text("チーム '" + teamColorName + "' のスポーン地点を設定しました。", NamedTextColor.GREEN));
    }

    private void setArena(Player player, String teamColorName) {
        if (plugin.getWorldEditHook() == null) {
            return;
        }
        try {
            com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
            Region selection = plugin.getWorldEditHook().getSessionManager().get(wePlayer).getSelection(wePlayer.getWorld());
            teamDataMap.get(teamColorName).setArenaRegion(selection);
        } catch (Exception e) {
            // silent fail
        }
    }

    private void setDedSpawn(Player player) {
        Location centeredLoc = player.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
        centeredLoc.setPitch(player.getLocation().getPitch());
        centeredLoc.setYaw(player.getLocation().getYaw());
        dedArenaData.setSpawnLocation(centeredLoc);
        adventure.player(player).sendMessage(Component.text("デス待機場所(スポーン)を設定しました。", NamedTextColor.GREEN));
    }

    private void handleZeroLives(Player player) {
        updateAllScoreboards();
        switch (onZeroAction) {
            case SPECTATOR -> {
                zeroLivesSpectator.add(player.getUniqueId());
                adventure.player(player).sendMessage(Component.text("残機が0になりました。観戦モードに移行します。", NamedTextColor.RED));
            }
            case WAIT -> {
                zeroLivesWaitRespawn.add(player.getUniqueId());
                adventure.player(player).sendMessage(Component.text("残機が0になりました。待機所へ移動します。", NamedTextColor.RED));
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if(player.isOnline()) player.spigot().respawn();
            }
        }.runTaskLater(plugin, 1L);
    }

    public void updateAllScoreboards() {
        Bukkit.getOnlinePlayers().forEach(this::updatePlayerScoreboard);
    }

    private List<Player> getRegisteredPlayers() {
        return Bukkit.getOnlinePlayers().stream().filter(p -> getPlayerTeamTag(p) != null).collect(Collectors.toList());
    }

    @SuppressWarnings("deprecation")
    public void updatePlayerScoreboard(Player player) {
        if (!player.isOnline()) return;

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        if (!livesFeatureEnabled && !isGracePeriodActive && !invinciblePlayers.containsKey(player.getUniqueId()) && !respawnTimers.containsKey(player.getUniqueId())) {
            player.setScoreboard(manager.getMainScoreboard());
            return;
        }

        Scoreboard board = manager.getNewScoreboard();

        Objective objective = board.registerNewObjective("pvp_status", "dummy", "§e§lステータス");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Team mainBoardTeam = nameManager.getPlayerTeam(p.getUniqueId());
            if (mainBoardTeam == null) continue;

            Team pvpBoardTeam = board.registerNewTeam(mainBoardTeam.getName());
            pvpBoardTeam.setColor(mainBoardTeam.getColor());
            pvpBoardTeam.setPrefix(mainBoardTeam.getPrefix());
            pvpBoardTeam.setSuffix(mainBoardTeam.getSuffix());
            pvpBoardTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, mainBoardTeam.getOption(Team.Option.NAME_TAG_VISIBILITY));
            pvpBoardTeam.addEntry(p.getName());
        }

        AtomicInteger scoreCounter = new AtomicInteger(15);
        boolean hasStatus = false;

        if (isGracePeriodActive) {
            objective.getScore("§b準備時間: §e" + gracePeriodCountdown + "秒").setScore(scoreCounter.getAndDecrement());
            hasStatus = true;
        } else if (respawnTimers.containsKey(player.getUniqueId())) {
            objective.getScore("§cデス待機: §e" + respawnTimers.get(player.getUniqueId()) + "秒").setScore(scoreCounter.getAndDecrement());
            hasStatus = true;
        } else if (invinciblePlayers.containsKey(player.getUniqueId())) {
            long timeLeft = (invinciblePlayers.get(player.getUniqueId()) - System.currentTimeMillis()) / 1000;
            if (timeLeft > 0) {
                objective.getScore("§bリスポーン無敵: §e" + timeLeft + "秒").setScore(scoreCounter.getAndDecrement());
                hasStatus = true;
            }
        } else if (isPlayerInOwnTeamProtectedArea(player)) {
            objective.getScore("§a自チームエリア内 (無敵)").setScore(scoreCounter.getAndDecrement());
            hasStatus = true;
        }

        if(livesFeatureEnabled) {
            if(hasStatus) objective.getScore(" ").setScore(scoreCounter.getAndDecrement());
            objective.getScore("§6- 残り残機 -").setScore(scoreCounter.getAndDecrement());

            if (livesMode == LivesMode.TEAM) {
                teamLives.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            String teamName = entry.getKey();
                            int lives = entry.getValue();
                            objective.getScore(ChatColor.translateAlternateColorCodes('&', "&" + getColorCode(teamName) + teamName + ": &e" + lives)).setScore(scoreCounter.getAndDecrement());
                        });
            } else {
                getRegisteredPlayers().stream()
                        .sorted(Comparator.comparing(p -> nameManager.getDisplayName(p.getUniqueId())))
                        .forEach(p -> {
                            int lives = playerLives.getOrDefault(p.getUniqueId(), 0);
                            objective.getScore(nameManager.getDisplayName(p.getUniqueId()) + ": §e" + lives).setScore(scoreCounter.getAndDecrement());
                        });
            }
        } else if (!hasStatus) {
            player.setScoreboard(manager.getMainScoreboard());
            return;
        }
        player.setScoreboard(board);
    }

    private void makePlayerInvincible(Player player, int seconds) {
        if (!player.isOnline()) return;
        final UUID playerUUID = player.getUniqueId();

        if (playerStateTasks.containsKey(playerUUID)) {
            playerStateTasks.get(playerUUID).cancel();
        }

        invinciblePlayers.put(playerUUID, System.currentTimeMillis() + ((long) seconds * 1000L));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, seconds * 20 + 10, 255, true, false));
        adventure.player(player).sendMessage(Component.text(seconds + "秒間、無敵です。", NamedTextColor.YELLOW));

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player onlinePlayer = Bukkit.getPlayer(playerUUID);
                if (onlinePlayer == null || !onlinePlayer.isOnline() || !invinciblePlayers.containsKey(playerUUID) || gameState != GameState.RUNNING) {
                    cleanup();
                    return;
                }

                long timeLeft = invinciblePlayers.get(playerUUID) - System.currentTimeMillis();
                if (timeLeft <= 0) {
                    adventure.player(onlinePlayer).sendMessage(Component.text("無敵時間が終了しました。", NamedTextColor.GRAY));
                    cleanup();
                    return;
                }
                updatePlayerScoreboard(onlinePlayer);
            }

            private void cleanup() {
                invinciblePlayers.remove(playerUUID);
                playerStateTasks.remove(playerUUID);
                Player onlinePlayer = Bukkit.getPlayer(playerUUID);
                if (onlinePlayer != null) {
                    onlinePlayer.removePotionEffect(PotionEffectType.RESISTANCE);
                    updatePlayerScoreboard(onlinePlayer);
                }
                cancel();
            }
        };
        task.runTaskTimer(plugin, 20L, 20L);
        playerStateTasks.put(playerUUID, task);
    }

    private void teleportPlayersAndCreateBoxes() {
        getRegisteredPlayers().forEach(p -> {
            String teamTag = getPlayerTeamTag(p);
            ArenaData data = teamDataMap.get(teamTag);
            if (data != null && data.getSpawnLocation() != null) {
                p.teleport(data.getSpawnLocation());
                createSpawnBox(data);
            }
        });
    }

    private void createSpawnBox(ArenaData data) {
        Location center = data.getSpawnLocation().getBlock().getLocation();
        Location[] boxBlocks = {
                center.clone().add(0, -1, 0), center.clone().add(0, 2, 0),
                center.clone().add(1, 0, 0), center.clone().add(-1, 0, 0),
                center.clone().add(0, 0, 1), center.clone().add(0, 0, -1),
                center.clone().add(1, 1, 0), center.clone().add(-1, 1, 0),
                center.clone().add(0, 1, 1), center.clone().add(0, 1, -1)
        };
        for (Location loc : boxBlocks) {
            placeTempBarrier(loc, data);
        }
    }

    private void placeTempBarrier(Location loc, ArenaData data) {
        if (loc.getBlock().getType().isAir() || loc.getBlock().isPassable()) {
            loc.getBlock().setType(Material.BARRIER);
            data.getSpawnBoxBlocks().add(loc.clone());
        }
    }

    private void removeSpawnBoxes() {
        teamDataMap.values().forEach(data -> {
            data.getSpawnBoxBlocks().forEach(loc -> {
                if (loc.getBlock().getType() == Material.BARRIER) {
                    loc.getBlock().setType(Material.AIR);
                }
            });
            data.getSpawnBoxBlocks().clear();
        });
    }

    private void createArenaWalls() {
        // This method is a placeholder for potential future functionality.
    }

    private void removeArenaWalls() {
        teamDataMap.values().forEach(data -> {
            data.getWallBlocks().forEach(loc -> {
                if (loc.getBlock().getType() == Material.BARRIER) {
                    loc.getBlock().setType(Material.AIR);
                }
            });
            data.getWallBlocks().clear();
        });
    }

    private char getColorCode(String colorName) {
        return switch (colorName.toLowerCase()) {
            case "black" -> '0'; case "dark_blue" -> '1';
            case "dark_green" -> '2'; case "dark_aqua" -> '3';
            case "dark_red" -> '4'; case "dark_purple" -> '5';
            case "gold", "orange" -> '6'; case "gray" -> '7';
            case "dark_gray" -> '8'; case "blue" -> '9';
            case "green", "lime" -> 'a'; case "aqua", "light_blue", "cyan" -> 'b';
            case "red" -> 'c'; case "light_purple", "pink", "magenta" -> 'd';
            case "yellow" -> 'e'; default -> 'f'; // white, brown etc.
        };
    }
}