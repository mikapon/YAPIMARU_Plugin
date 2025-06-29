package com.yapimaru.plugin.data;

import com.sk89q.worldedit.regions.Region;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.List;

public class ArenaData {
    private Region arenaRegion;
    private Location spawnLocation;
    private final List<Location> wallBlocks = new ArrayList<>();
    private final List<Location> spawnBoxBlocks = new ArrayList<>();

    public Region getArenaRegion() {
        return arenaRegion;
    }

    public void setArenaRegion(Region arenaRegion) {
        this.arenaRegion = arenaRegion.clone();
    }

    public Location getSpawnLocation() {
        return spawnLocation;
    }

    public void setSpawnLocation(Location spawnLocation) {
        this.spawnLocation = spawnLocation.clone();
    }

    public List<Location> getWallBlocks() {
        return wallBlocks;
    }

    public List<Location> getSpawnBoxBlocks() {
        return spawnBoxBlocks;
    }
}