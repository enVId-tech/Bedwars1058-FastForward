package dev.etran.bedwars1058FastForward;

import com.andrei1058.bedwars.api.BedWars;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class FastForwardPlugin extends JavaPlugin {

    private SpeedController speed;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<BedWars> rsp = Bukkit.getServicesManager().getRegistration(BedWars.class);
        Plugin bedwarsPlugin = Bukkit.getPluginManager().getPlugin("BedWars1058");
        if (rsp == null || bedwarsPlugin == null) {
            getLogger().severe("BedWars1058 API not found - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        BedWars api = rsp.getProvider();

        StageController stages = new StageController(bedwarsPlugin);
        speed = new SpeedController(this, bedwarsPlugin, getConfig().getDouble("max-speed", 20.0));
        getServer().getPluginManager().registerEvents(speed, this);

        FastForwardCommand cmd = new FastForwardCommand(api, stages, speed);
        PluginCommand pc = getCommand("bwff");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
    }

    @Override
    public void onDisable() {
        if (speed != null) {
            speed.shutdown();
        }
    }
}
