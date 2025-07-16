package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.LinkedGroup;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class LinkManager {
    private final YAPIMARU_Plugin plugin;
    private final BukkitAudiences adventure;
    private final Map<String, LinkedGroup> linkedGroups = new ConcurrentHashMap<>();
    private final Map<Location, String> chestToGroupMap = new ConcurrentHashMap<>();

    private final Map<UUID, String> pendingAdd = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingRemove = new ConcurrentHashMap<>();
    private final Map<Inventory, LinkedGroup> openVirtualInventories = new ConcurrentHashMap<>();


    private final File linkDir;

    public LinkManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.adventure = plugin.getAdventure();
        this.linkDir = new File(plugin.getDataFolder(), "links");
        if (!linkDir.exists()) {
            linkDir.mkdirs();
        }
        loadGroups();
        startParticleTask();
        startBackupTask();
    }

    public void loadGroups() {
        File[] groupFiles = linkDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (groupFiles == null) return;

        for (File file : groupFiles) {
            try {
                String groupName = file.getName().replace(".yml", "");
                LinkedGroup group = new LinkedGroup(groupName);
                group.load(file);
                linkedGroups.put(groupName, group);
                group.getLinkedChests().forEach(loc -> chestToGroupMap.put(loc, groupName));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load linked group file: " + file.getName(), e);
            }
        }
        plugin.getLogger().info("Loaded " + linkedGroups.size() + " linked groups.");
    }

    public void saveGroup(String name) {
        LinkedGroup group = linkedGroups.get(name);
        if (group == null) return;
        try {
            File file = new File(linkDir, name + ".yml");
            group.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save linked group: " + name, e);
        }
    }

    public void createGroup(Player player, String name) {
        if (linkedGroups.containsKey(name)) {
            adventure.player(player).sendMessage(Component.text("共有グループ「" + name + "」は既に存在します。", NamedTextColor.RED));
            return;
        }
        if (!name.matches("^[a-zA-Z0-9_]+$")) {
            adventure.player(player).sendMessage(Component.text("グループ名には英数字とアンダースコアしか使用できません。", NamedTextColor.RED));
            return;
        }

        LinkedGroup group = new LinkedGroup(name);
        linkedGroups.put(name, group);
        saveGroup(name);
        adventure.player(player).sendMessage(Component.text("共有グループ「" + name + "」を作成しました。", NamedTextColor.GREEN));
        logInteraction(name, player.getName(), "GROUP_CREATE", "Group created");
    }

    public void deleteGroup(Player player, String name) {
        if (!linkedGroups.containsKey(name)) {
            adventure.player(player).sendMessage(Component.text("共有グループ「" + name + "」は存在しません。", NamedTextColor.RED));
            return;
        }

        LinkedGroup group = linkedGroups.remove(name);
        group.getLinkedChests().forEach(chestToGroupMap::remove);

        // Delete files
        new File(linkDir, name + ".yml").delete();
        File logFile = new File(linkDir, "logs/" + name + ".log");
        if(logFile.exists()) logFile.delete();
        File backupDir = new File(linkDir, "backups/" + name);
        if(backupDir.exists()) {
            File[] files = backupDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            backupDir.delete();
        }

        adventure.player(player).sendMessage(Component.text("共有グループ「" + name + "」を削除しました。", NamedTextColor.GOLD));
        logInteraction(name, player.getName(), "GROUP_DELETE", "Group deleted");
    }

    public void listGroups(Player player) {
        if (linkedGroups.isEmpty()) {
            adventure.player(player).sendMessage(Component.text("作成済みの共有グループはありません。", NamedTextColor.YELLOW));
            return;
        }
        adventure.player(player).sendMessage(Component.text("--- 共有グループ一覧 ---", NamedTextColor.GOLD));
        linkedGroups.keySet().forEach(name -> adventure.player(player).sendMessage(Component.text("- " + name, NamedTextColor.AQUA)));
    }

    public void startAddProcess(Player player, String groupName) {
        if (!linkedGroups.containsKey(groupName)) {
            adventure.player(player).sendMessage(Component.text("共有グループ「" + groupName + "」は存在しません。", NamedTextColor.RED));
            return;
        }
        pendingAdd.put(player.getUniqueId(), groupName);
        pendingRemove.remove(player.getUniqueId());
        adventure.player(player).sendMessage(Component.text("リンクしたいチェストを左クリックしてください。", NamedTextColor.AQUA));
    }

    public void startRemoveProcess(Player player) {
        pendingRemove.put(player.getUniqueId(), true);
        pendingAdd.remove(player.getUniqueId());
        adventure.player(player).sendMessage(Component.text("リンクを解除したいチェストを左クリックしてください。", NamedTextColor.AQUA));
    }

    public boolean handleLinkProcess(Player player, Location loc) {
        UUID uuid = player.getUniqueId();
        if (pendingAdd.containsKey(uuid)) {
            String groupName = pendingAdd.remove(uuid);
            addChestToGroup(player, groupName, loc);
            return true;
        }
        if (pendingRemove.containsKey(uuid)) {
            pendingRemove.remove(uuid);
            removeChestFromGroup(player, loc);
            return true;
        }
        return false;
    }

    private void addChestToGroup(Player player, String groupName, Location loc) {
        if (chestToGroupMap.containsKey(loc)) {
            adventure.player(player).sendMessage(Component.text("このチェストは既に他のグループにリンクされています。", NamedTextColor.RED));
            return;
        }
        LinkedGroup group = linkedGroups.get(groupName);
        if (group == null) return;

        group.addChest(loc);
        chestToGroupMap.put(loc, groupName);
        saveGroup(groupName);
        adventure.player(player).sendMessage(Component.text("チェストをグループ「" + groupName + "」にリンクしました。", NamedTextColor.GREEN));
        logInteraction(groupName, player.getName(), "CHEST_ADD", loc.toString());
    }

    private void removeChestFromGroup(Player player, Location loc) {
        String groupName = chestToGroupMap.get(loc);
        if (groupName == null) {
            adventure.player(player).sendMessage(Component.text("このチェストはどのグループにもリンクされていません。", NamedTextColor.YELLOW));
            return;
        }

        if (!player.isOp() && !isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このチェストのリンクを解除する権限がありません。", NamedTextColor.RED));
            return;
        }

        LinkedGroup group = linkedGroups.get(groupName);
        if (group == null) return;

        group.removeChest(loc);
        chestToGroupMap.remove(loc);
        saveGroup(groupName);
        adventure.player(player).sendMessage(Component.text("チェストのリンクを解除しました。", NamedTextColor.GOLD));
        logInteraction(groupName, player.getName(), "CHEST_REMOVE", loc.toString());
    }


    public void openLinkedChest(Player player, Location loc, LinkedGroup group) {
        if (loc != null && group.isReadOnly(loc) && !player.isOp() && !isModerator(player.getUniqueId(), group.getName())) {
            adventure.player(player).sendMessage(Component.text("このチェストは読み取り専用です。", NamedTextColor.YELLOW));
            return;
        }

        Inventory virtualInv = Bukkit.createInventory(null, group.getVirtualInventory().getSize(), "共有: " + group.getName());
        virtualInv.setContents(group.getVirtualInventory().getContents());
        openVirtualInventories.put(virtualInv, group);
        player.openInventory(virtualInv);
    }

    public void handleVirtualInventoryClose(Player player, Inventory closedInventory) {
        if (openVirtualInventories.containsKey(closedInventory)) {
            LinkedGroup group = openVirtualInventories.remove(closedInventory);
            if (group != null) {
                ItemStack[] oldContents = group.getVirtualInventory().getContents();
                ItemStack[] newContents = closedInventory.getContents();

                logInventoryChanges(group.getName(), player.getName(), oldContents, newContents);

                group.getVirtualInventory().setContents(newContents);
                group.sortInventory();
                saveGroup(group.getName());
            }
        }
    }

    public void handleChestBreak(Player player, Location loc) {
        String groupName = chestToGroupMap.get(loc);
        if (groupName == null) return;
        if (!player.isOp() && !isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("リンクされたチェストを破壊する権限がありません。", NamedTextColor.RED));
            return;
        }
        LinkedGroup group = linkedGroups.get(groupName);
        group.removeChest(loc);
        chestToGroupMap.remove(loc);
        saveGroup(groupName);
        adventure.player(player).sendMessage(Component.text("リンクされたチェストを破壊したため、グループから自動的に解除しました。", NamedTextColor.GOLD));
        logInteraction(groupName, player.getName(), "CHEST_BREAK", loc.toString());
    }

    public void handleHopperMove(InventoryMoveItemEvent event) {
        Location sourceLoc = event.getSource().getLocation();
        Location destLoc = event.getDestination().getLocation();

        LinkedGroup sourceGroup = getGroupFromChestLocation(sourceLoc);
        LinkedGroup destGroup = getGroupFromChestLocation(destLoc);

        if (sourceGroup == null && destGroup != null) {
            event.setCancelled(true);
            ItemStack item = event.getItem();
            HashMap<Integer, ItemStack> remaining = destGroup.getVirtualInventory().addItem(item.clone());
            if (remaining.isEmpty()) {
                event.getSource().removeItem(item.clone());
                logInteraction(destGroup.getName(), "HOPPER", "ITEM_IN", item.getType() + " x" + item.getAmount());
                saveGroup(destGroup.getName());
            }
        }
        else if (sourceGroup != null && destGroup == null) {
            event.setCancelled(true);
            ItemStack item = event.getItem();
            HashMap<Integer, ItemStack> remaining = event.getDestination().addItem(item.clone());
            if (remaining.isEmpty()) {
                sourceGroup.getVirtualInventory().removeItem(item.clone());
                logInteraction(sourceGroup.getName(), "HOPPER", "ITEM_OUT", item.getType() + " x" + item.getAmount());
                saveGroup(sourceGroup.getName());
            }
        }
        else if (sourceGroup != null && destGroup != null) {
            event.setCancelled(true);
        }
    }


    public LinkedGroup getGroupFromChestLocation(Location loc) {
        if (loc == null) return null;
        String groupName = chestToGroupMap.get(loc.getBlock().getLocation());
        return groupName != null ? linkedGroups.get(groupName) : null;
    }

    public void displayGroupInfo(Player player, String name) {
        LinkedGroup group = linkedGroups.get(name);
        if (group == null) {
            adventure.player(player).sendMessage(Component.text("共有グループ「" + name + "」は存在しません。", NamedTextColor.RED));
            return;
        }
        adventure.player(player).sendMessage(Component.text("--- グループ情報: " + name + " ---", NamedTextColor.GOLD));
        adventure.player(player).sendMessage(Component.text("リンクされたチェストの数: " + group.getLinkedChests().size(), NamedTextColor.AQUA));
        adventure.player(player).sendMessage(Component.text("管理者: ", NamedTextColor.AQUA)
                .append(Component.text(group.getModerators().stream().map(uuid -> Bukkit.getOfflinePlayer(uuid).getName()).collect(Collectors.joining(", ")), NamedTextColor.WHITE)));
        adventure.player(player).sendMessage(Component.text("チェスト座標:", NamedTextColor.AQUA));
        group.getLinkedChests().forEach(loc -> {
            String readOnlyStatus = group.isReadOnly(loc) ? " (読み取り専用)" : "";
            adventure.player(player).sendMessage(Component.text(String.format("  - W: %s, X: %d, Y: %d, Z: %d%s",
                    loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), readOnlyStatus), NamedTextColor.WHITE));
        });
    }

    public void openVirtualInventory(Player player, String name) {
        LinkedGroup group = linkedGroups.get(name);
        if (group == null) {
            adventure.player(player).sendMessage(Component.text("共有グループ「" + name + "」は存在しません。", NamedTextColor.RED));
            return;
        }
        openLinkedChest(player, null, group);
    }

    public boolean toggleReadOnly(Player player, Location loc) {
        String groupName = chestToGroupMap.get(loc);
        if (groupName == null) return false;

        if (!player.isOp() && !isModerator(player.getUniqueId(), groupName)) {
            adventure.player(player).sendMessage(Component.text("このチェストの設定を変更する権限がありません。", NamedTextColor.RED));
            return true;
        }
        LinkedGroup group = linkedGroups.get(groupName);
        boolean isNowReadOnly = group.toggleReadOnly(loc);
        saveGroup(groupName);

        adventure.player(player).sendMessage(Component.text("このチェストを「" + (isNowReadOnly ? "読み取り専用" : "書き込み可能") + "」に設定しました。", NamedTextColor.AQUA));
        logInteraction(groupName, player.getName(), "READ_ONLY_TOGGLE", loc + " -> " + isNowReadOnly);
        return true;
    }

    public void addModerator(Player sender, String groupName, Player target) {
        LinkedGroup group = linkedGroups.get(groupName);
        if (group == null) return;
        if (group.addModerator(target.getUniqueId())) {
            saveGroup(groupName);
            adventure.player(sender).sendMessage(Component.text(target.getName() + "をグループ「" + groupName + "」の管理者に任命しました。", NamedTextColor.GREEN));
            logInteraction(groupName, sender.getName(), "MOD_ADD", target.getName());
        } else {
            adventure.player(sender).sendMessage(Component.text(target.getName() + "は既にこのグループの管理者です。", NamedTextColor.YELLOW));
        }
    }

    public void removeModerator(Player sender, String groupName, Player target) {
        LinkedGroup group = linkedGroups.get(groupName);
        if (group == null) return;
        if (group.removeModerator(target.getUniqueId())) {
            saveGroup(groupName);
            adventure.player(sender).sendMessage(Component.text(target.getName() + "をグループ「" + groupName + "」の管理者から解任しました。", NamedTextColor.GOLD));
            logInteraction(groupName, sender.getName(), "MOD_REMOVE", target.getName());
        } else {
            adventure.player(sender).sendMessage(Component.text(target.getName() + "はこのグループの管理者ではありません。", NamedTextColor.YELLOW));
        }
    }

    public boolean isModerator(UUID uuid, String groupName) {
        LinkedGroup group = linkedGroups.get(groupName);
        return group != null && group.isModerator(uuid);
    }

    public boolean canManageAnyGroup(UUID uuid) {
        return linkedGroups.values().stream().anyMatch(g -> g.isModerator(uuid));
    }


    public List<String> getManageableGroupNames(Player player) {
        if (player.isOp()) {
            return new ArrayList<>(linkedGroups.keySet());
        }
        return linkedGroups.values().stream()
                .filter(g -> g.isModerator(player.getUniqueId()))
                .map(LinkedGroup::getName)
                .collect(Collectors.toList());
    }

    private void startParticleTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    chestToGroupMap.forEach((loc, groupName) -> {
                        if (loc.getWorld().equals(player.getWorld()) && loc.distanceSquared(player.getLocation()) < 100) {
                            player.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0.5, 0.5, 0.5), 1, 0.2, 0.2, 0.2, 0);
                        }
                    });
                }
            }
        }.runTaskTimer(plugin, 100L, 20L);
    }

    private void startBackupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                linkedGroups.forEach((name, group) -> {
                    File backupDir = new File(linkDir, "backups/" + name);
                    if (!backupDir.exists()) {
                        backupDir.mkdirs();
                    }
                    try {
                        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                        File backupFile = new File(backupDir, timestamp + ".yml");
                        group.save(backupFile);

                        File[] backups = backupDir.listFiles();
                        if (backups != null && backups.length > 24) {
                            Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
                            for (int i = 0; i < backups.length - 24; i++) {
                                backups[i].delete();
                            }
                        }
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to create backup for group " + name, e);
                    }
                });
            }
        }.runTaskTimerAsynchronously(plugin, 20 * 3600, 20 * 3600);
    }

    private void logInteraction(String groupName, String actor, String action, String details) {
        new BukkitRunnable() {
            @Override
            public void run() {
                File logDir = new File(linkDir, "logs");
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }
                File logFile = new File(logDir, groupName + ".log");
                try (java.io.FileWriter writer = new java.io.FileWriter(logFile, true)) {
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    writer.write(String.format("[%s] [%s] %s: %s\n", timestamp, actor, action, details));
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not write to log file for group " + groupName, e);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void logInventoryChanges(String groupName, String playerName, ItemStack[] oldContents, ItemStack[] newContents) {
        for (int i = 0; i < oldContents.length; i++) {
            ItemStack oldItem = oldContents[i];
            ItemStack newItem = newContents[i];

            if (!Objects.equals(oldItem, newItem)) {
                if (oldItem == null && newItem != null) {
                    logInteraction(groupName, playerName, "ITEM_ADD", String.format("Slot %d: %s x%d", i, newItem.getType(), newItem.getAmount()));
                } else if (oldItem != null && newItem == null) {
                    logInteraction(groupName, playerName, "ITEM_REMOVE", String.format("Slot %d: %s x%d", i, oldItem.getType(), oldItem.getAmount()));
                } else if (oldItem != null && newItem != null) {
                    logInteraction(groupName, playerName, "ITEM_CHANGE", String.format("Slot %d: %s -> %s", i, oldItem.toString(), newItem.toString()));
                }
            }
        }
    }
}