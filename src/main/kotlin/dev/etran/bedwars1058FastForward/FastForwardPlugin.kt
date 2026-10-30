package dev.etran.bedwars1058FastForward

import com.andrei1058.bedwars.api.BedWars
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class FastForwardPlugin : JavaPlugin() {

    private var speed: SpeedController? = null

    override fun onEnable() {
        saveDefaultConfig()

        val rsp = Bukkit.getServicesManager().getRegistration(BedWars::class.java)
        val bedwarsPlugin = Bukkit.getPluginManager().getPlugin("BedWars1058")
        if (rsp == null || bedwarsPlugin == null) {
            logger.severe("BedWars1058 API not found - disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        val api = rsp.provider

        val stages = StageController(bedwarsPlugin)
        val speedController = SpeedController(this, bedwarsPlugin, config.getDouble("max-speed", 20.0))
        speed = speedController
        server.pluginManager.registerEvents(speedController, this)

        val cmd = FastForwardCommand(api, stages, speedController)
        getCommand("bwff")?.let {
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
    }

    override fun onDisable() {
        speed?.shutdown()
    }
}
