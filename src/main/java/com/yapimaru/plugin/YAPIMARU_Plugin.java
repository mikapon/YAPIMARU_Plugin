package com.yapimaru.plugin;

import com.yapimaru.plugin.commands.ServerCommand;
import com.yapimaru.plugin.commands.VoteCommand;
import com.yapimaru.plugin.commands.YmCommand;
import com.yapimaru.plugin.listeners.ChatListener;
import com.yapimaru.plugin.listeners.PlayerListener;
import com.yapimaru.plugin.managers.ConfigManager;
import com.yapimaru.plugin.managers.DatabaseManager;
import com.yapimaru.plugin.managers.DiscordManager;
import com.yapimaru.plugin.managers.TimerManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class YAPIMARU_Plugin extends JavaPlugin implements Listener {
    private static YAPIMARU_Plugin instance;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private DiscordManager discordManager;
    private ChatListener chatListener;

    private boolean pollActive = false;
    private String pollQuestion;
    private Map<UUID, Integer> playerVotes = new HashMap<>();
    private Map<Integer, String> pollOptions = new HashMap<>();

    public YAPIMARU_Plugin() {
        instance = this;
    }

    public static YAPIMARU_Plugin getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DiscordManager getDiscordManager() {
        return discordManager;
    }

    public ChatListener getChatListener() {
        return chatListener;
    }

    // 投票システムのゲッターとセッター
    public boolean isPollActive() {
        return pollActive;
    }

    public void setPollActive(boolean pollActive) {
        this.pollActive = pollActive;
    }

    public String getPollQuestion() {
        return pollQuestion;
    }

    public void setPollQuestion(String pollQuestion) {
        this.pollQuestion = pollQuestion;
    }

    public Map<UUID, Integer> getPlayerVotes() {
        return playerVotes;
    }

    public Map<Integer, String> getPollOptions() {
        return pollOptions;
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        getLogger().info(ChatColor.GREEN + "YAPIMARU_Plugin が有効になりました。");

        // インスタンスの初期化
        instance = this;
        configManager = new ConfigManager(this);
        discordManager = new DiscordManager(this);
        chatListener = new ChatListener(this);

        try {
            databaseManager = new DatabaseManager(this);
        } catch (SQLException e) {
            getLogger().severe("データベースの初期化に失敗しました。");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // TimerManagerの初期化
        TimerManager timerManager = new TimerManager(this);
        timerManager.startTimers();

        // リスナーの登録
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(chatListener, this);

        // コマンドの登録
        YmCommand ymCommand = new YmCommand(this);
        getCommand("ym").setExecutor(ymCommand);
        getCommand("vote").setExecutor(new VoteCommand(this));

        // [修正点 1] ServerCommandクラスには引数なしのコンストラクタしかないため、引数を渡さずにインスタンス化します。
        // 元のコード: getCommand("server").setExecutor(new ServerCommand(this, timerManager, ymCommand));
        getCommand("server").setExecutor(new ServerCommand());

        // [修正点 2] 以下のコマンドとタブ補完は、対応するクラスファイルが存在しないためコンパイルエラーになります。
        // 実装が完了するまで一時的にコメントアウトします。
        // getCommand("vote").setTabCompleter(new VoteTabCompleter());
        // getCommand("question").setExecutor(new QuestionCommand(this));
        // getCommand("answer").setExecutor(new AnswerCommand(this));
        // getCommand("endpoll").setExecutor(new EndPollCommand(this));

        // カスタムレシピの登録
        registerCustomRecipes();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info(ChatColor.RED + "YAPIMARU_Plugin が無効になりました。");
        if (databaseManager != null) {
            databaseManager.closeConnection();
        }
        if (discordManager != null) {
            discordManager.shutdown();
        }
    }

    private void registerCustomRecipes() {
        // レシピ: ダイヤモンド9個 -> ネザライトインゴット1個
        ItemStack netheriteIngot = new ItemStack(Material.NETHERITE_INGOT);
        NamespacedKey netheriteKey = new NamespacedKey(this, "netherite_ingot_from_diamonds");
        ShapedRecipe netheriteRecipe = new ShapedRecipe(netheriteKey, netheriteIngot);
        netheriteRecipe.shape("DDD", "DDD", "DDD");
        netheriteRecipe.setIngredient('D', Material.DIAMOND);
        // レシピが既に存在しないか確認してから追加
        if (Bukkit.getRecipe(netheriteKey) == null) {
            Bukkit.addRecipe(netheriteRecipe);
        }

        // レシピ: エリトラ1個 -> ダイヤモンド8個
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 8);
        NamespacedKey diamondsKey = new NamespacedKey(this, "diamonds_from_elytra");
        ShapedRecipe diamondsRecipe = new ShapedRecipe(diamondsKey, diamonds);
        diamondsRecipe.shape(" ", " E ", " ");
        diamondsRecipe.setIngredient('E', Material.ELYTRA);
        if (Bukkit.getRecipe(diamondsKey) == null) {
            Bukkit.addRecipe(diamondsRecipe);
        }
    }

    public void sendVoteStatus(Player player) {
        if (!isPollActive()) {
            player.sendMessage("現在、投票は行われていません。");
            return;
        }

        player.sendMessage("現在の投票状況:");
        player.sendMessage("質問: " + getPollQuestion());
        for (Map.Entry<Integer, String> entry : getPollOptions().entrySet()) {
            long count = getPlayerVotes().values().stream().filter(v -> v.equals(entry.getKey())).count();
            player.sendMessage(entry.getKey() + ": " + entry.getValue() + " (" + count + "票)");
        }
    }
}
