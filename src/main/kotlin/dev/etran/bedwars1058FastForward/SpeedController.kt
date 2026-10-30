package dev.etran.bedwars1058FastForward

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent
import com.andrei1058.bedwars.api.tasks.PlayingTask
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import kotlin.math.abs

/**
 * Changes game speed by taking over BedWars1058's once-per-second playing task.
 *
 * BedWars runs GamePlayingTask#run() every 20 ticks. That one method advances everything:
 * generator spawns, the event countdowns, respawn timers, invisibility timers, etc.
 * When a speed other than 1x is set, the original scheduler entry is cancelled and this class calls
 * the same run() itself: 2x = twice per second, 0.5x = once every two seconds, 0 = paused.
 * Control is handed back to BedWars (best effort) if this plugin is disabled mid-game.
 */
internal class SpeedController(
    private val plugin: JavaPlugin,
    private val bedwars: Plugin,
    maxSpeed: Double,
) : Listener {

    private class Session(val task: PlayingTask, val body: Runnable) {
        var speed = 1.0
        var acc = 0.0
        var loop: BukkitTask? = null
    }

    private val maxSpeed: Double = maxSpeed.coerceAtLeast(1.0)
    private val sessions = HashMap<IArena, Session>()

    fun maxSpeed(): Double = maxSpeed

    fun getSpeed(a: IArena): Double = sessions[a]?.speed ?: 1.0

    fun setSpeed(a: IArena, speed: Double) {
        if (a.status != GameState.playing || a.playingTask == null) {
            throw IllegalStateException("That arena isn't in progress.")
        }
        if (speed < 0 || speed > maxSpeed) {
            throw IllegalArgumentException("Speed must be between 0 and $maxSpeed (max-speed in config.yml).")
        }
        val pt = a.playingTask
        var s = sessions[a]
        if (s != null && s.task !== pt) {
            stop(a, s) // leftover from a previous game
            s = null
        }
        if (s == null) {
            if (abs(speed - 1.0) < EPS) {
                return // already normal, nothing to take over
            }
            if (pt !is Runnable) {
                throw IllegalStateException("This BedWars1058 build's playing task isn't runnable; speed control is unavailable.")
            }
            try {
                pt.bukkitTask.cancel()
            } catch (e: NoSuchMethodError) {
                throw IllegalStateException("This BedWars1058 build can't expose its playing task; speed control is unavailable.")
            }
            val created = Session(pt, pt)
            created.loop = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick(a, created) }, 1L, 1L)
            sessions[a] = created
            s = created
        }
        s.speed = speed
    }

    /** Advance a paused/slowed game by exactly one game second. */
    fun tickOnce(a: IArena): Boolean {
        val s = sessions[a] ?: return false
        s.body.run()
        return true
    }

    private fun tick(a: IArena, s: Session) {
        if (a.status != GameState.playing || a.playingTask !== s.task) {
            stop(a, s)
            return
        }
        s.acc += s.speed / 20.0
        var runs = 0
        while (s.acc >= 1.0 - EPS && runs < MAX_RUNS_PER_TICK) {
            s.acc -= 1.0
            runs++
            try {
                s.body.run()
            } catch (t: Throwable) {
                plugin.logger.warning("Game tick failed: $t")
            }
            if (a.status != GameState.playing || a.playingTask !== s.task) {
                stop(a, s)
                return
            }
        }
        if (s.acc >= 1.0 - EPS) {
            s.acc = 0.0 // fell behind the per-tick cap; drop the backlog
        }
    }

    private fun stop(a: IArena, s: Session) {
        s.loop?.cancel()
        if (sessions[a] === s) {
            sessions.remove(a)
        }
    }

    @EventHandler
    fun onStateChange(e: GameStateChangeEvent) {
        if (e.newState == GameState.playing) {
            return
        }
        val s = sessions[e.arena]
        if (s != null) {
            stop(e.arena, s)
        }
    }

    /** Called from onDisable so a reload doesn't freeze running games. */
    fun shutdown() {
        for ((a, s) in HashMap(sessions)) {
            s.loop?.cancel()
            if (a.status == GameState.playing && a.playingTask === s.task) {
                object : BukkitRunnable() {
                    override fun run() {
                        if (a.status != GameState.playing || a.playingTask !== s.task) {
                            cancel()
                            return
                        }
                        s.body.run()
                    }
                }.runTaskTimer(bedwars, 20L, 20L)
            }
        }
        sessions.clear()
    }

    private companion object {
        const val MAX_RUNS_PER_TICK = 10
        const val EPS = 1e-9
    }
}
