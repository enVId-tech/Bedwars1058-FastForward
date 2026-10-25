package dev.etran.bedwars1058FastForward;

import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.generator.GeneratorType;
import com.andrei1058.bedwars.api.arena.generator.IGenHolo;
import com.andrei1058.bedwars.api.arena.generator.IGenerator;
import com.andrei1058.bedwars.api.language.Language;
import com.andrei1058.bedwars.api.language.Messages;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * Puts the middle (team-less) diamond/emerald generators into any tier 1-3, up or down.
 * IGenerator#upgrade() only goes up, so tier changes are applied by writing the same values
 * BedWars1058 itself reads from generators.yml.
 */
final class GeneratorTiers {

    private static final String[] ROMAN = {"", "I", "II", "III"};

    private GeneratorTiers() {
    }

    private static String resource(GeneratorType type) {
        return type == GeneratorType.DIAMOND ? "diamond" : "emerald";
    }

    private static int value(YamlConfiguration yml, IArena arena, String path, int fallback) {
        String key = arena.getGroup() + "." + path;
        if (!yml.contains(key)) {
            key = "Default." + path;
        }
        return yml.getInt(key, fallback);
    }

    private static YamlConfiguration generatorsYml(Plugin bedwars) {
        return YamlConfiguration.loadConfiguration(new File(bedwars.getDataFolder(), "generators.yml"));
    }

    /** Seconds BedWars waits before reaching {@code tier} (tier 2 or 3), per generators.yml. */
    static int startSeconds(Plugin bedwars, IArena arena, GeneratorType type, int tier) {
        if (tier < 2 || tier > 3) {
            return 0;
        }
        String path = resource(type) + ".tier" + ROMAN[tier] + ".start";
        return value(generatorsYml(bedwars), arena, path, tier == 2 ? 360 : 1080);
    }

    /** Make every middle generator of this type (and the arena's recorded tier) equal to {@code tier}. */
    static void apply(Plugin bedwars, IArena arena, GeneratorType type, int tier) {
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("Tier must be 1, 2 or 3.");
        }
        YamlConfiguration yml = generatorsYml(bedwars);
        String base = resource(type) + ".tier" + ROMAN[tier] + ".";
        int delay = value(yml, arena, base + "delay", 30);
        int amount = value(yml, arena, base + "amount", 1);
        int limit = value(yml, arena, base + "spawn-limit", 0);
        String tierMessage = tier == 1 ? Messages.FORMATTING_GENERATOR_TIER1
                : tier == 2 ? Messages.FORMATTING_GENERATOR_TIER2 : Messages.FORMATTING_GENERATOR_TIER3;

        for (IGenerator g : arena.getOreGenerators()) {
            if (g.getType() != type || g.getBwt() != null) {
                continue; // team generators are not touched
            }
            Internals.setInt(g, "upgradeStage", tier);
            g.setDelay(delay);
            g.setAmount(amount);
            g.setSpawnLimit(limit);
            if (g.getNextSpawn() > delay) {
                g.setNextSpawn(delay);
            }
            try {
                for (IGenHolo holo : g.getLanguageHolograms().values()) {
                    Language lang = Language.getLang(holo.getIso());
                    holo.setTierName(lang.m(Messages.GENERATOR_HOLOGRAM_TIER).replace("{tier}", lang.m(tierMessage)));
                }
            } catch (RuntimeException ignored) {
                // hologram text is cosmetic; the tier itself is already applied
            }
        }
        Internals.setInt(arena, type == GeneratorType.DIAMOND ? "diamondTier" : "emeraldTier", tier);
    }
}
