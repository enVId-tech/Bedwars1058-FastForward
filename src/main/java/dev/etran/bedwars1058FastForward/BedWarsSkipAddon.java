package dev.etran.bedwars1058FastForward;

import org.bukkit.plugin.java.JavaPlugin;

public class BedWarsSkipAddon extends JavaPlugin {

    @Override
    public void onEnable() {
        // Check if BedWars1058 is loaded
        if (getServer().getPluginManager().getPlugin("BedWars1058") == null) {
            getLogger().severe("BedWars1058 was not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register the /skipevent command
        if (getCommand("skipevent") != null) {
            getCommand("skipevent").setExecutor(new SkipStageCommand());
        }

        getLogger().info("BedWarsSkipAddon successfully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("BedWarsSkipAddon disabled.");
    }
}