package dev.etran.bedwars1058FastForward;

import com.andrei1058.bedwars.api.BedWars;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.nextstage.NextEvent;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SkipStageCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("bwskip.admin")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to skip game events.");
            return true;
        }

        // Retrieve player's arena via the API
        IArena arena = BedWars.getAPI().getArenaUtil().getArenaByPlayer(player);

        if (arena == null) {
            player.sendMessage(ChatColor.RED + "[BedWars] You are not in an active BedWars arena!");
            return true;
        }

        // Handle pre-game lobby state
        if (arena.getStatus() == GameState.WAITING || arena.getStatus() == GameState.STARTING) {
            player.performCommand("bw start");
            player.sendMessage(ChatColor.GREEN + "[BedWars] Force-starting the arena...");
            return true;
        }

        // Handle active game state
        if (arena.getStatus() == GameState.PLAYING) {
            NextEvent nextEvent = arena.getNextEvent();
            if (nextEvent != null) {
                // Execute next stage
                nextEvent.onStart();
                player.sendMessage(ChatColor.GREEN + "[BedWars] Advanced to the next event: " + nextEvent.getName());
            } else {
                player.sendMessage(ChatColor.RED + "[BedWars] No further scheduled events in this arena.");
            }
            return true;
        }

        player.sendMessage(ChatColor.RED + "[BedWars] Cannot skip events in current arena state (" + arena.getStatus().name() + ").");
        return true;
    }
}