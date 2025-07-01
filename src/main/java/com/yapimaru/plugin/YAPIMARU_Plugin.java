package com.yapimaru.plugin;

import com.sk89q.worldedit.WorldEdit;
import com.yapimaru.plugin.commands.*;
import com.yapimaru.plugin.completers.*;
import com.yapimaru.plugin.listeners.GuiListener;
import com.yapimaru.plugin.listeners.PlayerEventListener;
import com.yapimaru.plugin.listeners.VoteListener;
import com.yapimaru.plugin.managers.*;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class YAPIMARU_Plugin extends JavaPlugin {

    private BukkitAudiences adventure;
    private WorldEdit worldEditHook;

    private ParticipantManager participantManager;
    private NameManager nameManager;
    private GuiManager creatorGuiManager;
    private TimerManager timerManager;
    private PvpManager pvpManager;
    private WhitelistManager whitelistManager;
    private PlayerRestrictionManager restrictionManager;
    private SpectatorManager spectatorManager;
    private VoteManager voteManager;
    private YmCommand ymCommand;

    private List<String> commandManual = new ArrayList<>();

    @Override
    public void onEnable() {
        this.adventure = BukkitAudiences.create(this);

        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            this.worldEditHook = WorldEdit.getInstance();
        } else {
            getLogger().warning("WorldEdit not found. Some features will be disabled.");
        }

        getDataFolder().mkdirs();
        saveDefaultConfig();

        initializeManagers();

        // ★★★ サーバー起動時の自動移行処理を削除 ★★★

        // データを読み込む
        loadConfigAndManual();
        linkManagers();

        registerListeners();
        registerCommands();

        for (Player player : Bukkit.getOnlinePlayers()) {
            nameManager.updatePlayerName(player);
        }

        getLogger().info("YAPIMARU Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        if (nameManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                nameManager.resetPlayerName(player);
            }
        }
        if (timerManager != null) timerManager.forceStop(true);

        if(adventure != null) {
            adventure.close();
            this.adventure = null;
        }
        getLogger().info("YAPIMARU Plugin has been disabled!");
    }

    public void loadConfigAndManual() {
        reloadConfig();

        File manualFile = new File(getDataFolder(), "commands.txt");
        if (!manualFile.exists()) {
            try (InputStream in = getResource("commands.txt")) {
                if (in != null) {
                    Files.copy(in, manualFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not save commands.txt!", e);
            }
        }
        try {
            commandManual = Files.readAllLines(manualFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not read commands.txt!", e);
        }

        if (whitelistManager != null) whitelistManager.load();

        if (participantManager != null) participantManager.reloadAllParticipants();
        if (nameManager != null) nameManager.reloadData();
    }

    public List<String> getCommandManual() {
        return commandManual;
    }

    private void initializeManagers() {
        participantManager = new ParticipantManager(this);
        nameManager = new NameManager(this, participantManager);
        voteManager = new VoteManager(this);
        whitelistManager = new WhitelistManager(this, participantManager);
        creatorGuiManager = new GuiManager(this, nameManager);
        pvpManager = new PvpManager(this);
        timerManager = new TimerManager(this);
        restrictionManager = new PlayerRestrictionManager();
        spectatorManager = new SpectatorManager(this);
        ymCommand = new YmCommand(this);
    }

    private void linkManagers() {
        nameManager.setVoteManager(voteManager);
        voteManager.setNameManager(nameManager);
        spectatorManager.linkManagers(nameManager, pvpManager);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerEventListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this, ymCommand), this);
        Bukkit.getPluginManager().registerEvents(new VoteListener(voteManager), this);
        Bukkit.getPluginManager().registerEvents(restrictionManager, this);
        Bukkit.getPluginManager().registerEvents(spectatorManager, this);
    }

    private void registerCommands() {
        setExecutor("yapimaru", ymCommand, new YmTabCompleter(participantManager));
        setExecutor("creator", new CreatorCommand(creatorGuiManager), new CreatorTabCompleter());
        setExecutor("hub", new HubCommand(this));
        setExecutor("name", new NameCommand(this, nameManager), new NameTabCompleter());
        setExecutor("pvp", new PvpCommand(this, pvpManager), new PvpTabCompleter(pvpManager));
        setExecutor("timer", new TimerCommand(this, timerManager), new TimerTabCompleter());
        setExecutor("skinlist", new SkinListCommand(adventure));
        setExecutor("server", new ServerCommand(this, timerManager), new ServerTabCompleter());
        setExecutor("spectator", new SpectatorCommand(spectatorManager, adventure), new SpectatorTabCompleter());
        setExecutor("voting", new VotingCommand(this, voteManager), new VotingTabCompleter(voteManager));
        setExecutor("ans", new AnsCommand(voteManager), new AnsTabCompleter(voteManager));
        setExecutor("photographing", new PhotographingCommand(this, participantManager));
        setExecutor("stats", new StatsCommand(this, participantManager, nameManager), new StatsTabCompleter(participantManager));
        // ★★★ 新しいコマンドを登録 ★★★
        setExecutor("ppparticipant", new PpparticipantCommand(this, participantManager, nameManager));
    }

    private void setExecutor(String commandName, CommandExecutor executor) {
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setExecutor(executor);
        }
    }

    private void setExecutor(String commandName, CommandExecutor executor, TabCompleter completer) {
        setExecutor(commandName, executor);
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setTabCompleter(completer);
        }
    }

    // Getters
    public ParticipantManager getParticipantManager() { return participantManager; }
    public WorldEdit getWorldEditHook() { return this.worldEditHook; }
    public BukkitAudiences getAdventure() { return this.adventure; }
    public NameManager getNameManager() { return nameManager; }
    public TimerManager getTimerManager() { return timerManager; }
    public PvpManager getPvpManager() { return pvpManager; }
    public WhitelistManager getWhitelistManager() { return whitelistManager; }
    public PlayerRestrictionManager getRestrictionManager() { return restrictionManager; }
    public SpectatorManager getSpectatorManager() { return spectatorManager; }
    public GuiManager getCreatorGuiManager() { return creatorGuiManager; }
}