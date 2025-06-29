package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.*;
import java.util.stream.Collectors;

public class GuiManager {

    private final YAPIMARU_Plugin plugin;
    private final NameManager nameManager;
    private final WhitelistManager whitelistManager;

    public static final String MAIN_MENU_TITLE = "クリエイターメニュー";
    public static final String TP_MENU_TITLE = "クリエイターメニュー - テレポート";
    public static final String EFFECT_MENU_TITLE = "クリエイターメニュー - エフェクト";
    public static final String GAMEMODE_MENU_TITLE = "クリエイターメニュー - ゲームモード";

    // ★★★ プレイヤーが設定したエフェクトを記憶するためのMapを追加 ★★★
    private final Map<UUID, Set<PotionEffect>> stickyEffects = new HashMap<>();
    private final Map<UUID, GameMode> stickyGameModes = new HashMap<>();

    public GuiManager(YAPIMARU_Plugin plugin, NameManager nameManager, WhitelistManager whitelistManager) {
        this.plugin = plugin;
        this.nameManager = nameManager;
        this.whitelistManager = whitelistManager;
    }

    public void handleInventoryClick(Player player, String title, ItemStack clickedItem) {
        switch (title) {
            case MAIN_MENU_TITLE -> handleMainMenuClick(player, clickedItem);
            case TP_MENU_TITLE -> handleTeleportMenuClick(player, clickedItem);
            case EFFECT_MENU_TITLE -> handleEffectMenuClick(player, clickedItem);
            case GAMEMODE_MENU_TITLE -> handleGamemodeMenuClick(player, clickedItem);
        }
    }

    public void openMainMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, MAIN_MENU_TITLE);
        gui.setItem(11, createItem(Material.ENDER_PEARL, "§aテレポート"));
        gui.setItem(13, createItem(Material.BEACON, "§bエフェクト"));
        gui.setItem(15, createItem(Material.DIAMOND_PICKAXE, "§eゲームモード"));
        p.openInventory(gui);
    }

    public void openTeleportMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, TP_MENU_TITLE);

        List<Player> players = Bukkit.getOnlinePlayers().stream()
                .filter(pl -> !pl.getUniqueId().equals(p.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName))
                .collect(Collectors.toList());

        for (int i = 0; i < Math.min(players.size(), 53); i++) {
            Player target = players.get(i);
            gui.setItem(i, createPlayerHead(target, "§eクリックで" + target.getName() + "にテレポート"));
        }
        gui.setItem(53, createItem(Material.ARROW, "§e戻る"));
        p.openInventory(gui);
    }

    public void openEffectMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, EFFECT_MENU_TITLE);
        addEffectButton(gui, 10, p, PotionEffectType.SPEED, 1, "移動速度上昇");
        addEffectButton(gui, 11, p, PotionEffectType.JUMP_BOOST, 1, "跳躍力上昇");
        addEffectButton(gui, 12, p, PotionEffectType.INVISIBILITY, 0, "透明化");
        addEffectButton(gui, 13, p, PotionEffectType.NIGHT_VISION, 0, "暗視");
        addEffectButton(gui, 14, p, PotionEffectType.WATER_BREATHING, 0, "水中呼吸");
        addEffectButton(gui, 15, p, PotionEffectType.RESISTANCE, 4, "ダメージ耐性 (V)");
        addEffectButton(gui, 16, p, PotionEffectType.GLOWING, 0, "発光");

        gui.setItem(18, createItem(Material.MILK_BUCKET, "§c全エフェクト解除"));
        gui.setItem(26, createItem(Material.ARROW, "§e戻る"));
        p.openInventory(gui);
    }

    public void openGamemodeMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, GAMEMODE_MENU_TITLE);
        GameMode sticky = getStickyGameMode(p.getUniqueId());
        addGamemodeButton(gui, 11, GameMode.CREATIVE, Material.GRASS_BLOCK, "クリエイティブ", sticky);
        addGamemodeButton(gui, 12, GameMode.SURVIVAL, Material.IRON_SWORD, "サバイバル", sticky);
        addGamemodeButton(gui, 14, GameMode.ADVENTURE, Material.MAP, "アドベンチャー", sticky);
        addGamemodeButton(gui, 15, GameMode.SPECTATOR, Material.ENDER_EYE, "スペクテイター", sticky);

        gui.setItem(18, createItem(Material.BARRIER, "§cゲームモード固定を解除"));
        gui.setItem(26, createItem(Material.ARROW, "§e戻る"));
        p.openInventory(gui);
    }

    private void handleMainMenuClick(Player p, ItemStack item) {
        switch (item.getType()) {
            case ENDER_PEARL -> openTeleportMenu(p);
            case BEACON -> openEffectMenu(p);
            case DIAMOND_PICKAXE -> openGamemodeMenu(p);
        }
    }

    private void handleTeleportMenuClick(Player p, ItemStack item) {
        if (item.getType() == Material.PLAYER_HEAD) {
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            if (meta != null && meta.getOwningPlayer() != null) {
                Player target = Bukkit.getPlayer(meta.getOwningPlayer().getUniqueId());
                if (target != null) {
                    p.teleport(target.getLocation());
                    p.sendMessage("§a" + target.getName() + "にテレポートしました。");
                    p.closeInventory();
                } else {
                    p.sendMessage("§cテレポート先のプレイヤーが見つかりません。");
                }
            }
        } else if (item.getType() == Material.ARROW) {
            openMainMenu(p);
        }
    }

    private void handleEffectMenuClick(Player p, ItemStack item) {
        switch (item.getType()) {
            case POTION, LINGERING_POTION, SPLASH_POTION, LIME_DYE, GRAY_DYE -> {
                PotionMeta meta = (PotionMeta) item.getItemMeta();
                if (meta != null) {
                    PotionEffectType type = meta.getBasePotionType().getEffectType();
                    if (type != null) {
                        int amp = type == PotionEffectType.SPEED || type == PotionEffectType.JUMP_BOOST ? 1 : (type == PotionEffectType.RESISTANCE ? 4 : 0);
                        toggleEffect(p, type, amp);
                        openEffectMenu(p);
                    }
                }
            }
            case MILK_BUCKET -> {
                // ★★★ 記憶しているエフェクトもクリア ★★★
                stickyEffects.remove(p.getUniqueId());
                new ArrayList<>(p.getActivePotionEffects()).forEach(eff -> p.removePotionEffect(eff.getType()));
                openEffectMenu(p);
            }
            case ARROW -> openMainMenu(p);
        }
    }

    private void handleGamemodeMenuClick(Player p, ItemStack item) {
        GameMode targetMode = null;
        switch (item.getType()) {
            case GRASS_BLOCK -> targetMode = GameMode.CREATIVE;
            case IRON_SWORD -> targetMode = GameMode.SURVIVAL;
            case MAP -> targetMode = GameMode.ADVENTURE;
            case ENDER_EYE -> targetMode = GameMode.SPECTATOR;
            case ARROW -> { openMainMenu(p); return; }
            case BARRIER -> {
                stickyGameModes.remove(p.getUniqueId());
                p.sendMessage("§eゲームモードの固定を解除しました。");
                openGamemodeMenu(p);
                return;
            }
        }
        if (targetMode != null) {
            p.setGameMode(targetMode);
            stickyGameModes.put(p.getUniqueId(), targetMode);
            p.sendMessage("§aゲームモードを" + targetMode.name() + "に固定しました。");
            openGamemodeMenu(p);
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPlayerHead(Player p, String... lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if(meta == null) return head;
        meta.setOwningPlayer(p);
        meta.setDisplayName("§r" + nameManager.getDisplayName(p.getUniqueId()));
        meta.setLore(Arrays.asList(lore));
        head.setItemMeta(meta);
        return head;
    }

    private void addEffectButton(Inventory gui, int slot, Player p, PotionEffectType type, int amp, String name) {
        Material mat;
        if (type == PotionEffectType.RESISTANCE || type == PotionEffectType.GLOWING) {
            mat = p.hasPotionEffect(type) ? Material.LIME_DYE : Material.GRAY_DYE;
        } else {
            mat = Material.POTION;
        }

        ItemStack item = createItem(mat, "§b" + name);
        if(mat == Material.POTION) {
            PotionMeta pmeta = (PotionMeta) item.getItemMeta();
            pmeta.setBasePotionType(PotionType.getByEffect(type));
            item.setItemMeta(pmeta);
        }

        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add("§7状態: " + (p.hasPotionEffect(type) ? "§aON" : "§cOFF"));
        lore.add("§7クリックで切り替え");
        meta.setLore(lore);
        item.setItemMeta(meta);

        gui.setItem(slot, item);
    }

    private void toggleEffect(Player p, PotionEffectType type, int amp) {
        UUID uuid = p.getUniqueId();
        stickyEffects.computeIfAbsent(uuid, k -> new HashSet<>());
        PotionEffect effect = new PotionEffect(type, -1, amp, true, false);

        if (p.hasPotionEffect(type)) {
            p.removePotionEffect(type);
            stickyEffects.get(uuid).removeIf(e -> e.getType().equals(type));
        } else {
            p.addPotionEffect(effect);
            stickyEffects.get(uuid).add(effect);
        }
    }

    private void addGamemodeButton(Inventory gui, int slot, GameMode gm, Material mat, String name, GameMode sticky) {
        ItemStack item = createItem(mat, "§e" + name, "§7クリックで" + name + "モードに固定します。");
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (sticky == gm) {
                meta.addEnchant(Enchantment.LURE, 1, true);
                List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("§6(現在このモードに固定中)");
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        gui.setItem(slot, item);
    }

    public GameMode getStickyGameMode(UUID uuid) {
        return stickyGameModes.get(uuid);
    }

    // ★★★ リスポーン時に呼び出すためのメソッドを追加 ★★★
    public Set<PotionEffect> getStickyEffectsForPlayer(UUID uuid) {
        return stickyEffects.get(uuid);
    }

    // ★★★ プレイヤー退出時に呼び出すメソッドを追加 ★★★
    public void handlePlayerQuit(Player player) {
        stickyEffects.remove(player.getUniqueId());
        stickyGameModes.remove(player.getUniqueId());
    }
}
