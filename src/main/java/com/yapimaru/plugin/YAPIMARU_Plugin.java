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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class YAPIMARU_Plugin extends JavaPlugin {

    private BukkitAudiences adventure;
    private WorldEdit worldEditHook;

    private NameManager nameManager;
    private GuiManager creatorGuiManager;
    private TimerManager timerManager;
    private PvpManager pvpManager;
    private PlayerRestrictionManager restrictionManager;
    private SpectatorManager spectatorManager;
    private VoteManager voteManager;
    private ParticipantManager participantManager;
    private WhitelistManager whitelistManager;
    private ConfigManager configManager;
    private YmCommand ymCommand;

    private List<String> commandManual = new ArrayList<>();

    @Override
    public void onEnable() {
        this.adventure = BukkitAudiences.create(this);

        if (Bukkit.getPluginManager().getPlugin("WorldEdit") != null) {
            this.worldEditHook = WorldEdit.getInstance();
        }

        saveDefaultConfig();
        initializeManagers();
        loadConfigAndManual();
        linkManagers();
        registerListeners();
        registerCommands();

        participantManager.handleServerStartup();

        for (Player player : Bukkit.getOnlinePlayers()) {
            participantManager.handlePlayerLogin(player.getUniqueId(), true);
            nameManager.updatePlayerName(player);
        }

        getLogger().info("YAPIMARU Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        if (timerManager != null) timerManager.forceStop(true);
        if (participantManager != null) {
            participantManager.saveAllParticipantData();
        }
        if (adventure != null) {
            adventure.close();
            this.adventure = null;
        }
        getLogger().info("YAPIMARU Plugin has been disabled!");
    }

    public void loadConfigAndManual() {
        reloadConfig();
        if (configManager != null) configManager.reloadConfig();

        File manualFile = new File(getDataFolder(), "commands.txt");
        if (!manualFile.exists()) {
            saveResource("commands.txt", false);
        }
        try {
            commandManual = Files.readAllLines(manualFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not read commands.txt!", e);
        }

        if (participantManager != null) participantManager.reloadAllParticipants();
        if (nameManager != null) nameManager.reloadData();
        if (whitelistManager != null) whitelistManager.load();
    }

    private void initializeManagers() {
        this.configManager = new ConfigManager(this);
        this.participantManager = new ParticipantManager(this);
        this.voteManager = new VoteManager(this);
        this.nameManager = new NameManager(this, participantManager);
        this.whitelistManager = new WhitelistManager(this);
        this.creatorGuiManager = new GuiManager(nameManager);
        this.pvpManager = new PvpManager(this);
        this.timerManager = new TimerManager(this);
        this.restrictionManager = new PlayerRestrictionManager();
        this.spectatorManager = new SpectatorManager(this, pvpManager);
        this.ymCommand = new YmCommand(this);
    }

    private void linkManagers() {
        nameManager.setVoteManager(voteManager);
        voteManager.setNameManager(nameManager);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerEventListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this, ymCommand), this);
        getServer().getPluginManager().registerEvents(new VoteListener(voteManager), this);
        getServer().getPluginManager().registerEvents(restrictionManager, this);
        getServer().getPluginManager().registerEvents(spectatorManager, this);
    }

    private void registerCommands() {
        setExecutor("yapimaru", ymCommand, new YmTabCompleter(participantManager));
        setExecutor("creator", new CreatorCommand(creatorGuiManager), new CreatorTabCompleter());
        setExecutor("hub", new HubCommand(this), null);
        setExecutor("name", new NameCommand(this, nameManager, participantManager), new NameTabCompleter());
        setExecutor("pvp", new PvpCommand(this, pvpManager), new PvpTabCompleter(pvpManager));
        setExecutor("timer", new TimerCommand(this, timerManager), new TimerTabCompleter());
        setExecutor("skinlist", new SkinListCommand(adventure), null);
        setExecutor("server", new ServerCommand(this, timerManager), new ServerTabCompleter());
        setExecutor("spectator", new SpectatorCommand(spectatorManager, adventure), new SpectatorTabCompleter());
        setExecutor("voting", new VotingCommand(this, voteManager), new VotingTabCompleter(voteManager));
        setExecutor("ans", new AnsCommand(voteManager), new AnsTabCompleter(voteManager));
        setExecutor("stats", new StatsCommand(this, participantManager, nameManager), new StatsTabCompleter(participantManager));
        setExecutor("photographing", new PhotographingCommand(this, participantManager), null);
        LogCommand logCommand = new LogCommand(this);
        setExecutor("log", logCommand, logCommand);
    }

    private void setExecutor(String commandName, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setExecutor(executor);
            if (completer != null) {
                command.setTabCompleter(completer);
            }
        }
    }

    public BukkitAudiences getAdventure() { return this.adventure; }
    public NameManager getNameManager() { return nameManager; }
    public TimerManager getTimerManager() { return timerManager; }
    public PvpManager getPvpManager() { return pvpManager; }
    public PlayerRestrictionManager getRestrictionManager() { return restrictionManager; }
    public SpectatorManager getSpectatorManager() { return spectatorManager; }
    public GuiManager getCreatorGuiManager() { return creatorGuiManager; }
    public ParticipantManager getParticipantManager() { return participantManager; }
    public WhitelistManager getWhitelistManager() { return whitelistManager; }
    public ConfigManager getConfigManager() { return configManager; }
    public YmCommand getYmCommand() { return ymCommand; }
    public WorldEdit getWorldEditHook() { return worldEditHook; }
    public List<String> getCommandManual() { return commandManual; }
}