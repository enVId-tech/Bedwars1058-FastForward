package dev.etran.bedwars1058FastForward

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.NextEvent
import com.andrei1058.bedwars.api.arena.generator.GeneratorType
import com.andrei1058.bedwars.api.configuration.ConfigPath
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * Re-schedules BedWars1058's events.
 *
 * How BedWars1058 works (verified against its source):
 *  - Two independent countdowns, one for diamonds and one for emeralds, tick down in parallel.
 *    When one hits 0 the arena upgrades that resource, then loads the countdown for the following tier.
 *  - Once both resources are maxed, three phase countdowns run one after another:
 *    beds-destroy -> dragon-spawn -> game-end.
 *  - "Next event" is just a label; it is derived here from those countdowns (see [recompute]).
 */
internal class StageController(private val bedwars: Plugin) {

    enum class Timer { DIAMOND, EMERALD, BEDS, DRAGON, END }

    // ------------------------------------------------------------------ raw counters

    fun get(a: IArena, t: Timer): Int = when (t) {
        Timer.DIAMOND -> Internals.getInt(a, "upgradeDiamondsCount")
        Timer.EMERALD -> Internals.getInt(a, "upgradeEmeraldsCount")
        Timer.BEDS -> a.playingTask.bedsDestroyCountdown
        Timer.DRAGON -> a.playingTask.dragonSpawnCountdown
        Timer.END -> a.playingTask.gameEndCountdown
    }

    private fun put(a: IArena, t: Timer, seconds: Int) {
        when (t) {
            Timer.DIAMOND -> Internals.setInt(a, "upgradeDiamondsCount", seconds)
            Timer.EMERALD -> Internals.setInt(a, "upgradeEmeraldsCount", seconds)
            Timer.BEDS -> Internals.setInt(a.playingTask, "beds_destroy_countdown", seconds)
            Timer.DRAGON -> Internals.setInt(a.playingTask, "dragon_spawn_countdown", seconds)
            Timer.END -> Internals.setInt(a.playingTask, "game_end_countdown", seconds)
        }
    }

    // ------------------------------------------------------------------ config helpers

    private fun mainCountdown(path: String, fallback: Int): Int {
        val yml = YamlConfiguration.loadConfiguration(File(bedwars.dataFolder, "config.yml"))
        return yml.getInt(path, fallback)
    }

    private fun start(a: IArena, type: GeneratorType, nextTier: Int): Int =
        GeneratorTiers.startSeconds(bedwars, a, type, nextTier).coerceAtLeast(1)

    // ------------------------------------------------------------------ public operations

    /** Change one countdown. Diamond/emerald need a tier below III (otherwise nothing is scheduled). */
    fun setTimer(a: IArena, t: Timer, seconds: Int) {
        requirePlaying(a)
        require(seconds >= 1) { "Time must be at least 1 second." }
        require(!(t == Timer.DIAMOND && Internals.diamondTier(a) >= 3)) {
            "Diamonds are already tier III. Lower them first: /bwff tier diamond 2"
        }
        require(!(t == Timer.EMERALD && Internals.emeraldTier(a) >= 3)) {
            "Emeralds are already tier III. Lower them first: /bwff tier emerald 2"
        }
        put(a, t, seconds)
        recompute(a)
    }

    /**
     * Put the game at [target]: tiers/phase are set as if every earlier event in [ORDER]
     * had already happened, and [target] fires in [seconds] (or its configured default if <= 0).
     * Returns human-readable notes about side effects.
     */
    fun setStage(a: IArena, target: NextEvent, seconds: Int): List<String> {
        requirePlaying(a)
        val notes = ArrayList<String>()
        val idx = ORDER.indexOf(target)

        var d = 1
        var e = 1
        for (i in 0 until minOf(idx, 4)) {
            val n = ORDER[i]
            if (n == NextEvent.DIAMOND_GENERATOR_TIER_II || n == NextEvent.DIAMOND_GENERATOR_TIER_III) {
                d++
            } else {
                e++
            }
        }
        val gensDone = idx >= 4
        if (gensDone) {
            d = 3
            e = 3
        }

        if (idx < 5 && a.teams.any { it.isBedDestroyed }) {
            notes.add("Destroyed beds can't be restored; they stay destroyed.")
        }

        GeneratorTiers.apply(bedwars, a, GeneratorType.DIAMOND, d)
        GeneratorTiers.apply(bedwars, a, GeneratorType.EMERALD, e)

        if (gensDone) {
            put(a, Timer.DIAMOND, 0)
            put(a, Timer.EMERALD, 0)
        } else {
            val diamondTarget = target == NextEvent.DIAMOND_GENERATOR_TIER_II ||
                target == NextEvent.DIAMOND_GENERATOR_TIER_III
            val targetType = if (diamondTarget) GeneratorType.DIAMOND else GeneratorType.EMERALD
            val otherType = if (diamondTarget) GeneratorType.EMERALD else GeneratorType.DIAMOND
            val targetTimer = if (diamondTarget) Timer.DIAMOND else Timer.EMERALD
            val otherTimer = if (diamondTarget) Timer.EMERALD else Timer.DIAMOND
            val targetTier = if (diamondTarget) d else e
            val otherTier = if (diamondTarget) e else d

            val targetSeconds = if (seconds > 0) seconds else start(a, targetType, targetTier + 1)
            put(a, targetTimer, targetSeconds)

            // Keep the other resource from firing first, but don't disturb it if it already comes later.
            if (otherTier >= 3) {
                put(a, otherTimer, 0)
            } else if (get(a, otherTimer) <= targetSeconds) {
                put(a, otherTimer, targetSeconds + start(a, otherType, otherTier + 1))
            }
        }

        var beds = mainCountdown(ConfigPath.GENERAL_CONFIGURATION_BEDS_DESTROY_COUNTDOWN, 360)
        var dragon = mainCountdown(ConfigPath.GENERAL_CONFIGURATION_DRAGON_SPAWN_COUNTDOWN, 300)
        var end = mainCountdown(ConfigPath.GENERAL_CONFIGURATION_GAME_END_COUNTDOWN, 120)
        when (idx) {
            4 -> beds = if (seconds > 0) seconds else beds
            5 -> {
                beds = 0
                dragon = if (seconds > 0) seconds else dragon
            }
            6 -> {
                beds = 0
                dragon = 0
                end = if (seconds > 0) seconds else end
                notes.add("Dragons are skipped when jumping straight to game end.")
            }
        }
        put(a, Timer.BEDS, beds)
        put(a, Timer.DRAGON, dragon)
        put(a, Timer.END, end)

        if (idx >= 5) {
            for (t in a.teams) {
                if (!t.isBedDestroyed) {
                    t.setBedDestroyed(true)
                }
            }
        }

        recompute(a)
        if (a.nextEvent != target) {
            notes.add("${pretty(a.nextEvent)} is scheduled before it. Adjust with /bwff timer.")
        }
        return notes
    }

    /** Set a middle generator type to an exact tier. [seconds] <= 0 uses the configured delay to the next tier. */
    fun setTier(a: IArena, type: GeneratorType, tier: Int, seconds: Int, announce: Boolean) {
        requirePlaying(a)
        val diamond = type == GeneratorType.DIAMOND
        val old = if (diamond) Internals.diamondTier(a) else Internals.emeraldTier(a)

        GeneratorTiers.apply(bedwars, a, type, tier)
        put(
            a, if (diamond) Timer.DIAMOND else Timer.EMERALD,
            if (tier >= 3) 0 else if (seconds > 0) seconds else start(a, type, tier + 1)
        )

        if (announce && tier > old && tier >= 2) {
            try {
                if (diamond) {
                    a.sendDiamondsUpgradeMessages()
                } else {
                    a.sendEmeraldsUpgradeMessages()
                }
            } catch (ignored: NoSuchMethodError) {
                // very old BedWars1058 builds lack the announcement helpers; the tier change still applied
            }
        }
        recompute(a)
    }

    /**
     * Fire the upcoming event on the next game second by letting BedWars1058's own logic run
     * (so titles, messages, upgrades and bed destruction all happen normally).
     */
    fun skip(a: IArena): NextEvent {
        requirePlaying(a)
        val n = a.nextEvent
        when (n) {
            NextEvent.DIAMOND_GENERATOR_TIER_II,
            NextEvent.DIAMOND_GENERATOR_TIER_III -> put(a, Timer.DIAMOND, 1)
            NextEvent.EMERALD_GENERATOR_TIER_II,
            NextEvent.EMERALD_GENERATOR_TIER_III -> put(a, Timer.EMERALD, 1)
            NextEvent.BEDS_DESTROY -> put(a, Timer.BEDS, 1)
            NextEvent.ENDER_DRAGON -> put(a, Timer.DRAGON, 1)
            else -> put(a, Timer.END, 1)
        }
        return n
    }

    /** Go back one event in [ORDER]. Returns the event that is now next. */
    fun rewind(a: IArena, seconds: Int, notesOut: MutableList<String>): NextEvent {
        requirePlaying(a)
        var idx = ORDER.indexOf(a.nextEvent)
        if (idx < 4) {
            idx = (Internals.diamondTier(a) - 1) + (Internals.emeraldTier(a) - 1)
        }
        require(idx > 0) { "Already at the very first event." }
        val target = ORDER[idx - 1]
        notesOut.addAll(setStage(a, target, seconds))
        return target
    }

    /** Derive the "next event" label from the countdowns, the same way BedWars1058 progresses. */
    fun recompute(a: IArena) {
        val d = Internals.diamondTier(a)
        val e = Internals.emeraldTier(a)
        val dc = if (d < 3) get(a, Timer.DIAMOND) else 0
        val ec = if (e < 3) get(a, Timer.EMERALD) else 0

        val next = when {
            dc > 0 && (ec <= 0 || dc <= ec) ->
                if (d <= 1) NextEvent.DIAMOND_GENERATOR_TIER_II else NextEvent.DIAMOND_GENERATOR_TIER_III
            ec > 0 ->
                if (e <= 1) NextEvent.EMERALD_GENERATOR_TIER_II else NextEvent.EMERALD_GENERATOR_TIER_III
            get(a, Timer.BEDS) > 0 -> NextEvent.BEDS_DESTROY
            get(a, Timer.DRAGON) > 0 -> NextEvent.ENDER_DRAGON
            else -> NextEvent.GAME_END
        }
        if (a.nextEvent != next) {
            a.nextEvent = next
        }
    }

    /** Seconds until the arena's next event fires. */
    fun secondsUntilNext(a: IArena): Int = when (a.nextEvent) {
        NextEvent.DIAMOND_GENERATOR_TIER_II,
        NextEvent.DIAMOND_GENERATOR_TIER_III -> get(a, Timer.DIAMOND)
        NextEvent.EMERALD_GENERATOR_TIER_II,
        NextEvent.EMERALD_GENERATOR_TIER_III -> get(a, Timer.EMERALD)
        NextEvent.BEDS_DESTROY -> get(a, Timer.BEDS)
        NextEvent.ENDER_DRAGON -> get(a, Timer.DRAGON)
        else -> get(a, Timer.END)
    }

    companion object {
        /** Canonical order used by "event" and "rewind". */
        val ORDER: List<NextEvent> = listOf(
            NextEvent.DIAMOND_GENERATOR_TIER_II,
            NextEvent.EMERALD_GENERATOR_TIER_II,
            NextEvent.DIAMOND_GENERATOR_TIER_III,
            NextEvent.EMERALD_GENERATOR_TIER_III,
            NextEvent.BEDS_DESTROY,
            NextEvent.ENDER_DRAGON,
            NextEvent.GAME_END,
        )

        fun requirePlaying(a: IArena) {
            if (a.status != GameState.playing || a.playingTask == null) {
                throw IllegalStateException("That arena isn't in progress.")
            }
        }

        fun pretty(e: NextEvent): String = when (e) {
            NextEvent.DIAMOND_GENERATOR_TIER_II -> "Diamond II"
            NextEvent.DIAMOND_GENERATOR_TIER_III -> "Diamond III"
            NextEvent.EMERALD_GENERATOR_TIER_II -> "Emerald II"
            NextEvent.EMERALD_GENERATOR_TIER_III -> "Emerald III"
            NextEvent.BEDS_DESTROY -> "Beds Destroyed"
            NextEvent.ENDER_DRAGON -> "Sudden Death"
            else -> "Game End"
        }
    }
}
