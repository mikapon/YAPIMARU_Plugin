package com.yapimaru.plugin.logic;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.ZipInputStream;

public class LogRestoreExecutor implements Listener {

    private final YAPIMARU_Plugin plugin;
    private final CommandSender sender;
    private final File backupDir;
    private final File participantDir;
    private final File dischargeDir;

    private static final Map<UUID, RestoreSession> restoreSessions = new HashMap<>();

    public LogRestoreExecutor(YAPIMARU_Plugin plugin, CommandSender sender) {
        this.plugin = plugin;
        this.sender = sender;
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        File participantInfoDir = new File(plugin.getDataFolder(), "Participant_Information");
        this.participantDir = new File(participantInfoDir, "participant");
        this.dischargeDir = new File(participantInfoDir, "discharge");
    }

    public void execute() {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみが実行できます。");
            return;
        }
        Player player = (Player) sender;

        File[] backups = backupDir.listFiles((dir, name) -> name.endsWith(".zip"));
        if (backups == null || backups.length == 0) {
            sender.sendMessage("§c利用可能なバックアップが見つかりません。");
            return;
        }
        Arrays.sort(backups, Comparator.comparing(File::getName).reversed());

        sender.sendMessage("§a--- 利用可能なバックアップ ---");
        for (int i = 0; i < backups.length; i++) {
            sender.sendMessage("§e" + (i + 1) + ": §f" + backups[i].getName());
        }
        sender.sendMessage("§a復元したいバックアップの番号をチャットで入力してください。(60秒以内)");
        sender.sendMessage("§cキャンセルするには 'cancel' と入力してください。");

        restoreSessions.put(player.getUniqueId(), new RestoreSession(player, backups));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!restoreSessions.containsKey(player.getUniqueId())) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                RestoreSession session = restoreSessions.get(player.getUniqueId());
                if(session == null) return;

                event.setCancelled(true);
                String message = event.getMessage();

                if (message.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§e復元プロセスをキャンセルしました。");
                    restoreSessions.remove(player.getUniqueId());
                    HandlerList.unregisterAll(LogRestoreExecutor.this);
                    return;
                }

                if(session.isAwaitingConfirmation()) {
                    if(message.equalsIgnoreCase("confirm")) {
                        player.sendMessage("§6復元を開始します...");
                        performRestore(session.getSelectedBackup());
                    } else {
                        player.sendMessage("§c確認文字列が一致しません。復元プロセスをキャンセルしました。");
                    }
                    restoreSessions.remove(player.getUniqueId());
                    HandlerList.unregisterAll(LogRestoreExecutor.this);
                    return;
                }

                try {
                    int choice = Integer.parseInt(message) - 1;
                    if (choice >= 0 && choice < session.getBackups().length) {
                        session.setSelectedBackup(session.getBackups()[choice]);
                        player.sendMessage("§e----------------------------------------");
                        player.sendMessage("§6本当に " + session.getSelectedBackup().getName() + " から復元しますか？");
                        player.sendMessage("§c警告: 現在の全ての参加者データが上書きされ、失われます！");
                        player.sendMessage("§cこの操作は取り消せません。");
                        player.sendMessage("§eよろしければ、チャットで 'confirm' と入力してください。");
                        player.sendMessage("§e----------------------------------------");
                        session.setAwaitingConfirmation(true);
                    } else {
                        player.sendMessage("§c無効な番号です。プロセスをキャンセルしました。");
                        restoreSessions.remove(player.getUniqueId());
                        HandlerList.unregisterAll(LogRestoreExecutor.this);
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage("§c無効な入力です。数字で番号を入力してください。プロセスをキャンセルしました。");
                    restoreSessions.remove(player.getUniqueId());
                    HandlerList.unregisterAll(LogRestoreExecutor.this);
                }
            }
        }.runTask(plugin);
    }

    private void performRestore(File backupFile) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    deleteDirectory(participantDir);
                    deleteDirectory(dischargeDir);
                    participantDir.mkdirs();
                    dischargeDir.mkdirs();

                    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(backupFile))) {
                        byte[] buffer = new byte[1024];
                        java.util.zip.ZipEntry zipEntry;
                        while ((zipEntry = zis.getNextEntry()) != null) {
                            File destDir = zipEntry.getName().startsWith("discharge/") ? dischargeDir : participantDir;
                            String entryName = zipEntry.getName().substring(zipEntry.getName().indexOf('/') + 1);
                            File newFile = new File(destDir, entryName);

                            if (!newFile.getCanonicalPath().startsWith(participantDir.getCanonicalPath()) && !newFile.getCanonicalPath().startsWith(dischargeDir.getCanonicalPath())) {
                                throw new IOException("Zip Slip vulnerability detected!");
                            }

                            if (zipEntry.isDirectory()) {
                                newFile.mkdirs();
                            } else {
                                new File(newFile.getParent()).mkdirs();
                                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                                    int len;
                                    while ((len = zis.read(buffer)) > 0) {
                                        fos.write(buffer, 0, len);
                                    }
                                }
                            }
                            zis.closeEntry();
                        }
                    }

                    plugin.getParticipantManager().reloadAllParticipants();
                    plugin.getWhitelistManager().syncAllowedPlayers();

                    sender.sendMessage("§aバックアップ " + backupFile.getName() + " からの復元が正常に完了しました。");

                } catch (Exception e) {
                    sender.sendMessage("§c復元中にエラーが発生しました。詳細はコンソールを確認してください。");
                    plugin.getLogger().log(Level.SEVERE, "バックアップの復元に失敗しました。", e);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directory.delete();
    }

    private static class RestoreSession {
        private final Player player;
        private final File[] backups;
        private File selectedBackup;
        private boolean awaitingConfirmation = false;

        RestoreSession(Player player, File[] backups) { this.player = player; this.backups = backups; }
        public File[] getBackups() { return backups; }
        public File getSelectedBackup() { return selectedBackup; }
        public void setSelectedBackup(File file) { this.selectedBackup = file; }
        public boolean isAwaitingConfirmation() { return awaitingConfirmation; }
        public void setAwaitingConfirmation(boolean val) { this.awaitingConfirmation = val; }
    }
}