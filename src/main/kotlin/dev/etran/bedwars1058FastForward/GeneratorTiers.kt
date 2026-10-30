package dev.etran.bedwars1058FastForward

import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.generator.GeneratorType
import com.andrei1058.bedwars.api.language.Language
import com.andrei1058.bedwars.api.language.Messages
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * Puts the middle (team-less) diamond/emerald generators into any tier 1-3, up or down.
 * IGenerator#upgrade() only goes up, so tier changes are applied by writing the same values
 * BedWars1058 itself reads from generators.yml.
 */
internal object GeneratorTiers {

    private val ROMAN = arrayOf("", "I", "II", "III")

    private fun resource(type: GeneratorType): String =
        if (type == GeneratorType.DIAMOND) "diamond" else "emerald"

    private fun value(yml: YamlConfiguration, arena: IArena, path: String, fallback: Int): Int {
        var key = "${arena.group}.$path"
        if (!yml.contains(key)) {
            key = "Default.$path"
        }
        return yml.getInt(key, fallback)
    }

    private fun generatorsYml(bedwars: Plugin): YamlConfiguration =
        YamlConfiguration.loadConfiguration(File(bedwars.dataFolder, "generators.yml"))

    /** Seconds BedWars waits before reaching [tier] (tier 2 or 3), per generators.yml. */
    fun startSeconds(bedwars: Plugin, arena: IArena, type: GeneratorType, tier: Int): Int {
        if (tier < 2 || tier > 3) {
            return 0
        }
        val path = "${resource(type)}.tier${ROMAN[tier]}.start"
        return value(generatorsYml(bedwars), arena, path, if (tier == 2) 360 else 1080)
    }

    /** Make every middle generator of this type (and the arena's recorded tier) equal to [tier]. */
    fun apply(bedwars: Plugin, arena: IArena, type: GeneratorType, tier: Int) {
        require(tier in 1..3) { "Tier must be 1, 2 or 3." }

        val yml = generatorsYml(bedwars)
        val base = "${resource(type)}.tier${ROMAN[tier]}."
        val delay = value(yml, arena, base + "delay", 30)
        val amount = value(yml, arena, base + "amount", 1)
        val limit = value(yml, arena, base + "spawn-limit", 0)
        val tierMessage = when (tier) {
            1 -> Messages.FORMATTING_GENERATOR_TIER1
            2 -> Messages.FORMATTING_GENERATOR_TIER2
            else -> Messages.FORMATTING_GENERATOR_TIER3
        }

        for (g in arena.oreGenerators) {
            if (g.type != type || g.bwt != null) {
                continue // team generators are not touched
            }
            Internals.setInt(g, "upgradeStage", tier)
            g.setDelay(delay)
            g.setAmount(amount)
            g.setSpawnLimit(limit)
            if (g.nextSpawn > delay) {
                g.nextSpawn = delay
            }
            try {
                for (holo in g.languageHolograms.values) {
                    val lang = Language.getLang(holo.iso)
                    holo.setTierName(lang.m(Messages.GENERATOR_HOLOGRAM_TIER).replace("{tier}", lang.m(tierMessage)))
                }
            } catch (ignored: RuntimeException) {
                // hologram text is cosmetic; the tier itself is already applied
            }
        }
        Internals.setInt(arena, if (type == GeneratorType.DIAMOND) "diamondTier" else "emeraldTier", tier)
    }
}
