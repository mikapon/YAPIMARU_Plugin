package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimerManager {

    public enum TimerMode {COUNTDOWN, COUNTUP}
    public enum DisplayType {ACTIONBAR, BOSSBAR, TITLE, OFF}
    private enum TimerState { IDLE, PRE_START, RUNNING, PAUSED }

    private final YAPIMARU_Plugin plugin;
    private final BukkitAudiences adventure;
    private final PvpManager pvpManager;

    private boolean featureEnabled = true;
    private TimerMode mode = TimerMode.COUNTDOWN;
    private DisplayType displayType = DisplayType.ACTIONBAR;
    private TimerState state = TimerState.IDLE;
    private int preStartSeconds = 3;
    private int preStartCounter = 0;
    private long initialTime = 0;
    private long currentTime = 0;
    private BukkitTask task;
    private BossBar bossBar;

    private final Map<String, TreeMap<Integer, String>> onEndActions = new HashMap<>();
    private List<String> rotatingSubtitles = new ArrayList<>();
    private int subtitleIndex = 0;
    private long subtitleIntervalMillis = 2000L;
    private long lastSubtitleChangeMillis = 0L;

    private final Set<Long> notificationTimes = Set.of(3600L, 2700L, 1800L, 600L, 300L, 180L, 60L, 30L, 15L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);

    public TimerManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.pvpManager = plugin.getPvpManager();
        this.bossBar = BossBar.bossBar(Component.text("Timer"), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        onEndActions.put("msg", new TreeMap<>());
        onEndActions.put("cmd", new TreeMap<>());
        reset(Bukkit.getConsoleSender());
    }

    public TimerMode getMode() { return this.mode; }
    public DisplayType getDisplayType() { return this.displayType; }
    public int getPreStartSeconds() { return this.preStartSeconds; }
    public String getInitialTimeFormatted() { return formatTime(this.initialTime); }
    public boolean isFeatureEnabled() { return this.featureEnabled; }
    public boolean isRunning() { return this.state != TimerState.IDLE; }

    public void setFeatureEnabled(boolean enabled, CommandSender sender) {
        if (this.featureEnabled == enabled) {
            adventure.sender(sender).sendMessage(Component.text("タイマー機能は既に" + (enabled ? "有効" : "無効") + "です。", NamedTextColor.YELLOW));
            return;
        }
        this.featureEnabled = enabled;
        if (!enabled) {
            if (isRunning()) forceStop(true);
            reset(sender);
        }
        adventure.sender(sender).sendMessage(Component.text("タイマー機能を" + (enabled ? "有効" : "無効") + "にしました。", enabled ? NamedTextColor.GREEN : NamedTextColor.GOLD));
    }

    public void reset(CommandSender sender) {
        if (isRunning()) {
            if (sender != null && sender != Bukkit.getConsoleSender()) {
                adventure.sender(sender).sendMessage(Component.text("タイマーが作動中はリセットできません。", NamedTextColor.RED));
            }
            return;
        }
        this.mode = TimerMode.COUNTDOWN;
        this.displayType = DisplayType.ACTIONBAR;
        this.preStartSeconds = 3;
        this.initialTime = 0;
        this.currentTime = 0;
        this.rotatingSubtitles.clear();
        clearOnEndActions();

        this.onEndActions.get("msg").put(0, "終了");

        if(sender != null && sender != Bukkit.getConsoleSender()) {
            adventure.sender(sender).sendMessage(Component.text("タイマーの設定をデフォルトにリセットしました。", NamedTextColor.GOLD));
        }

        adventure.players().hideBossBar(bossBar);
        updateDisplay(true);
    }

    public void start(CommandSender sender) {
        if (isRunning()) {
            adventure.sender(sender).sendMessage(Component.text("タイマーは既に作動中です。", NamedTextColor.RED));
            return;
        }
        if (mode == TimerMode.COUNTDOWN && initialTime <= 0) {
            adventure.sender(sender).sendMessage(Component.text("時間が設定されていません。先に /timer set <時間> を使用してください。", NamedTextColor.RED));
            return;
        }

        if (pvpManager.isFeatureEnabled()) {
            if (!pvpManager.prepareGame(sender)) return;
            pvpManager.prepareGameForTimer();
        }

        if (preStartSeconds > 0) {
            state = TimerState.PRE_START;
            preStartCounter = preStartSeconds;
        } else {
            state = TimerState.RUNNING;
            startNotifications();
        }

        if (task == null || task.isCancelled()) {
            startTimerTask();
        }
    }

    public void pause(CommandSender sender) {
        if (state != TimerState.RUNNING) {
            adventure.sender(sender).sendMessage(Component.text("タイマーは作動していません。", NamedTextColor.RED));
            return;
        }
        state = TimerState.PAUSED;
        adventure.sender(sender).sendMessage(Component.text("タイマーを " + formatTime(currentTime) + " で一時停止しました。", NamedTextColor.YELLOW));
    }

    public void resume(CommandSender sender) {
        if (state != TimerState.PAUSED) {
            adventure.sender(sender).sendMessage(Component.text("タイマーは一時停止されていません。", NamedTextColor.RED));
            return;
        }
        state = TimerState.RUNNING;
        adventure.sender(sender).sendMessage(Component.text("タイマーを再開しました。", NamedTextColor.GREEN));
    }

    public void stop(CommandSender sender) {
        if (!isRunning()) {
            adventure.sender(sender).sendMessage(Component.text("タイマーは作動していません。", NamedTextColor.RED));
            return;
        }
        forceStop(false);
        adventure.sender(sender).sendMessage(Component.text("タイマーを停止しました。設定は維持されています。", NamedTextColor.GOLD));
    }

    public void forceStop(boolean resetSettings) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }

        if (pvpManager.isFeatureEnabled() && pvpManager.getGameState() != PvpManager.GameState.IDLE) {
            pvpManager.stopGame(null);
        }

        state = TimerState.IDLE;
        if (resetSettings) {
            reset(null);
        } else {
            this.currentTime = (mode == TimerMode.COUNTDOWN) ? initialTime : 0;
        }
        updateDisplay(true);
    }

    private void startTimerTask() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                switch (state) {
                    case PRE_START -> handlePreStart();
                    case RUNNING -> handleRunning();
                    case PAUSED, IDLE -> updateDisplay(false);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void handlePreStart() {
        if (preStartCounter > 0) {
            Title title = Title.title(Component.text(preStartCounter, NamedTextColor.YELLOW), Component.empty(), Title.Times.of(Duration.ofMillis(200), Duration.ofMillis(700), Duration.ofMillis(100)));
            adventure.all().showTitle(title);
            adventure.all().playSound(Sound.sound(Key.key("minecraft:ui.button.click"), Sound.Source.MASTER, 1f, 1f));
            preStartCounter--;
        } else {
            state = TimerState.RUNNING;
            startNotifications();
        }
    }

    private void handleRunning() {
        updateDisplay(false);
        if (mode == TimerMode.COUNTDOWN) {
            if (notificationTimes.contains(currentTime)) {
                adventure.all().sendMessage(Component.text("残り " + formatTime(currentTime), NamedTextColor.GOLD));
            }
            if (currentTime <= 0) {
                handleTimerEnd();
                return;
            }
            currentTime--;
        } else {
            if (initialTime > 0 && currentTime >= initialTime) {
                handleTimerEnd();
                return;
            }
            currentTime++;
        }
    }

    private void startNotifications() {
        Title title = Title.title(Component.text("START!!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.empty(), Title.Times.of(Duration.ofMillis(200), Duration.ofMillis(1000), Duration.ofMillis(500)));
        adventure.all().showTitle(title);
        adventure.all().playSound(Sound.sound(Key.key("minecraft:entity.player.levelup"), Sound.Source.MASTER, 0.8f, 1.2f));
        if (pvpManager.isFeatureEnabled()) {
            pvpManager.startGame();
        }
    }

    private void handleTimerEnd() {
        forceStop(false);

        if (!onEndActions.get("msg").isEmpty() || !onEndActions.get("cmd").isEmpty()) {
            onEndActions.forEach((type, actions) -> {
                actions.forEach((priority, value) -> {
                    if ("msg".equals(type)) {
                        adventure.all().sendMessage(Component.text(value, NamedTextColor.RED, TextDecoration.BOLD));
                    } else if ("cmd".equals(type)) {
                        final String commandToExecute = value.startsWith("/") ? value.substring(1) : value;
                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute));
                    }
                });
            });
        }
    }

    public void setTime(CommandSender sender, String timeString) {
        if (isRunning() && state != TimerState.PAUSED && state != TimerState.IDLE) {
            adventure.sender(sender).sendMessage(Component.text("タイマーが作動中は時間を設定できません。", NamedTextColor.RED));
            return;
        }
        long seconds = parseTime(timeString);
        if (seconds < 0) {
            adventure.sender(sender).sendMessage(Component.text("無効な時間形式です。例: 1h30m10s", NamedTextColor.RED));
            return;
        }
        this.initialTime = seconds;
        this.currentTime = (mode == TimerMode.COUNTDOWN) ? seconds : 0;
        if (sender != null) {
            adventure.sender(sender).sendMessage(Component.text("タイマーを " + formatTime(seconds) + " に設定しました。", NamedTextColor.GREEN));
        }
        updateDisplay(true);
    }

    public void setDisplay(CommandSender sender, String displayString) {
        try {
            DisplayType newDisplay = DisplayType.valueOf(displayString.toUpperCase());
            if (this.displayType != DisplayType.BOSSBAR && newDisplay == DisplayType.BOSSBAR && isRunning()) {
                adventure.players().showBossBar(bossBar);
            } else if (this.displayType == DisplayType.BOSSBAR && newDisplay != DisplayType.BOSSBAR) {
                adventure.players().hideBossBar(bossBar);
            }
            this.displayType = newDisplay;
            adventure.sender(sender).sendMessage(Component.text("タイマーの表示を " + displayType.name() + " に設定しました。", NamedTextColor.GREEN));
            updateDisplay(true);
        } catch (IllegalArgumentException e) {
            adventure.sender(sender).sendMessage(Component.text("無効な表示形式です。「actionbar」、「bossbar」、「title」、または「off」を使用してください。", NamedTextColor.RED));
        }
    }

    public boolean addOnEndAction(CommandSender sender, String type, int priority, String value) {
        TreeMap<Integer, String> actions = onEndActions.get(type);
        if (actions.containsValue(value)) {
            adventure.sender(sender).sendMessage(Component.text("同じ内容のアクションが既に登録されています。", NamedTextColor.RED));
            return false;
        }
        if (actions.containsKey(priority)) {
            TreeMap<Integer, String> newActions = new TreeMap<>();
            actions.forEach((p, v) -> {
                if (p < priority) {
                    newActions.put(p, v);
                } else {
                    newActions.put(p + 1, v);
                }
            });
            newActions.put(priority, value);
            onEndActions.put(type, newActions);
        } else {
            actions.put(priority, value);
        }
        return true;
    }

    public boolean removeOnEndAction(CommandSender sender, String type, int priority) {
        if (!onEndActions.get(type).containsKey(priority)) {
            adventure.sender(sender).sendMessage(Component.text("指定された優先度のアクションは見つかりませんでした。", NamedTextColor.RED));
            return false;
        }
        onEndActions.get(type).remove(priority);
        return true;
    }

    private void updateDisplay(boolean forceShow) {
        if (!isRunning() && !forceShow) {
            if(displayType == DisplayType.BOSSBAR) adventure.players().hideBossBar(bossBar);
            return;
        }
        Component timeComponent = Component.text(formatTime(currentTime), TextColor.color(0x55FF55));
        switch (displayType) {
            case ACTIONBAR -> adventure.players().sendActionBar(timeComponent);
            case BOSSBAR -> {
                float progress = (initialTime > 0) ? (float) currentTime / initialTime : 1.0f;
                bossBar.progress(Math.max(0.0f, Math.min(1.0f, progress)));
                bossBar.name(timeComponent);
                if (isRunning() || (state == TimerState.IDLE && initialTime > 0)) adventure.players().showBossBar(bossBar);
            }
            case TITLE -> {
                Component subtitle = Component.empty();
                if (!rotatingSubtitles.isEmpty()) {
                    long now = System.currentTimeMillis();
                    if (now - lastSubtitleChangeMillis >= subtitleIntervalMillis) {
                        subtitleIndex = (subtitleIndex + 1) % rotatingSubtitles.size();
                        lastSubtitleChangeMillis = now;
                    }
                    subtitle = Component.text(rotatingSubtitles.get(subtitleIndex));
                }
                Title.Times times = Title.Times.of(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(100));
                adventure.players().showTitle(Title.title(timeComponent, subtitle, times));
            }
            case OFF -> {}
        }
    }

    public void addTime(CommandSender sender, String timeString) { if (mode == TimerMode.COUNTUP) { adventure.sender(sender).sendMessage(Component.text("カウントアップモードでは時間を追加できません。", NamedTextColor.RED)); return; } long secondsToAdd = parseTime(timeString); if (secondsToAdd < 0) { adventure.sender(sender).sendMessage(Component.text("無効な時間形式です。", NamedTextColor.RED)); return; } this.currentTime += secondsToAdd; if (state == TimerState.RUNNING || state == TimerState.PAUSED) this.initialTime += secondsToAdd; else this.initialTime = this.currentTime; adventure.sender(sender).sendMessage(Component.text(formatTime(secondsToAdd) + " を追加しました。新しい時間: " + formatTime(currentTime), NamedTextColor.GREEN)); updateDisplay(true); }
    public void setMode(CommandSender sender, String modeString) { if (isRunning()) { adventure.sender(sender).sendMessage(Component.text("タイマーが作動中はモードを変更できません。", NamedTextColor.RED)); return; } try { this.mode = TimerMode.valueOf(modeString.toUpperCase()); adventure.sender(sender).sendMessage(Component.text("タイマーモードを " + mode.name() + " に設定しました。", NamedTextColor.GREEN)); this.currentTime = (mode == TimerMode.COUNTDOWN) ? initialTime : 0; } catch (IllegalArgumentException e) { adventure.sender(sender).sendMessage(Component.text("無効なモードです。「countdown」または「countup」を使用してください。", NamedTextColor.RED)); } }
    public void clearOnEndActions() { onEndActions.get("msg").clear(); onEndActions.get("cmd").clear(); }
    public void listOnEndActions(CommandSender sender, String filterType) { adventure.sender(sender).sendMessage(Component.text("--- 終了時アクション一覧 ---", NamedTextColor.GOLD)); boolean hasActions = false; for (Map.Entry<String, TreeMap<Integer, String>> entry : onEndActions.entrySet()) { String type = entry.getKey(); TreeMap<Integer, String> actions = entry.getValue(); if (filterType != null && !filterType.equalsIgnoreCase(type)) { continue; } if (!actions.isEmpty()) { hasActions = true; adventure.sender(sender).sendMessage(Component.text("【" + type.toUpperCase() + "】", NamedTextColor.AQUA)); actions.forEach((priority, value) -> adventure.sender(sender).sendMessage(Component.text("  優先度 " + priority + ": " + value, NamedTextColor.YELLOW))); } } if (!hasActions) { adventure.sender(sender).sendMessage(Component.text((filterType == null ? "" : filterType.toUpperCase() + "の") + "アクションは設定されていません。", NamedTextColor.GRAY)); } }
    public void setRotatingSubtitles(List<String> subtitles, int intervalSeconds) { this.rotatingSubtitles = new ArrayList<>(subtitles); this.subtitleIntervalMillis = intervalSeconds * 1000L; this.subtitleIndex = 0; this.lastSubtitleChangeMillis = 0; }
    public void setPreStartSeconds(CommandSender sender, String secondsString) { if (isRunning()) { adventure.sender(sender).sendMessage(Component.text("タイマーが作動中は設定を変更できません。", NamedTextColor.RED)); return; } try { int seconds = Integer.parseInt(secondsString); if (seconds < 0) { adventure.sender(sender).sendMessage(Component.text("0以上の数値を入力してください。", NamedTextColor.RED)); return; } this.preStartSeconds = seconds; adventure.sender(sender).sendMessage(Component.text("開始前カウントダウンを " + seconds + " 秒に設定しました。", NamedTextColor.GREEN)); } catch (NumberFormatException e) { adventure.sender(sender).sendMessage(Component.text("数値を入力してください。", NamedTextColor.RED)); } }
    private long parseTime(String timeString) { long totalSeconds = 0; Pattern pattern = Pattern.compile("(\\d+)([hms])"); Matcher matcher = pattern.matcher(timeString.toLowerCase()); if (!matcher.find(0)) { try { return Long.parseLong(timeString); } catch (NumberFormatException e) { return -1; } } matcher.reset(); while (matcher.find()) { int value = Integer.parseInt(matcher.group(1)); String unit = matcher.group(2); switch (unit) { case "h" -> totalSeconds += value * 3600; case "m" -> totalSeconds += value * 60; case "s" -> totalSeconds += value; } } return totalSeconds; }
    private String formatTime(long totalSeconds) { if (totalSeconds < 0) totalSeconds = 0; long hours = totalSeconds / 3600; long minutes = (totalSeconds % 3600) / 60; long seconds = totalSeconds % 60; if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds); return String.format("%02d:%02d", minutes, seconds); }
}