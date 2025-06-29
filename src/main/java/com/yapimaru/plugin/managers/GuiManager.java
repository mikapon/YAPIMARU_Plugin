package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

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

    private enum TeleportMode {
        TELEPORT_TO, SUMMON
    }
    private final Map<UUID, Integer> playerTpGuiPages = new HashMap<>();
    private final Map<UUID, TeleportMode> playerTpModes = new HashMap<>();
    private final Set<UUID> awaitingTpAllTarget = new HashSet<>();

    private static final Map<Material, PotionEffect> TOGGLEABLE_EFFECTS;

    static {
        Map<Material, PotionEffect> effects = new HashMap<>();
        effects.put(Material.GLASS, new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        effects.put(Material.GLOWSTONE_DUST, new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false));
        effects.put(Material.GLOW_BERRIES, new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));
        effects.put(Material.SUGAR, new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 4, false, false));
        effects.put(Material.FEATHER, new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 4, false, false));
        effects.put(Material.GOLDEN_PICKAXE, new PotionEffect(PotionEffectType.HASTE, Integer.MAX_VALUE, 254, false, false));
        effects.put(Material.DIAMOND_SWORD, new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 254, false, false));
        effects.put(Material.GHAST_TEAR, new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 254, false, false));
        effects.put(Material.NETHERITE_CHESTPLATE, new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 254, false, false));
        effects.put(Material.MAGMA_CREAM, new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 254, false, false));
        effects.put(Material.HEART_OF_THE_SEA, new PotionEffect(PotionEffectType.WATER_BREATHING, Integer.MAX_VALUE, 254, false, false));
        TOGGLEABLE_EFFECTS = Collections.unmodifiableMap(effects);
    }

    public GuiManager(YAPIMARU_Plugin plugin, NameManager nameManager, WhitelistManager whitelistManager) {
        this.plugin = plugin;
        this.nameManager = nameManager;
    }

    public void handleInventoryClick(Player player, String title, ItemStack clickedItem, Inventory inventory) {
        String baseTitle = title.split(" §8\\(")[0];
        switch (baseTitle) {
            case MAIN_MENU_TITLE -> handleMainMenuClick(player, clickedItem);
            case TP_MENU_TITLE -> handleTeleportMenuClick(player, clickedItem, inventory);
            case EFFECT_MENU_TITLE -> handleEffectMenuClick(player, clickedItem, inventory);
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
        playerTpModes.put(p.getUniqueId(), TeleportMode.TELEPORT_TO);
        awaitingTpAllTarget.remove(p.getUniqueId());

        playerTpGuiPages.putIfAbsent(p.getUniqueId(), 0);
        int page = playerTpGuiPages.get(p.getUniqueId());

        Map<String, List<Player>> playersByColor = new LinkedHashMap<>();
        List<Player> noColorPlayers = new ArrayList<>();

        for (String colorName : NameManager.WOOL_COLOR_NAMES) {
            playersByColor.put(colorName, new ArrayList<>());
        }

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(p)) continue;
            String playerColor = getPlayerColorName(target);
            if (playerColor != null) {
                playersByColor.get(playerColor).add(target);
            } else {
                noColorPlayers.add(target);
            }
        }
        playersByColor.put("none", noColorPlayers);

        List<ItemStack> guiItems = new ArrayList<>();
        playersByColor.forEach((color, players) -> {
            if (!players.isEmpty()) {
                guiItems.add(createTeamHeader(color));
                players.stream()
                        .sorted(Comparator.comparing(Player::getName))
                        .forEach(player -> guiItems.add(createPlayerHead(p, player)));
            }
        });

        int totalPages = Math.max(1, (int) Math.ceil((double) guiItems.size() / 45.0));
        if (page >= totalPages) {
            page = totalPages > 0 ? totalPages - 1 : 0;
            playerTpGuiPages.put(p.getUniqueId(), page);
        }

        String titleWithPage = TP_MENU_TITLE + " §8(" + (page + 1) + "/" + totalPages + ")";
        Inventory gui = Bukkit.createInventory(null, 54, titleWithPage);

        int startIndex = page * 45;
        for (int i = 0; i < 45; i++) {
            int itemIndex = startIndex + i;
            if (itemIndex < guiItems.size()) {
                gui.setItem(i, guiItems.get(itemIndex));
            }
        }

        if (page > 0) {
            gui.setItem(45, createItem(Material.ARROW, "§e前のページ"));
        }
        if ((page + 1) < totalPages) {
            gui.setItem(53, createItem(Material.ARROW, "§e次のページ"));
        }
        gui.setItem(49, createItem(Material.OAK_DOOR, "§eメインメニューに戻る"));

        TeleportMode currentMode = playerTpModes.getOrDefault(p.getUniqueId(), TeleportMode.TELEPORT_TO);
        if (currentMode == TeleportMode.TELEPORT_TO) {
            gui.setItem(48, createItem(Material.ENDER_PEARL, "§aモード: 自分をTP", "§7クリックで「相手をTP」に切替"));
            gui.setItem(50, createItem(Material.BEACON, "§c全員を自分の場所にTP", "§7自分以外の全員を召喚します"));
        } else {
            gui.setItem(48, createItem(Material.ENDER_EYE, "§bモード: 相手をTP", "§7クリックで「自分をTP」に切替"));
            gui.setItem(50, createItem(Material.NETHER_STAR, "§c全員を選択した人へTP", "§7これをクリック後、", "§7対象のプレイヤーをクリックしてください"));
        }

        p.openInventory(gui);
    }

    public void openEffectMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, EFFECT_MENU_TITLE);

        addEffectButton(gui, 10, Material.GLASS, "透明化");
        addEffectButton(gui, 11, Material.GLOWSTONE_DUST, "暗視");
        addEffectButton(gui, 12, Material.GLOW_BERRIES, "発光");

        addEffectButton(gui, 19, Material.SUGAR, "移動速度上昇 V");
        addEffectButton(gui, 20, Material.FEATHER, "跳躍力上昇 V");

        addEffectButton(gui, 28, Material.GOLDEN_PICKAXE, "採掘速度上昇 MAX");
        addEffectButton(gui, 29, Material.DIAMOND_SWORD, "攻撃力上昇 MAX");
        addEffectButton(gui, 30, Material.GHAST_TEAR, "再生能力 MAX");
        addEffectButton(gui, 31, Material.NETHERITE_CHESTPLATE, "ダメージ耐性 MAX");
        addEffectButton(gui, 32, Material.MAGMA_CREAM, "火炎耐性 MAX");
        addEffectButton(gui, 33, Material.HEART_OF_THE_SEA, "水中呼吸 MAX");

        gui.setItem(40, createItem(Material.GLISTERING_MELON_SLICE, "§d即時回復＆満腹度回復", "§7クリックで即座に効果を発動"));

        gui.setItem(45, createItem(Material.MILK_BUCKET, "§c全エフェクト解除"));
        gui.setItem(53, createItem(Material.OAK_DOOR, "§eメインメニューに戻る"));

        updateEffectButtons(gui, p);

        p.openInventory(gui);
    }

    public void openGamemodeMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, GAMEMODE_MENU_TITLE);
        GameMode sticky = getStickyGameMode(p.getUniqueId());

        addGamemodeButton(gui, 11, GameMode.CREATIVE, Material.GRASS_BLOCK, "クリエイティブ", sticky);
        addGamemodeButton(gui, 12, GameMode.SURVIVAL, Material.IRON_SWORD, "サバイバル", sticky);
        addGamemodeButton(gui, 14, GameMode.ADVENTURE, Material.MAP, "アドベンチャー", sticky);
        addGamemodeButton(gui, 15, GameMode.SPECTATOR, Material.ENDER_EYE, "スペクテイター", sticky);

        gui.setItem(22, createItem(Material.BARRIER, "§cゲームモード固定を解除"));
        gui.setItem(26, createItem(Material.ARROW, "§e戻る"));
        p.openInventory(gui);
    }

    private void handleMainMenuClick(Player p, ItemStack item) {
        if (item == null) return;
        switch (item.getType()) {
            case ENDER_PEARL:
                openTeleportMenu(p);
                break;
            case BEACON:
                openEffectMenu(p);
                break;
            case DIAMOND_PICKAXE:
                openGamemodeMenu(p);
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private void handleTeleportMenuClick(Player p, ItemStack item, Inventory inventory) {
        if (item == null) return;

        if (awaitingTpAllTarget.contains(p.getUniqueId())) {
            if (item.getType() == Material.PLAYER_HEAD && item.getItemMeta() instanceof SkullMeta meta && meta.getOwningPlayer() != null) {
                Player destination = Bukkit.getPlayer(meta.getOwningPlayer().getUniqueId());
                if (destination != null) {
                    p.sendMessage("§c" + destination.getName() + " の元へ、自分以外の全プレイヤーをテレポートさせます...");
                    Bukkit.getOnlinePlayers().stream()
                            .filter(target -> !target.equals(p) && !target.equals(destination))
                            .forEach(target -> target.teleport(destination));
                    p.closeInventory();
                } else {
                    p.sendMessage("§cテレポート先のプレイヤーが見つかりません。");
                }
            } else {
                p.sendMessage("§cキャンセルしました。プレイヤーの頭をクリックしてください。");
            }
            awaitingTpAllTarget.remove(p.getUniqueId());
            return;
        }

        switch(item.getType()) {
            case PLAYER_HEAD:
                if (item.getItemMeta() instanceof SkullMeta meta && meta.getOwningPlayer() != null) {
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
                if (item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("前")) {
                    playerTpGuiPages.put(p.getUniqueId(), Math.max(0, currentPage - 1));
                } else {
                    playerTpGuiPages.put(p.getUniqueId(), currentPage + 1);
                }
                openTeleportMenu(p);
                break;
            case OAK_DOOR:
                openMainMenu(p);
                break;
            case ENDER_PEARL, ENDER_EYE:
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
            case NETHER_STAR:
                awaitingTpAllTarget.add(p.getUniqueId());
                p.sendMessage("§e[全員テレポート] 次にクリックしたプレイヤーに全員をテレポートさせます。");
                p.closeInventory();
                p.openInventory(inventory);
                break;
        }
    }

    private void handleEffectMenuClick(Player p, ItemStack item, Inventory inventory) {
        if (item == null || item.getType() == Material.AIR) return;

        if (TOGGLEABLE_EFFECTS.containsKey(item.getType())) {
            toggleEffect(p, TOGGLEABLE_EFFECTS.get(item.getType()));
            updateEffectButtons(inventory, p);
            return;
        }

        switch(item.getType()) {
            case GLISTERING_MELON_SLICE:
                p.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 10));
                p.setSaturation(20);
                p.setFoodLevel(20);
                p.sendMessage("§d体力を回復し、お腹を満たしました。");
                break;
            case MILK_BUCKET:
                stickyEffects.remove(p.getUniqueId());
                new ArrayList<>(p.getActivePotionEffects()).forEach(eff -> p.removePotionEffect(eff.getType()));
                openEffectMenu(p);
                break;
            case OAK_DOOR:
                openMainMenu(p);
                break;
        }
    }

    private void handleGamemodeMenuClick(Player p, ItemStack item) {
        if (item == null) return;
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
    private ItemStack createPlayerHead(Player viewer, Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(target);
            meta.setDisplayName("§r" + nameManager.getDisplayName(target.getUniqueId()));

            TeleportMode currentMode = playerTpModes.getOrDefault(viewer.getUniqueId(), TeleportMode.TELEPORT_TO);
            if (currentMode == TeleportMode.TELEPORT_TO) {
                meta.setLore(Collections.singletonList("§eクリックでこのプレイヤーへテレポート"));
            } else {
                meta.setLore(Collections.singletonList("§bクリックでこのプレイヤーを召喚"));
            }

            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createTeamHeader(String colorName) {
        if ("none".equals(colorName)) {
            return createItem(Material.LIGHT_GRAY_WOOL, "§lチームなし");
        }
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
        return null;
    }

    private void addEffectButton(Inventory gui, int slot, Material material, String name) {
        ItemStack item = createItem(material, "§b" + name);
        gui.setItem(slot, item);
    }

    private void updateEffectButtons(Inventory gui, Player p) {
        for (int i = 0; i < gui.getSize(); i++) {
            ItemStack item = gui.getItem(i);
            if (item == null || !TOGGLEABLE_EFFECTS.containsKey(item.getType())) {
                continue;
            }

            PotionEffect effect = TOGGLEABLE_EFFECTS.get(item.getType());
            boolean hasEffect = p.hasPotionEffect(effect.getType());

            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("§7状態: " + (hasEffect ? "§aON" : "§cOFF"));
                lore.add("§7クリックで切り替え");
                meta.setLore(lore);
                if (hasEffect) {
                    if(!meta.hasEnchants()) meta.addEnchant(Enchantment.LURE, 1, true);
                } else {
                    meta.removeEnchant(Enchantment.LURE);
                }
                item.setItemMeta(meta);
            }
        }
    }

    private void toggleEffect(Player p, PotionEffect effect) {
        UUID uuid = p.getUniqueId();
        stickyEffects.computeIfAbsent(uuid, k -> new HashSet<>());
        PotionEffectType type = effect.getType();

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
        awaitingTpAllTarget.remove(player.getUniqueId());
    }
}