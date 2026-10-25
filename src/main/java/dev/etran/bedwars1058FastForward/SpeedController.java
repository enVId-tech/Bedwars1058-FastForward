package dev.etran.bedwars1058FastForward;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent;
import com.andrei1058.bedwars.api.tasks.PlayingTask;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Changes game speed by taking over BedWars1058's once-per-second playing task.
 *
 * BedWars runs GamePlayingTask#run() every 20 ticks. That one method advances everything:
 * generator spawns, the event countdowns, respawn timers, invisibility timers, etc.
 * When a speed other than 1x is set, the original scheduler entry is cancelled and this class calls
 * the same run() itself: 2x = twice per second, 0.5x = once every two seconds, 0 = paused.
 * Control is handed back to BedWars (best effort) if this plugin is disabled mid-game.
 */
final class SpeedController implements Listener {

    private static final int MAX_RUNS_PER_TICK = 10;
    private static final double EPS = 1e-9;

    private static final class Session {
        final PlayingTask task;
        final Runnable body;
        double speed = 1.0;
        double acc = 0.0;
        BukkitTask loop;

        Session(PlayingTask task, Runnable body) {
            this.task = task;
            this.body = body;
        }
    }

    private final JavaPlugin plugin;
    private final Plugin bedwars;
    private final double maxSpeed;
    private final Map<IArena, Session> sessions = new HashMap<>();

    SpeedController(JavaPlugin plugin, Plugin bedwars, double maxSpeed) {
        this.plugin = plugin;
        this.bedwars = bedwars;
        this.maxSpeed = Math.max(1.0, maxSpeed);
    }

    double maxSpeed() {
        return maxSpeed;
    }

    double getSpeed(IArena a) {
        Session s = sessions.get(a);
        return s == null ? 1.0 : s.speed;
    }

    void setSpeed(IArena a, double speed) {
        if (a.getStatus() != GameState.playing || a.getPlayingTask() == null) {
            throw new IllegalStateException("That arena isn't in progress.");
        }
        if (speed < 0 || speed > maxSpeed) {
            throw new IllegalArgumentException("Speed must be between 0 and " + maxSpeed + " (max-speed in config.yml).");
        }
        PlayingTask pt = a.getPlayingTask();
        Session s = sessions.get(a);
        if (s != null && s.task != pt) {
            stop(a, s); // leftover from a previous game
            s = null;
        }
        if (s == null) {
            if (Math.abs(speed - 1.0) < EPS) {
                return; // already normal, nothing to take over
            }
            if (!(pt instanceof Runnable)) {
                throw new IllegalStateException("This BedWars1058 build's playing task isn't runnable; speed control is unavailable.");
            }
            pt.getBukkitTask().cancel();
            final Session created = new Session(pt, (Runnable) pt);
            created.loop = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(a, created), 1L, 1L);
            sessions.put(a, created);
            s = created;
        }
        s.speed = speed;
    }

    /** Advance a paused/slowed game by exactly one game second. */
    boolean tickOnce(IArena a) {
        Session s = sessions.get(a);
        if (s == null) {
            return false;
        }
        s.body.run();
        return true;
    }

    private void tick(IArena a, Session s) {
        if (a.getStatus() != GameState.playing || a.getPlayingTask() != s.task) {
            stop(a, s);
            return;
        }
        s.acc += s.speed / 20.0;
        int runs = 0;
        while (s.acc >= 1.0 - EPS && runs < MAX_RUNS_PER_TICK) {
            s.acc -= 1.0;
            runs++;
            try {
                s.body.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("Game tick failed: " + t);
            }
            if (a.getStatus() != GameState.playing || a.getPlayingTask() != s.task) {
                stop(a, s);
                return;
            }
        }
        if (s.acc >= 1.0 - EPS) {
            s.acc = 0; // fell behind the per-tick cap; drop the backlog
        }
    }

    private void stop(IArena a, Session s) {
        if (s.loop != null) {
            s.loop.cancel();
        }
        sessions.remove(a, s);
    }

    @EventHandler
    public void onStateChange(GameStateChangeEvent e) {
        if (e.getNewState() == GameState.playing) {
            return;
        }
        Session s = sessions.get(e.getArena());
        if (s != null) {
            stop(e.getArena(), s);
        }
    }

    /** Called from onDisable so a reload doesn't freeze running games. */
    void shutdown() {
        for (Map.Entry<IArena, Session> entry : new HashMap<>(sessions).entrySet()) {
            final IArena a = entry.getKey();
            final Session s = entry.getValue();
            if (s.loop != null) {
                s.loop.cancel();
            }
            if (a.getStatus() == GameState.playing && a.getPlayingTask() == s.task) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (a.getStatus() != GameState.playing || a.getPlayingTask() != s.task) {
                            cancel();
                            return;
                        }
                        s.body.run();
                    }
                }.runTaskTimer(bedwars, 20L, 20L);
            }
        }
        sessions.clear();
    }
}
