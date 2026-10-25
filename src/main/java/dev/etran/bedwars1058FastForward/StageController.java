package dev.etran.bedwars1058FastForward;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.NextEvent;
import com.andrei1058.bedwars.api.arena.generator.GeneratorType;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import com.andrei1058.bedwars.api.configuration.ConfigPath;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Re-schedules BedWars1058's events.
 *
 * How BedWars1058 works (verified against its source):
 *  - Two independent countdowns, one for diamonds and one for emeralds, tick down in parallel.
 *    When one hits 0 the arena upgrades that resource, then loads the countdown for the following tier.
 *  - Once both resources are maxed, three phase countdowns run one after another:
 *    beds-destroy -> dragon-spawn -> game-end.
 *  - "Next event" is just a label; it is derived here from those countdowns (see {@link #recompute}).
 */
final class StageController {

    /** Canonical order used by "event" and "rewind". */
    static final List<NextEvent> ORDER = Arrays.asList(
            NextEvent.DIAMOND_GENERATOR_TIER_II,
            NextEvent.EMERALD_GENERATOR_TIER_II,
            NextEvent.DIAMOND_GENERATOR_TIER_III,
            NextEvent.EMERALD_GENERATOR_TIER_III,
            NextEvent.BEDS_DESTROY,
            NextEvent.ENDER_DRAGON,
            NextEvent.GAME_END
    );

    enum Timer { DIAMOND, EMERALD, BEDS, DRAGON, END }

    private final Plugin bedwars;

    StageController(Plugin bedwars) {
        this.bedwars = bedwars;
    }

    // ------------------------------------------------------------------ raw counters

    static void requirePlaying(IArena a) {
        if (a.getStatus() != GameState.playing || a.getPlayingTask() == null) {
            throw new IllegalStateException("That arena isn't in progress.");
        }
    }

    int get(IArena a, Timer t) {
        switch (t) {
            case DIAMOND:
                return Internals.getInt(a, "upgradeDiamondsCount");
            case EMERALD:
                return Internals.getInt(a, "upgradeEmeraldsCount");
            case BEDS:
                return a.getPlayingTask().getBedsDestroyCountdown();
            case DRAGON:
                return a.getPlayingTask().getDragonSpawnCountdown();
            default:
                return a.getPlayingTask().getGameEndCountdown();
        }
    }

    private void put(IArena a, Timer t, int seconds) {
        switch (t) {
            case DIAMOND:
                Internals.setInt(a, "upgradeDiamondsCount", seconds);
                break;
            case EMERALD:
                Internals.setInt(a, "upgradeEmeraldsCount", seconds);
                break;
            case BEDS:
                Internals.setInt(a.getPlayingTask(), "beds_destroy_countdown", seconds);
                break;
            case DRAGON:
                Internals.setInt(a.getPlayingTask(), "dragon_spawn_countdown", seconds);
                break;
            default:
                Internals.setInt(a.getPlayingTask(), "game_end_countdown", seconds);
                break;
        }
    }

    // ------------------------------------------------------------------ config helpers

    private int mainCountdown(String path, int fallback) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(bedwars.getDataFolder(), "config.yml"));
        return yml.getInt(path, fallback);
    }

    private int start(IArena a, GeneratorType type, int nextTier) {
        return Math.max(1, GeneratorTiers.startSeconds(bedwars, a, type, nextTier));
    }

    // ------------------------------------------------------------------ public operations

    /** Change one countdown. Diamond/emerald need a tier below III (otherwise nothing is scheduled). */
    void setTimer(IArena a, Timer t, int seconds) {
        requirePlaying(a);
        if (seconds < 1) {
            throw new IllegalArgumentException("Time must be at least 1 second.");
        }
        if (t == Timer.DIAMOND && Internals.diamondTier(a) >= 3) {
            throw new IllegalArgumentException("Diamonds are already tier III. Lower them first: /bwff tier diamond 2");
        }
        if (t == Timer.EMERALD && Internals.emeraldTier(a) >= 3) {
            throw new IllegalArgumentException("Emeralds are already tier III. Lower them first: /bwff tier emerald 2");
        }
        put(a, t, seconds);
        recompute(a);
    }

    /**
     * Put the game at {@code target}: tiers/phase are set as if every earlier event in {@link #ORDER}
     * had already happened, and {@code target} fires in {@code seconds} (or its configured default if &lt;= 0).
     * Returns human-readable notes about side effects.
     */
    List<String> setStage(IArena a, NextEvent target, int seconds) {
        requirePlaying(a);
        List<String> notes = new ArrayList<>();
        int idx = ORDER.indexOf(target);

        int d = 1;
        int e = 1;
        for (int i = 0; i < Math.min(idx, 4); i++) {
            NextEvent n = ORDER.get(i);
            if (n == NextEvent.DIAMOND_GENERATOR_TIER_II || n == NextEvent.DIAMOND_GENERATOR_TIER_III) {
                d++;
            } else {
                e++;
            }
        }
        boolean gensDone = idx >= 4;
        if (gensDone) {
            d = 3;
            e = 3;
        }

        if (idx < 5) {
            for (ITeam t : a.getTeams()) {
                if (t.isBedDestroyed()) {
                    notes.add("Destroyed beds can't be restored; they stay destroyed.");
                    break;
                }
            }
        }

        GeneratorTiers.apply(bedwars, a, GeneratorType.DIAMOND, d);
        GeneratorTiers.apply(bedwars, a, GeneratorType.EMERALD, e);

        if (gensDone) {
            put(a, Timer.DIAMOND, 0);
            put(a, Timer.EMERALD, 0);
        } else {
            boolean diamondTarget = target == NextEvent.DIAMOND_GENERATOR_TIER_II
                    || target == NextEvent.DIAMOND_GENERATOR_TIER_III;
            GeneratorType targetType = diamondTarget ? GeneratorType.DIAMOND : GeneratorType.EMERALD;
            GeneratorType otherType = diamondTarget ? GeneratorType.EMERALD : GeneratorType.DIAMOND;
            Timer targetTimer = diamondTarget ? Timer.DIAMOND : Timer.EMERALD;
            Timer otherTimer = diamondTarget ? Timer.EMERALD : Timer.DIAMOND;
            int targetTier = diamondTarget ? d : e;
            int otherTier = diamondTarget ? e : d;

            int targetSeconds = seconds > 0 ? seconds : start(a, targetType, targetTier + 1);
            put(a, targetTimer, targetSeconds);

            // Keep the other resource from firing first, but don't disturb it if it already comes later.
            if (otherTier >= 3) {
                put(a, otherTimer, 0);
            } else if (get(a, otherTimer) <= targetSeconds) {
                put(a, otherTimer, targetSeconds + start(a, otherType, otherTier + 1));
            }
        }

        int beds = mainCountdown(ConfigPath.GENERAL_CONFIGURATION_BEDS_DESTROY_COUNTDOWN, 360);
        int dragon = mainCountdown(ConfigPath.GENERAL_CONFIGURATION_DRAGON_SPAWN_COUNTDOWN, 300);
        int end = mainCountdown(ConfigPath.GENERAL_CONFIGURATION_GAME_END_COUNTDOWN, 120);
        switch (idx) {
            case 4:
                beds = seconds > 0 ? seconds : beds;
                break;
            case 5:
                beds = 0;
                dragon = seconds > 0 ? seconds : dragon;
                break;
            case 6:
                beds = 0;
                dragon = 0;
                end = seconds > 0 ? seconds : end;
                notes.add("Dragons are skipped when jumping straight to game end.");
                break;
            default:
                break;
        }
        put(a, Timer.BEDS, beds);
        put(a, Timer.DRAGON, dragon);
        put(a, Timer.END, end);

        if (idx >= 5) {
            for (ITeam t : a.getTeams()) {
                if (!t.isBedDestroyed()) {
                    t.setBedDestroyed(true);
                }
            }
        }

        recompute(a);
        if (a.getNextEvent() != target) {
            notes.add(pretty(a.getNextEvent()) + " is scheduled before it. Adjust with /bwff timer.");
        }
        return notes;
    }

    /** Set a middle generator type to an exact tier. {@code seconds <= 0} uses the configured delay to the next tier. */
    void setTier(IArena a, GeneratorType type, int tier, int seconds, boolean announce) {
        requirePlaying(a);
        boolean diamond = type == GeneratorType.DIAMOND;
        int old = diamond ? Internals.diamondTier(a) : Internals.emeraldTier(a);

        GeneratorTiers.apply(bedwars, a, type, tier);
        put(a, diamond ? Timer.DIAMOND : Timer.EMERALD,
                tier >= 3 ? 0 : (seconds > 0 ? seconds : start(a, type, tier + 1)));

        if (announce && tier > old && tier >= 2) {
            try {
                if (diamond) {
                    a.sendDiamondsUpgradeMessages();
                } else {
                    a.sendEmeraldsUpgradeMessages();
                }
            } catch (NoSuchMethodError ignored) {
                // very old BedWars1058 builds lack the announcement helpers; the tier change still applied
            }
        }
        recompute(a);
    }

    /**
     * Fire the upcoming event on the next game second by letting BedWars1058's own logic run
     * (so titles, messages, upgrades and bed destruction all happen normally).
     */
    NextEvent skip(IArena a) {
        requirePlaying(a);
        NextEvent n = a.getNextEvent();
        switch (n) {
            case DIAMOND_GENERATOR_TIER_II:
            case DIAMOND_GENERATOR_TIER_III:
                put(a, Timer.DIAMOND, 1);
                break;
            case EMERALD_GENERATOR_TIER_II:
            case EMERALD_GENERATOR_TIER_III:
                put(a, Timer.EMERALD, 1);
                break;
            case BEDS_DESTROY:
                put(a, Timer.BEDS, 1);
                break;
            case ENDER_DRAGON:
                put(a, Timer.DRAGON, 1);
                break;
            default:
                put(a, Timer.END, 1);
                break;
        }
        return n;
    }

    /** Go back one event in {@link #ORDER}. Returns the event that is now next. */
    NextEvent rewind(IArena a, int seconds, List<String> notesOut) {
        requirePlaying(a);
        int idx = ORDER.indexOf(a.getNextEvent());
        if (idx < 4) {
            idx = (Internals.diamondTier(a) - 1) + (Internals.emeraldTier(a) - 1);
        }
        if (idx <= 0) {
            throw new IllegalArgumentException("Already at the very first event.");
        }
        NextEvent target = ORDER.get(idx - 1);
        notesOut.addAll(setStage(a, target, seconds));
        return target;
    }

    /** Derive the "next event" label from the countdowns, the same way BedWars1058 progresses. */
    void recompute(IArena a) {
        int d = Internals.diamondTier(a);
        int e = Internals.emeraldTier(a);
        int dc = d < 3 ? get(a, Timer.DIAMOND) : 0;
        int ec = e < 3 ? get(a, Timer.EMERALD) : 0;

        NextEvent next;
        if (dc > 0 && (ec <= 0 || dc <= ec)) {
            next = d <= 1 ? NextEvent.DIAMOND_GENERATOR_TIER_II : NextEvent.DIAMOND_GENERATOR_TIER_III;
        } else if (ec > 0) {
            next = e <= 1 ? NextEvent.EMERALD_GENERATOR_TIER_II : NextEvent.EMERALD_GENERATOR_TIER_III;
        } else if (get(a, Timer.BEDS) > 0) {
            next = NextEvent.BEDS_DESTROY;
        } else if (get(a, Timer.DRAGON) > 0) {
            next = NextEvent.ENDER_DRAGON;
        } else {
            next = NextEvent.GAME_END;
        }
        if (a.getNextEvent() != next) {
            a.setNextEvent(next);
        }
    }

    /** Seconds until the arena's next event fires. */
    int secondsUntilNext(IArena a) {
        switch (a.getNextEvent()) {
            case DIAMOND_GENERATOR_TIER_II:
            case DIAMOND_GENERATOR_TIER_III:
                return get(a, Timer.DIAMOND);
            case EMERALD_GENERATOR_TIER_II:
            case EMERALD_GENERATOR_TIER_III:
                return get(a, Timer.EMERALD);
            case BEDS_DESTROY:
                return get(a, Timer.BEDS);
            case ENDER_DRAGON:
                return get(a, Timer.DRAGON);
            default:
                return get(a, Timer.END);
        }
    }

    static String pretty(NextEvent e) {
        switch (e) {
            case DIAMOND_GENERATOR_TIER_II:
                return "Diamond II";
            case DIAMOND_GENERATOR_TIER_III:
                return "Diamond III";
            case EMERALD_GENERATOR_TIER_II:
                return "Emerald II";
            case EMERALD_GENERATOR_TIER_III:
                return "Emerald III";
            case BEDS_DESTROY:
                return "Beds Destroyed";
            case ENDER_DRAGON:
                return "Sudden Death";
            default:
                return "Game End";
        }
    }
}