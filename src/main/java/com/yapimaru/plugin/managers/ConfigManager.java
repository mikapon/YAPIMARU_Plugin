package com.yapimaru.plugin.managers;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final YAPIMARU_Plugin plugin;
    private FileConfiguration config;
    private final File configFile;

    private String processingIntensity;
    private int maxBackups;
    private Map<String, List<String>> customPatterns;
    private List<String> ignoredNames;

    public ConfigManager(YAPIMARU_Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "log_add_config.yml");
        saveDefaultConfig();
        reloadConfig();
    }

    public void saveDefaultConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("log_add_config.yml", false);
        }
    }

    public void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultConfigStream = plugin.getResource("log_add_config.yml");
        if (defaultConfigStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream));
            config.setDefaults(defaultConfig);
        }

        this.processingIntensity = config.getString("processing_intensity", "high");
        this.maxBackups = config.getInt("max_backups", 5);
        this.ignoredNames = config.getStringList("ignored_names");

        this.customPatterns = new HashMap<>();
        if (config.isConfigurationSection("custom_patterns")) {
            for (String key : config.getConfigurationSection("custom_patterns").getKeys(false)) {
                List<String> patterns = config.getStringList("custom_patterns." + key);
                customPatterns.put(key, patterns);
            }
        }
    }

    public String getProcessingIntensity() {
        return processingIntensity;
    }

    public int getMaxBackups() {
        return maxBackups;
    }

    public Map<String, List<String>> getCustomPatterns() {
        return Collections.unmodifiableMap(customPatterns);
    }

    public List<String> getIgnoredNames() {
        return Collections.unmodifiableList(ignoredNames);
    }
}