package com.yapimaru.plugin.listeners;

import com.yapimaru.plugin.managers.VoteManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class VoteListener implements Listener {

    private final VoteManager voteManager;

    public VoteListener(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        voteManager.getActivePolls().values().forEach(voteData -> {
            voteManager.showPollToPlayer(event.getPlayer(), voteData);
        });
    }
}