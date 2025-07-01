package com.yapimaru.plugin.managers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerRestrictionManager implements Listener {

    public enum RestrictionMode {
        NONE,
        ADVENTURE_ONLY,
        NO_INTERACT,
        NO_MOVE,
        NO_WASD
    }

    private RestrictionMode currentMode = RestrictionMode.NONE;
    private final Set<UUID> exemptPlayers = new HashSet<>();

    public RestrictionMode getCurrentMode() {
        return currentMode;
    }

    public void setMode(RestrictionMode mode) {
        this.currentMode = mode;
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyModeToPlayer(player);
        }
    }

    private boolean isRestricted(Player player) {
        // OPは全ての制限から除外する
        if (player.isOp()) {
            return false;
        }
        if (currentMode == RestrictionMode.NONE || exemptPlayers.contains(player.getUniqueId())) {
            return false;
        }
        return true;
    }

    public void applyModeToPlayer(Player player) {
        if (player.isOp()) {
            return;
        }
        if (isRestricted(player)) {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyModeToPlayer(event.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isRestricted(event.getPlayer()) && (currentMode == RestrictionMode.NO_INTERACT || currentMode == RestrictionMode.NO_MOVE || currentMode == RestrictionMode.NO_WASD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isRestricted(event.getPlayer()) && (currentMode == RestrictionMode.NO_INTERACT || currentMode == RestrictionMode.NO_MOVE || currentMode == RestrictionMode.NO_WASD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (remover instanceof Player) {
            if (isRestricted((Player) remover) && (currentMode == RestrictionMode.NO_INTERACT || currentMode == RestrictionMode.NO_MOVE || currentMode == RestrictionMode.NO_WASD)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer()) && (currentMode == RestrictionMode.ADVENTURE_ONLY || currentMode == RestrictionMode.NO_INTERACT || currentMode == RestrictionMode.NO_MOVE || currentMode == RestrictionMode.NO_WASD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer()) && (currentMode == RestrictionMode.ADVENTURE_ONLY || currentMode == RestrictionMode.NO_INTERACT || currentMode == RestrictionMode.NO_MOVE || currentMode == RestrictionMode.NO_WASD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isRestricted(event.getPlayer())) return;

        if (currentMode == RestrictionMode.NO_MOVE) {
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getY() != event.getTo().getY() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        } else if (currentMode == RestrictionMode.NO_WASD) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (isRestricted(event.getPlayer()) && currentMode == RestrictionMode.NO_MOVE) {
            if (event.isSneaking()) {
                event.setCancelled(true);
            }
        }
    }
}