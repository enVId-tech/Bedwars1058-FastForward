package dev.etran.bedwars1058FastForward

import com.andrei1058.bedwars.api.BedWars
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.NextEvent
import com.andrei1058.bedwars.api.arena.generator.GeneratorType
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale
import kotlin.math.floor

internal class FastForwardCommand(
    private val bw: BedWars,
    private val stages: StageController,
    private val speed: SpeedController,
) : CommandExecutor, TabCompleter {

    // ------------------------------------------------------------------ dispatch

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("bwff.admin")) {
            msg(sender, "&cYou don't have permission.")
            return true
        }
        if (args.isEmpty()) {
            help(sender, label)
            return true
        }
        try {
            when (args[0].lowercase(Locale.ROOT)) {
                "help" -> help(sender, label)
                "status" -> status(sender, arena(sender, arg(args, 1)))
                "speed" -> cmdSpeed(sender, args)
                "timer" -> cmdTimer(sender, args)
                "event", "set" -> cmdEvent(sender, args)
                "tier" -> cmdTier(sender, args)
                "skip" -> cmdSkip(sender, args)
                "rewind" -> cmdRewind(sender, args)
                "debug" -> debug(sender, arena(sender, arg(args, 1)))
                else -> msg(sender, "&cUnknown subcommand. Try /$label help")
            }
        } catch (ex: IllegalArgumentException) {
            msg(sender, "&c${ex.message}")
        } catch (ex: IllegalStateException) {
            msg(sender, "&c${ex.message}")
        }
        return true
    }

    // ------------------------------------------------------------------ subcommands

    private fun cmdSpeed(sender: CommandSender, args: Array<String>) {
        need(args, 2, "speed <multiplier|pause|resume|reset> [arena]")
        val a = arena(sender, arg(args, 2))
        val v = parseSpeed(args[1])
        speed.setSpeed(a, v)
        if (v == 0.0) {
            msg(sender, "&aGame paused. &7(/bwff speed resume to continue)")
        } else {
            msg(sender, "&aGame speed set to &f${trim(v)}x&a.")
        }
    }

    private fun cmdTimer(sender: CommandSender, args: Array<String>) {
        need(args, 3, "timer <diamond|emerald|beds|dragon|end> <time|+time|-time> [arena]")
        val t = parseTimer(args[1])
        val a = arena(sender, arg(args, 3))
        StageController.requirePlaying(a)
        val seconds = parseTimeRelative(args[2], stages.get(a, t))
        stages.setTimer(a, t, seconds)
        msg(
            sender,
            "&a${cap(t.name)} timer set to &f${fmt(seconds)}&a. Next event: &f" +
                "${StageController.pretty(a.nextEvent)} &7in ${fmt(stages.secondsUntilNext(a))}"
        )
    }

    private fun cmdEvent(sender: CommandSender, args: Array<String>) {
        need(args, 2, "event <d2|e2|d3|e3|beds|dragon|end> [time] [arena]")
        val ev = parseEvent(args[1])
        val tail = tail(args, 2)
        val a = arena(sender, tail.arena)
        val notes = stages.setStage(a, ev, tail.seconds)
        msg(
            sender,
            "&aNext event is now &f${StageController.pretty(a.nextEvent)}" +
                " &ain &f${fmt(stages.secondsUntilNext(a))}&a."
        )
        notes.forEach { msg(sender, "&e$it") }
    }

    private fun cmdTier(sender: CommandSender, args: Array<String>) {
        need(args, 3, "tier <diamond|emerald> <1|2|3|+|-> [time] [arena]")
        val type = parseType(args[1])
        val tail = tail(args, 3)
        val a = arena(sender, tail.arena)
        StageController.requirePlaying(a)

        val current = if (type == GeneratorType.DIAMOND) Internals.diamondTier(a) else Internals.emeraldTier(a)
        val tier = when (args[2]) {
            "+" -> current + 1
            "-" -> current - 1
            else -> args[2].toIntOrNull()
                ?: throw IllegalArgumentException("Tier must be 1, 2, 3, + or -.")
        }
        require(tier in 1..3) { "Tier must stay between 1 and 3 (currently $current)." }
        stages.setTier(a, type, tier, tail.seconds, true)
        msg(
            sender,
            "&a${cap(type.name)} generators set to tier &f${roman(tier)}&a. Next event: &f" +
                "${StageController.pretty(a.nextEvent)} &7in ${fmt(stages.secondsUntilNext(a))}"
        )
    }

    private fun cmdSkip(sender: CommandSender, args: Array<String>) {
        val a = arena(sender, arg(args, 1))
        val fired = stages.skip(a)
        val paused = speed.getSpeed(a) == 0.0
        if (paused) {
            speed.tickOnce(a) // a paused game would never process the skip otherwise
        }
        msg(
            sender,
            "&aSkipping &f${StageController.pretty(fired)}&a" +
                if (paused) " (advanced one game second while paused)." else " - it fires within a second."
        )
    }

    private fun cmdRewind(sender: CommandSender, args: Array<String>) {
        val tail = tail(args, 1)
        val a = arena(sender, tail.arena)
        val notes = ArrayList<String>()
        val now = stages.rewind(a, tail.seconds, notes)
        msg(
            sender,
            "&aRewound. Next event is &f${StageController.pretty(now)}" +
                " &ain &f${fmt(stages.secondsUntilNext(a))}&a."
        )
        notes.forEach { msg(sender, "&e$it") }
    }

    private fun status(sender: CommandSender, a: IArena) {
        msg(sender, "&6&lArena &f${a.arenaName} &7(${a.status})")
        if (a.playingTask == null) {
            msg(sender, "&7Not in progress.")
            return
        }
        val currentSpeed = speed.getSpeed(a)
        msg(sender, "&7Speed: &f${trim(currentSpeed)}x" + if (currentSpeed == 0.0) " &c(paused)" else "")
        msg(
            sender,
            "&7Next event: &f${StageController.pretty(a.nextEvent)} &7in &f${fmt(stages.secondsUntilNext(a))}"
        )
        val d = Internals.diamondTier(a)
        val e = Internals.emeraldTier(a)
        msg(
            sender,
            "&7Diamonds: &bTier ${roman(d)}" +
                if (d < 3) " &7-> next tier in &f${fmt(stages.get(a, StageController.Timer.DIAMOND))}" else " &7(max)"
        )
        msg(
            sender,
            "&7Emeralds: &aTier ${roman(e)}" +
                if (e < 3) " &7-> next tier in &f${fmt(stages.get(a, StageController.Timer.EMERALD))}" else " &7(max)"
        )
        msg(
            sender,
            "&7Phase timers: beds &f${fmt(stages.get(a, StageController.Timer.BEDS))}" +
                "&7, dragon &f${fmt(stages.get(a, StageController.Timer.DRAGON))}" +
                "&7, end &f${fmt(stages.get(a, StageController.Timer.END))}"
        )
    }

    private fun debug(sender: CommandSender, a: IArena) {
        msg(sender, "&6Compatibility check for &f${a.arenaName}")
        msg(sender, "&7Arena impl: &f${a.javaClass.name}")
        for (f in arrayOf("upgradeDiamondsCount", "upgradeEmeraldsCount", "diamondTier", "emeraldTier")) {
            check(sender, "Arena.$f", Internals.has(a, f))
        }
        val pt: Any? = a.playingTask
        if (pt == null) {
            msg(sender, "&eArena isn't playing - start a game to check the playing-task fields.")
        } else {
            msg(sender, "&7PlayingTask impl: &f${pt.javaClass.name}")
            check(sender, "PlayingTask is Runnable (needed for speed)", pt is Runnable)
            for (f in arrayOf("beds_destroy_countdown", "dragon_spawn_countdown", "game_end_countdown")) {
                check(sender, "PlayingTask.$f", Internals.has(pt, f))
            }
        }
        for (g in a.oreGenerators) {
            if (g.bwt == null && (g.type == GeneratorType.DIAMOND || g.type == GeneratorType.EMERALD)) {
                check(sender, "Generator.upgradeStage (${g.javaClass.simpleName})", Internals.has(g, "upgradeStage"))
                break
            }
        }
    }

    private fun check(sender: CommandSender, what: String, ok: Boolean) {
        msg(sender, (if (ok) "&a[OK] " else "&c[MISSING] ") + "&7" + what)
    }

    private fun help(sender: CommandSender, label: String) {
        msg(sender, "&6&lBedWars1058 FastForward")
        val lines = arrayOf(
            "status [arena] &8- &7timers, tiers and speed",
            "speed <x|pause|resume|reset> [arena] &8- &7e.g. 2, 0.5, 10",
            "timer <diamond|emerald|beds|dragon|end> <time|+t|-t> [arena]",
            "event <d2|e2|d3|e3|beds|dragon|end> [time] [arena] &8- &7jump to an event",
            "tier <diamond|emerald> <1|2|3|+|-> [time] [arena] &8- &7set generator tier",
            "skip [arena] &8- &7fire the next event now",
            "rewind [time] [arena] &8- &7go back one event",
            "debug [arena] &8- &7check internals against this BedWars build",
            "&7Times: &f90&7, &f90s&7, &f1:30&7, &f1m30s&7. Arena is optional if you're in one.",
        )
        for (l in lines) {
            msg(sender, if (l.startsWith("&")) l else "&e/$label $l")
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private class Tail {
        var seconds = -1
        var arena: String? = null
    }

    /** Optional [time] followed by optional [arena], starting at [from]. */
    private fun tail(args: Array<String>, from: Int): Tail {
        val t = Tail()
        var i = from
        if (i < args.size && TIME.matches(args[i]) && !args[i].startsWith("+") && !args[i].startsWith("-")) {
            t.seconds = parseTime(args[i])
            i++
        }
        if (i < args.size) {
            t.arena = args[i]
        }
        return t
    }

    private fun arena(sender: CommandSender, name: String?): IArena {
        if (name != null) {
            val a = bw.arenaUtil.getArenaByName(name)
                ?: bw.arenaUtil.arenas.firstOrNull { it.arenaName.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("No arena called '$name'.")
            return a
        }
        if (sender is Player) {
            return bw.arenaUtil.getArenaByPlayer(sender)
                ?: throw IllegalArgumentException("You're not in an arena - add the arena name to the command.")
        }
        throw IllegalArgumentException("From the console you must give an arena name.")
    }

    // ------------------------------------------------------------------ tab completion

    override fun onTabComplete(
        sender: CommandSender,
        cmd: Command,
        alias: String,
        args: Array<String>,
    ): MutableList<String> {
        if (!sender.hasPermission("bwff.admin")) {
            return ArrayList()
        }
        val last = args[args.size - 1].lowercase(Locale.ROOT)

        val options: List<String> = if (args.size == 1) {
            SUBS
        } else {
            when (args[0].lowercase(Locale.ROOT)) {
                "speed" ->
                    if (args.size == 2) listOf("0.5", "2", "5", "10", "pause", "resume", "reset") else arenaNames()
                "timer" -> when (args.size) {
                    2 -> TIMER_NAMES
                    3 -> listOf("30", "1:00", "+30", "-30")
                    else -> arenaNames()
                }
                "event", "set" -> if (args.size == 2) EVENT_NAMES else arenaNames()
                "tier" -> when (args.size) {
                    2 -> listOf("diamond", "emerald")
                    3 -> listOf("1", "2", "3", "+", "-")
                    else -> arenaNames()
                }
                else -> arenaNames()
            }
        }
        return options.filterTo(ArrayList()) { it.lowercase(Locale.ROOT).startsWith(last) }
    }

    private fun arenaNames(): List<String> = bw.arenaUtil.arenas.map { it.arenaName }

    companion object {
        private val SUBS = listOf("help", "status", "speed", "timer", "event", "tier", "skip", "rewind", "debug")
        private val EVENT_NAMES = listOf("d2", "e2", "d3", "e3", "beds", "dragon", "end")
        private val TIMER_NAMES = listOf("diamond", "emerald", "beds", "dragon", "end")

        private val TIME = Regex(
            "^[+-]?(\\d+:\\d{1,2}(:\\d{1,2})?|(\\d+[hms])+|\\d+)$", RegexOption.IGNORE_CASE
        )
        private val UNIT = Regex("(\\d+)([hms])", RegexOption.IGNORE_CASE)
        private val DIGITS = Regex("\\d+")

        private fun arg(args: Array<String>, i: Int): String? = if (i < args.size) args[i] else null

        private fun need(args: Array<String>, count: Int, usage: String) {
            require(args.size >= count) { "Usage: /bwff $usage" }
        }

        private fun parseSpeed(s: String): Double {
            var v = s.lowercase(Locale.ROOT)
            if (v == "pause") {
                return 0.0
            }
            if (v == "resume" || v == "reset" || v == "normal") {
                return 1.0
            }
            if (v.endsWith("x")) {
                v = v.substring(0, v.length - 1)
            }
            return v.toDoubleOrNull()
                ?: throw IllegalArgumentException("'$s' isn't a speed. Try 2, 0.5, pause or reset.")
        }

        private fun parseTimer(s: String): StageController.Timer = when (s.lowercase(Locale.ROOT)) {
            "diamond", "diamonds" -> StageController.Timer.DIAMOND
            "emerald", "emeralds" -> StageController.Timer.EMERALD
            "beds", "bed" -> StageController.Timer.BEDS
            "dragon", "dragons" -> StageController.Timer.DRAGON
            "end", "gameend" -> StageController.Timer.END
            else -> throw IllegalArgumentException("Timer must be one of: diamond, emerald, beds, dragon, end.")
        }

        private fun parseType(s: String): GeneratorType = when (s.lowercase(Locale.ROOT)) {
            "diamond", "diamonds" -> GeneratorType.DIAMOND
            "emerald", "emeralds" -> GeneratorType.EMERALD
            else -> throw IllegalArgumentException("Generator must be diamond or emerald.")
        }

        private fun parseEvent(s: String): NextEvent = when (s.lowercase(Locale.ROOT)) {
            "d2" -> NextEvent.DIAMOND_GENERATOR_TIER_II
            "e2" -> NextEvent.EMERALD_GENERATOR_TIER_II
            "d3" -> NextEvent.DIAMOND_GENERATOR_TIER_III
            "e3" -> NextEvent.EMERALD_GENERATOR_TIER_III
            "beds", "bed" -> NextEvent.BEDS_DESTROY
            "dragon", "dragons" -> NextEvent.ENDER_DRAGON
            "end" -> NextEvent.GAME_END
            else -> try {
                NextEvent.valueOf(s.uppercase(Locale.ROOT))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown event. Use: d2, e2, d3, e3, beds, dragon, end.")
            }
        }

        /** "+30" / "-30" are relative to [current]; anything else is absolute. */
        private fun parseTimeRelative(s: String, current: Int): Int {
            require(TIME.matches(s)) { "'$s' isn't a time. Try 90, 1:30 or 2m." }
            if (s.startsWith("+")) {
                return current + parseTime(s.substring(1))
            }
            if (s.startsWith("-")) {
                return maxOf(1, current - parseTime(s.substring(1)))
            }
            return parseTime(s)
        }

        /** Accepts 90, 90s, 1:30, 1:02:03, 2m, 1h30m. */
        private fun parseTime(s: String): Int {
            if (s.contains(":")) {
                var total = 0
                for (part in s.split(":")) {
                    total = total * 60 + part.toInt()
                }
                return total
            }
            if (DIGITS.matches(s)) {
                return s.toInt()
            }
            var total = 0
            for (m in UNIT.findAll(s)) {
                val n = m.groupValues[1].toInt()
                total += when (m.groupValues[2].lowercase(Locale.ROOT)) {
                    "h" -> n * 3600
                    "m" -> n * 60
                    else -> n
                }
            }
            return total
        }

        // -------------------------------------------------------------- formatting

        fun fmt(seconds: Int): String {
            val s = maxOf(0, seconds)
            val h = s / 3600
            val m = (s % 3600) / 60
            val r = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, r) else String.format("%d:%02d", m, r)
        }

        private fun roman(tier: Int): String = when (tier) {
            1 -> "I"
            2 -> "II"
            else -> "III"
        }

        private fun cap(s: String): String = s.take(1) + s.substring(1).lowercase(Locale.ROOT)

        private fun trim(v: Double): String = if (v == floor(v)) v.toLong().toString() else v.toString()

        private fun msg(s: CommandSender, m: String) {
            s.sendMessage(ChatColor.translateAlternateColorCodes('&', m))
        }
    }
}
