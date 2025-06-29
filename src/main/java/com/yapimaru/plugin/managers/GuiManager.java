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

    public static final String MAIN_MENU_TITLE = "クリエイターメニュー";
    public static final String TP_MENU_TITLE = "クリエイターメニュー - テレポート";
    public static final String EFFECT_MENU_TITLE = "クリエイターメニュー - エフェクト";
    public static final String GAMEMODE_MENU_TITLE = "クリエイターメニュー - ゲームモード";

    private final Map<UUID, Set<PotionEffect>> stickyEffects = new HashMap<>();
    private final Map<UUID, GameMode> stickyGameModes = new HashMap<>();

    // --- テレポートGUI用の状態管理 ---
    private enum TeleportMode {
        TELEPORT_TO, SUMMON
    }
    private final Map<UUID, Integer> playerTpGuiPages = new HashMap<>();
    private final Map<UUID, TeleportMode> playerTpModes = new HashMap<>();

    public GuiManager(YAPIMARU_Plugin plugin, NameManager nameManager, WhitelistManager whitelistManager) {
        this.plugin = plugin;
        this.nameManager = nameManager;
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
        playerTpGuiPages.putIfAbsent(p.getUniqueId(), 0);
        int page = playerTpGuiPages.get(p.getUniqueId());

        // --- データ準備 ---
        Map<String, List<Player>> playersByColor = new LinkedHashMap<>();
        List<Player> noColorPlayers = new ArrayList<>();

        // 色ごとのリストを初期化
        for (String colorName : NameManager.WOOL_COLOR_NAMES) {
            playersByColor.put(colorName, new ArrayList<>());
        }

        // プレイヤーを色で分類
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(p)) continue; // 自分自身は除外

            String playerColor = getPlayerColorName(target);
            if (playerColor != null) {
                playersByColor.get(playerColor).add(target);
            } else {
                noColorPlayers.add(target);
            }
        }
        playersByColor.put("none", noColorPlayers); // 色なしグループを追加

        // --- GUIアイテムリスト生成 ---
        List<ItemStack> guiItems = new ArrayList<>();
        playersByColor.forEach((color, players) -> {
            if (!players.isEmpty()) {
                // ヘッダー（羊毛）を追加
                guiItems.add(createTeamHeader(color));
                // プレイヤーヘッドを追加
                players.stream()
                        .sorted(Comparator.comparing(Player::getName))
                        .forEach(player -> guiItems.add(createPlayerHead(player)));
            }
        });

        // --- GUI構築 ---
        int totalPages = (int) Math.ceil((double) guiItems.size() / 45.0);
        if (totalPages > 0 && page >= totalPages) {
            page = totalPages - 1;
            playerTpGuiPages.put(p.getUniqueId(), page);
        }

        Inventory gui = Bukkit.createInventory(null, 54, TP_MENU_TITLE);

        // ページに合わせてアイテムを配置
        int startIndex = page * 45;
        for (int i = 0; i < 45; i++) {
            int itemIndex = startIndex + i;
            if (itemIndex < guiItems.size()) {
                gui.setItem(i, guiItems.get(itemIndex));
            }
        }

        // --- 下部コントロールパネル ---
        if (page > 0) {
            gui.setItem(45, createItem(Material.ARROW, "§e前のページ", "§7" + page + "/" + totalPages));
        }
        if ((page + 1) < totalPages) {
            gui.setItem(53, createItem(Material.ARROW, "§e次のページ", "§7" + (page + 2) + "/" + totalPages));
        }
        gui.setItem(49, createItem(Material.OAK_DOOR, "§eメインメニューに戻る"));

        // モード切替ボタン
        TeleportMode currentMode = playerTpModes.getOrDefault(p.getUniqueId(), TeleportMode.TELEPORT_TO);
        if (currentMode == TeleportMode.TELEPORT_TO) {
            gui.setItem(48, createItem(Material.ENDER_PEARL, "§aモード: 自分をTP", "§7クリックで「相手をTP」に切替"));
        } else {
            gui.setItem(48, createItem(Material.ENDER_EYE, "§bモード: 相手をTP", "§7クリックで「自分をTP」に切替"));
        }

        // 全員TPボタン
        gui.setItem(50, createItem(Material.BEACON, "§c全員を自分の場所にTP", "§7自分以外の全員を召喚します"));

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

    @SuppressWarnings("deprecation")
    private void handleTeleportMenuClick(Player p, ItemStack item) {
        switch(item.getType()) {
            case PLAYER_HEAD:
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                if (meta != null && meta.getOwningPlayer() != null) {
                    Player target = Bukkit.getPlayer(meta.getOwningPlayer().getUniqueId());
                    if (target != null) {
                        TeleportMode currentMode = playerTpModes.getOrDefault(p.getUniqueId(), TeleportMode.TELEPORT_TO);
                        if (currentMode == TeleportMode.TELEPORT_TO) {
                            p.teleport(target.getLocation());
                            p.sendMessage("§a" + target.getName() + "にテレポートしました。");
                        } else {
                            target.teleport(p.getLocation());
                            p.sendMessage("§b" + target.getName() + "をあなたの場所に召喚しました。");
                        }
                        p.closeInventory();
                    } else {
                        p.sendMessage("§cテレポート先のプレイヤーが見つかりません。");
                    }
                }
                break;
            case ARROW:
                int currentPage = playerTpGuiPages.getOrDefault(p.getUniqueId(), 0);
                if (item.getItemMeta().getDisplayName().contains("前")) {
                    playerTpGuiPages.put(p.getUniqueId(), Math.max(0, currentPage - 1));
                } else {
                    playerTpGuiPages.put(p.getUniqueId(), currentPage + 1);
                }
                openTeleportMenu(p);
                break;
            case OAK_DOOR:
                openMainMenu(p);
                break;
            case ENDER_PEARL:
            case ENDER_EYE:
                TeleportMode currentMode = playerTpModes.getOrDefault(p.getUniqueId(), TeleportMode.TELEPORT_TO);
                playerTpModes.put(p.getUniqueId(), currentMode == TeleportMode.TELEPORT_TO ? TeleportMode.SUMMON : TeleportMode.TELEPORT_TO);
                openTeleportMenu(p);
                break;
            case BEACON:
                Bukkit.getOnlinePlayers().stream()
                        .filter(target -> !target.equals(p))
                        .forEach(target -> target.teleport(p.getLocation()));
                p.sendMessage("§c自分以外の全プレイヤーをあなたの場所に召喚しました。");
                p.closeInventory();
                break;
        }
    }

    private void handleEffectMenuClick(Player p, ItemStack item) {
        switch (item.getType()) {
            case POTION, LINGERING_POTION, SPLASH_POTION, LIME_DYE, GRAY_DYE -> {
                PotionMeta meta = (PotionMeta) item.getItemMeta();
                if (meta != null && meta.getBasePotionType() != null) {
                    PotionEffectType type = meta.getBasePotionType().getEffectType();
                    if (type != null) {
                        int amp = type == PotionEffectType.SPEED || type == PotionEffectType.JUMP_BOOST ? 1 : (type == PotionEffectType.RESISTANCE ? 4 : 0);
                        toggleEffect(p, type, amp);
                        openEffectMenu(p);
                    }
                }
            }
            case MILK_BUCKET -> {
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

    @SuppressWarnings("deprecation")
    private ItemStack createPlayerHead(Player p) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if(meta == null) return head;
        meta.setOwningPlayer(p);
        meta.setDisplayName("§r" + nameManager.getDisplayName(p.getUniqueId()));

        TeleportMode currentMode = playerTpModes.getOrDefault(p.getUniqueId(), TeleportMode.TELEPORT_TO);
        if (currentMode == TeleportMode.TELEPORT_TO) {
            meta.setLore(Collections.singletonList("§eクリックでこのプレイヤーへテレポート"));
        } else {
            meta.setLore(Collections.singletonList("§bクリックでこのプレイヤーを召喚"));
        }

        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createTeamHeader(String colorName) {
        Material woolMaterial;
        try {
            woolMaterial = Material.valueOf(colorName.toUpperCase() + "_WOOL");
        } catch (IllegalArgumentException e) {
            woolMaterial = Material.WHITE_WOOL;
        }
        return createItem(woolMaterial, "§l" + colorName.toUpperCase() + " Team");
    }

    private String getPlayerColorName(Player player) {
        for (String colorName : NameManager.WOOL_COLOR_NAMES) {
            if (player.getScoreboardTags().contains(colorName)) {
                return colorName;
            }
        }
        return null; // 色が設定されていない場合
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
            if (pmeta != null) {
                pmeta.setBasePotionType(PotionType.getByEffect(type));
                item.setItemMeta(pmeta);
            }
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§7状態: " + (p.hasPotionEffect(type) ? "§aON" : "§cOFF"));
            lore.add("§7クリックで切り替え");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

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

    public Set<PotionEffect> getStickyEffectsForPlayer(UUID uuid) {
        return stickyEffects.get(uuid);
    }

    public void handlePlayerQuit(Player player) {
        stickyEffects.remove(player.getUniqueId());
        stickyGameModes.remove(player.getUniqueId());
        playerTpGuiPages.remove(player.getUniqueId());
        playerTpModes.remove(player.getUniqueId());
    }
}