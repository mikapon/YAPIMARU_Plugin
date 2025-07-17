package com.yapimaru.plugin.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class LinkedGroup {
    private final String name;
    private Inventory virtualInventory;
    private int size;
    private final Set<Location> linkedChests = new HashSet<>();
    private final List<String> chestLocationStrings = new ArrayList<>(); // ワールドロード前の一時保管場所
    private final Map<Location, Boolean> readOnlyChests = new HashMap<>();
    private final Map<Location, Boolean> breakableChests = new HashMap<>();
    private final Set<UUID> moderators = new HashSet<>();
    private boolean autoSort = true;

    public LinkedGroup(String name) {
        this.name = name;
        this.size = 27; // Default to single chest size
        this.virtualInventory = Bukkit.createInventory(null, this.size, "Virtual " + name);
    }

    public String getName() { return name; }
    public Inventory getVirtualInventory() { return virtualInventory; }
    public int getSize() { return size; }
    public Set<Location> getLinkedChests() { return Collections.unmodifiableSet(linkedChests); }
    public Set<UUID> getModerators() { return Collections.unmodifiableSet(moderators); }
    public boolean isAutoSortEnabled() { return autoSort; }
    public void setAutoSort(boolean enabled) { this.autoSort = enabled; }


    public void expandToLarge() {
        if (this.size == 54) return;
        this.size = 54;
        Inventory newInventory = Bukkit.createInventory(null, this.size, "Virtual " + name);
        newInventory.setContents(this.virtualInventory.getContents());
        this.virtualInventory = newInventory;
    }

    public void addChest(Location loc) {
        linkedChests.add(loc);
        if (!chestLocationStrings.contains(locationToString(loc))) {
            chestLocationStrings.add(locationToString(loc));
        }
    }

    public void removeChest(Location loc) {
        linkedChests.remove(loc);
        chestLocationStrings.remove(locationToString(loc));
        readOnlyChests.remove(loc);
        breakableChests.remove(loc);
    }

    public boolean isReadOnly(Location loc) {
        return readOnlyChests.getOrDefault(loc, false);
    }

    public boolean toggleReadOnly(Location loc) {
        boolean isNowReadOnly = !isReadOnly(loc);
        readOnlyChests.put(loc, isNowReadOnly);
        return isNowReadOnly;
    }

    public void setReadOnly(Location loc, boolean readOnly) {
        readOnlyChests.put(loc, readOnly);
    }

    public boolean isBreakable(Location loc) {
        return breakableChests.getOrDefault(loc, true); // デフォルトで破壊可能
    }

    public boolean toggleBreakable(Location loc) {
        boolean isNowBreakable = !isBreakable(loc);
        breakableChests.put(loc, isNowBreakable);
        return isNowBreakable;
    }

    public void setBreakable(Location loc, boolean breakable) {
        breakableChests.put(loc, breakable);
    }


    public boolean addModerator(UUID uuid) {
        return moderators.add(uuid);
    }

    public boolean removeModerator(UUID uuid) {
        return moderators.remove(uuid);
    }

    public boolean isModerator(UUID uuid) {
        return moderators.contains(uuid);
    }

    public void sortInventory() {
        if (!this.autoSort) return;
        List<ItemStack> items = Arrays.stream(virtualInventory.getContents())
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ItemStack::getType, Comparator.comparing(Material::name))
                        .thenComparing(ItemStack::getAmount, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        virtualInventory.clear();
        for (ItemStack item : items) {
            virtualInventory.addItem(item);
        }
    }


    public void save(File file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("size", this.size);
        config.set("auto-sort", this.autoSort);
        // Save inventory
        for (int i = 0; i < virtualInventory.getSize(); i++) {
            ItemStack item = virtualInventory.getItem(i);
            if (item != null) {
                config.set("inventory." + i, item);
            }
        }
        // Save chest locations
        config.set("chests", chestLocationStrings);
        // Save read-only states
        List<String> readOnlyLocations = readOnlyChests.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(entry -> locationToString(entry.getKey()))
                .collect(Collectors.toList());
        config.set("readonly", readOnlyLocations);
        // Save breakable states
        List<String> breakableLocations = breakableChests.entrySet().stream()
                .filter(entry -> !entry.getValue()) // false（破壊不能）のみ保存
                .map(entry -> locationToString(entry.getKey()))
                .collect(Collectors.toList());
        config.set("unbreakable", breakableLocations);
        // Save moderators
        List<String> modUuids = moderators.stream().map(UUID::toString).collect(Collectors.toList());
        config.set("moderators", modUuids);

        config.save(file);
    }

    public void load(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        this.size = config.getInt("size", 27);
        this.autoSort = config.getBoolean("auto-sort", true);
        this.virtualInventory = Bukkit.createInventory(null, this.size, "Virtual " + name);
        // Load inventory
        if (config.isConfigurationSection("inventory")) {
            virtualInventory.clear();
            for (String key : config.getConfigurationSection("inventory").getKeys(false)) {
                int slot = Integer.parseInt(key);
                if (slot < this.size) {
                    ItemStack item = config.getItemStack("inventory." + key);
                    virtualInventory.setItem(slot, item);
                }
            }
        }

        chestLocationStrings.clear();
        chestLocationStrings.addAll(config.getStringList("chests"));

        readOnlyChests.clear();
        config.getStringList("readonly").forEach(s -> {
            Location loc = stringToLocation(s);
            if(loc != null) readOnlyChests.put(loc, true);
        });

        breakableChests.clear();
        config.getStringList("unbreakable").forEach(s -> {
            Location loc = stringToLocation(s);
            if(loc != null) breakableChests.put(loc, false);
        });

        moderators.clear();
        config.getStringList("moderators").forEach(s -> moderators.add(UUID.fromString(s)));
    }

    public void initializeLocations() {
        linkedChests.clear();
        chestLocationStrings.forEach(s -> {
            Location loc = stringToLocation(s);
            if (loc != null) {
                linkedChests.add(loc);
            }
        });
    }


    private String locationToString(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location stringToLocation(String s) {
        String[] parts = s.split(",");
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null; // ワールドが存在しない場合はnullを返す
        return new Location(world,
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
    }
}