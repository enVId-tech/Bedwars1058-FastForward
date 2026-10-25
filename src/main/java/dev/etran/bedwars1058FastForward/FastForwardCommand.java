package dev.etran.bedwars1058FastForward;

import com.andrei1058.bedwars.api.BedWars;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.NextEvent;
import com.andrei1058.bedwars.api.arena.generator.GeneratorType;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FastForwardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "help", "status", "speed", "timer", "event", "tier", "skip", "rewind", "debug");
    private static final List<String> EVENT_NAMES = Arrays.asList("d2", "e2", "d3", "e3", "beds", "dragon", "end");
    private static final List<String> TIMER_NAMES = Arrays.asList("diamond", "emerald", "beds", "dragon", "end");

    private static final Pattern TIME = Pattern.compile(
            "^[+-]?(\\d+:\\d{1,2}(:\\d{1,2})?|(\\d+[hms])+|\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT = Pattern.compile("(\\d+)([hms])", Pattern.CASE_INSENSITIVE);

    private final BedWars bw;
    private final StageController stages;
    private final SpeedController speed;

    FastForwardCommand(BedWars bw, StageController stages, SpeedController speed) {
        this.bw = bw;
        this.stages = stages;
        this.speed = speed;
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("bwff.admin")) {
            msg(sender, "&cYou don't have permission.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help":
                    help(sender, label);
                    break;
                case "status":
                    status(sender, arena(sender, arg(args, 1)));
                    break;
                case "speed":
                    cmdSpeed(sender, args);
                    break;
                case "timer":
                    cmdTimer(sender, args);
                    break;
                case "event":
                case "set":
                    cmdEvent(sender, args);
                    break;
                case "tier":
                    cmdTier(sender, args);
                    break;
                case "skip":
                    cmdSkip(sender, args);
                    break;
                case "rewind":
                    cmdRewind(sender, args);
                    break;
                case "debug":
                    debug(sender, arena(sender, arg(args, 1)));
                    break;
                default:
                    msg(sender, "&cUnknown subcommand. Try /" + label + " help");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            msg(sender, "&c" + ex.getMessage());
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void cmdSpeed(CommandSender sender, String[] args) {
        need(args, 2, "speed <multiplier|pause|resume|reset> [arena]");
        IArena a = arena(sender, arg(args, 2));
        double v = parseSpeed(args[1]);
        speed.setSpeed(a, v);
        if (v == 0) {
            msg(sender, "&aGame paused. &7(/bwff speed resume to continue)");
        } else {
            msg(sender, "&aGame speed set to &f" + trim(v) + "x&a.");
        }
    }

    private void cmdTimer(CommandSender sender, String[] args) {
        need(args, 3, "timer <diamond|emerald|beds|dragon|end> <time|+time|-time> [arena]");
        StageController.Timer t = parseTimer(args[1]);
        IArena a = arena(sender, arg(args, 3));
        StageController.requirePlaying(a);
        int seconds = parseTimeRelative(args[2], stages.get(a, t));
        stages.setTimer(a, t, seconds);
        msg(sender, "&a" + cap(t.name()) + " timer set to &f" + fmt(seconds) + "&a. Next event: &f"
                + StageController.pretty(a.getNextEvent()) + " &7in " + fmt(stages.secondsUntilNext(a)));
    }

    private void cmdEvent(CommandSender sender, String[] args) {
        need(args, 2, "event <d2|e2|d3|e3|beds|dragon|end> [time] [arena]");
        NextEvent ev = parseEvent(args[1]);
        Tail tail = tail(args, 2);
        IArena a = arena(sender, tail.arena);
        List<String> notes = stages.setStage(a, ev, tail.seconds);
        msg(sender, "&aNext event is now &f" + StageController.pretty(a.getNextEvent())
                + " &ain &f" + fmt(stages.secondsUntilNext(a)) + "&a.");
        for (String n : notes) {
            msg(sender, "&e" + n);
        }
    }

    private void cmdTier(CommandSender sender, String[] args) {
        need(args, 3, "tier <diamond|emerald> <1|2|3|+|-> [time] [arena]");
        GeneratorType type = parseType(args[1]);
        Tail tail = tail(args, 3);
        IArena a = arena(sender, tail.arena);
        StageController.requirePlaying(a);

        int current = type == GeneratorType.DIAMOND ? Internals.diamondTier(a) : Internals.emeraldTier(a);
        int tier;
        switch (args[2]) {
            case "+":
                tier = current + 1;
                break;
            case "-":
                tier = current - 1;
                break;
            default:
                try {
                    tier = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Tier must be 1, 2, 3, + or -.");
                }
        }
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("Tier must stay between 1 and 3 (currently " + current + ").");
        }
        stages.setTier(a, type, tier, tail.seconds, true);
        msg(sender, "&a" + cap(type.name()) + " generators set to tier &f" + roman(tier) + "&a. Next event: &f"
                + StageController.pretty(a.getNextEvent()) + " &7in " + fmt(stages.secondsUntilNext(a)));
    }

    private void cmdSkip(CommandSender sender, String[] args) {
        IArena a = arena(sender, arg(args, 1));
        NextEvent fired = stages.skip(a);
        boolean paused = speed.getSpeed(a) == 0;
        if (paused) {
            speed.tickOnce(a); // a paused game would never process the skip otherwise
        }
        msg(sender, "&aSkipping &f" + StageController.pretty(fired) + "&a"
                + (paused ? " (advanced one game second while paused)." : " - it fires within a second."));
    }

    private void cmdRewind(CommandSender sender, String[] args) {
        Tail tail = tail(args, 1);
        IArena a = arena(sender, tail.arena);
        List<String> notes = new ArrayList<>();
        NextEvent now = stages.rewind(a, tail.seconds, notes);
        msg(sender, "&aRewound. Next event is &f" + StageController.pretty(now)
                + " &ain &f" + fmt(stages.secondsUntilNext(a)) + "&a.");
        for (String n : notes) {
            msg(sender, "&e" + n);
        }
    }

    private void status(CommandSender sender, IArena a) {
        msg(sender, "&6&lArena &f" + a.getArenaName() + " &7(" + a.getStatus() + ")");
        if (a.getPlayingTask() == null) {
            msg(sender, "&7Not in progress.");
            return;
        }
        msg(sender, "&7Speed: &f" + trim(speed.getSpeed(a)) + "x"
                + (speed.getSpeed(a) == 0 ? " &c(paused)" : ""));
        msg(sender, "&7Next event: &f" + StageController.pretty(a.getNextEvent())
                + " &7in &f" + fmt(stages.secondsUntilNext(a)));
        msg(sender, "&7Diamonds: &bTier " + roman(Internals.diamondTier(a))
                + (Internals.diamondTier(a) < 3 ? " &7-> next tier in &f" + fmt(stages.get(a, StageController.Timer.DIAMOND)) : " &7(max)"));
        msg(sender, "&7Emeralds: &aTier " + roman(Internals.emeraldTier(a))
                + (Internals.emeraldTier(a) < 3 ? " &7-> next tier in &f" + fmt(stages.get(a, StageController.Timer.EMERALD)) : " &7(max)"));
        msg(sender, "&7Phase timers: beds &f" + fmt(stages.get(a, StageController.Timer.BEDS))
                + "&7, dragon &f" + fmt(stages.get(a, StageController.Timer.DRAGON))
                + "&7, end &f" + fmt(stages.get(a, StageController.Timer.END)));
    }

    private void debug(CommandSender sender, IArena a) {
        msg(sender, "&6Compatibility check for &f" + a.getArenaName());
        msg(sender, "&7Arena impl: &f" + a.getClass().getName());
        for (String f : new String[]{"upgradeDiamondsCount", "upgradeEmeraldsCount", "diamondTier", "emeraldTier"}) {
            check(sender, "Arena." + f, Internals.has(a, f));
        }
        Object pt = a.getPlayingTask();
        if (pt == null) {
            msg(sender, "&eArena isn't playing - start a game to check the playing-task fields.");
        } else {
            msg(sender, "&7PlayingTask impl: &f" + pt.getClass().getName());
            check(sender, "PlayingTask is Runnable (needed for speed)", pt instanceof Runnable);
            for (String f : new String[]{"beds_destroy_countdown", "dragon_spawn_countdown", "game_end_countdown"}) {
                check(sender, "PlayingTask." + f, Internals.has(pt, f));
            }
        }
        for (com.andrei1058.bedwars.api.arena.generator.IGenerator g : a.getOreGenerators()) {
            if (g.getBwt() == null && (g.getType() == GeneratorType.DIAMOND || g.getType() == GeneratorType.EMERALD)) {
                check(sender, "Generator.upgradeStage (" + g.getClass().getSimpleName() + ")", Internals.has(g, "upgradeStage"));
                break;
            }
        }
    }

    private void check(CommandSender sender, String what, boolean ok) {
        msg(sender, (ok ? "&a[OK] " : "&c[MISSING] ") + "&7" + what);
    }

    private void help(CommandSender sender, String label) {
        msg(sender, "&6&lBedWars1058 FastForward");
        String[] lines = {
                "status [arena] &8- &7timers, tiers and speed",
                "speed <x|pause|resume|reset> [arena] &8- &7e.g. 2, 0.5, 10",
                "timer <diamond|emerald|beds|dragon|end> <time|+t|-t> [arena]",
                "event <d2|e2|d3|e3|beds|dragon|end> [time] [arena] &8- &7jump to an event",
                "tier <diamond|emerald> <1|2|3|+|-> [time] [arena] &8- &7set generator tier",
                "skip [arena] &8- &7fire the next event now",
                "rewind [time] [arena] &8- &7go back one event",
                "debug [arena] &8- &7check internals against this BedWars build",
                "&7Times: &f90&7, &f90s&7, &f1:30&7, &f1m30s&7. Arena is optional if you're in one."
        };
        for (String l : lines) {
            msg(sender, l.startsWith("&") ? l : "&e/" + label + " " + l);
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private static final class Tail {
        int seconds = -1;
        String arena;
    }

    /** Optional [time] followed by optional [arena], starting at {@code from}. */
    private Tail tail(String[] args, int from) {
        Tail t = new Tail();
        int i = from;
        if (i < args.length && TIME.matcher(args[i]).matches() && !args[i].startsWith("+") && !args[i].startsWith("-")) {
            t.seconds = parseTime(args[i]);
            i++;
        }
        if (i < args.length) {
            t.arena = args[i];
        }
        return t;
    }

    private IArena arena(CommandSender sender, String name) {
        if (name != null) {
            IArena a = bw.getArenaUtil().getArenaByName(name);
            if (a == null) {
                for (IArena x : bw.getArenaUtil().getArenas()) {
                    if (x.getArenaName().equalsIgnoreCase(name)) {
                        a = x;
                        break;
                    }
                }
            }
            if (a == null) {
                throw new IllegalArgumentException("No arena called '" + name + "'.");
            }
            return a;
        }
        if (sender instanceof Player) {
            IArena a = bw.getArenaUtil().getArenaByPlayer((Player) sender);
            if (a != null) {
                return a;
            }
            throw new IllegalArgumentException("You're not in an arena - add the arena name to the command.");
        }
        throw new IllegalArgumentException("From the console you must give an arena name.");
    }

    private static String arg(String[] args, int i) {
        return i < args.length ? args[i] : null;
    }

    private static void need(String[] args, int count, String usage) {
        if (args.length < count) {
            throw new IllegalArgumentException("Usage: /bwff " + usage);
        }
    }

    private static double parseSpeed(String s) {
        String v = s.toLowerCase(Locale.ROOT);
        if (v.equals("pause")) {
            return 0;
        }
        if (v.equals("resume") || v.equals("reset") || v.equals("normal")) {
            return 1;
        }
        if (v.endsWith("x")) {
            v = v.substring(0, v.length() - 1);
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' isn't a speed. Try 2, 0.5, pause or reset.");
        }
    }

    private static StageController.Timer parseTimer(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "diamond":
            case "diamonds":
                return StageController.Timer.DIAMOND;
            case "emerald":
            case "emeralds":
                return StageController.Timer.EMERALD;
            case "beds":
            case "bed":
                return StageController.Timer.BEDS;
            case "dragon":
            case "dragons":
                return StageController.Timer.DRAGON;
            case "end":
            case "gameend":
                return StageController.Timer.END;
            default:
                throw new IllegalArgumentException("Timer must be one of: diamond, emerald, beds, dragon, end.");
        }
    }

    private static GeneratorType parseType(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "diamond":
            case "diamonds":
                return GeneratorType.DIAMOND;
            case "emerald":
            case "emeralds":
                return GeneratorType.EMERALD;
            default:
                throw new IllegalArgumentException("Generator must be diamond or emerald.");
        }
    }

    private static NextEvent parseEvent(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "d2":
                return NextEvent.DIAMOND_GENERATOR_TIER_II;
            case "e2":
                return NextEvent.EMERALD_GENERATOR_TIER_II;
            case "d3":
                return NextEvent.DIAMOND_GENERATOR_TIER_III;
            case "e3":
                return NextEvent.EMERALD_GENERATOR_TIER_III;
            case "beds":
            case "bed":
                return NextEvent.BEDS_DESTROY;
            case "dragon":
            case "dragons":
                return NextEvent.ENDER_DRAGON;
            case "end":
                return NextEvent.GAME_END;
            default:
                try {
                    return NextEvent.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown event. Use: d2, e2, d3, e3, beds, dragon, end.");
                }
        }
    }

    /** "+30" / "-30" are relative to {@code current}; anything else is absolute. */
    private static int parseTimeRelative(String s, int current) {
        if (!TIME.matcher(s).matches()) {
            throw new IllegalArgumentException("'" + s + "' isn't a time. Try 90, 1:30 or 2m.");
        }
        if (s.startsWith("+")) {
            return current + parseTime(s.substring(1));
        }
        if (s.startsWith("-")) {
            return Math.max(1, current - parseTime(s.substring(1)));
        }
        return parseTime(s);
    }

    /** Accepts 90, 90s, 1:30, 1:02:03, 2m, 1h30m. */
    private static int parseTime(String s) {
        if (s.contains(":")) {
            String[] p = s.split(":");
            int total = 0;
            for (String part : p) {
                total = total * 60 + Integer.parseInt(part);
            }
            return total;
        }
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        Matcher m = UNIT.matcher(s);
        int total = 0;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "h":
                    total += n * 3600;
                    break;
                case "m":
                    total += n * 60;
                    break;
                default:
                    total += n;
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ formatting

    static String fmt(int seconds) {
        int s = Math.max(0, seconds);
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int r = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, r) : String.format("%d:%02d", m, r);
    }

    private static String roman(int tier) {
        return tier == 1 ? "I" : tier == 2 ? "II" : "III";
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static void msg(CommandSender s, String m) {
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("bwff.admin")) {
            return new ArrayList<>();
        }
        List<String> options = new ArrayList<>();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            options.addAll(SUBS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "speed":
                    if (args.length == 2) {
                        options.addAll(Arrays.asList("0.5", "2", "5", "10", "pause", "resume", "reset"));
                    } else {
                        options.addAll(arenaNames());
                    }
                    break;
                case "timer":
                    if (args.length == 2) {
                        options.addAll(TIMER_NAMES);
                    } else if (args.length == 3) {
                        options.addAll(Arrays.asList("30", "1:00", "+30", "-30"));
                    } else {
                        options.addAll(arenaNames());
                    }
                    break;
                case "event":
                case "set":
                    if (args.length == 2) {
                        options.addAll(EVENT_NAMES);
                    } else {
                        options.addAll(arenaNames());
                    }
                    break;
                case "tier":
                    if (args.length == 2) {
                        options.addAll(Arrays.asList("diamond", "emerald"));
                    } else if (args.length == 3) {
                        options.addAll(Arrays.asList("1", "2", "3", "+", "-"));
                    } else {
                        options.addAll(arenaNames());
                    }
                    break;
                default:
                    options.addAll(arenaNames());
            }
        }
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(last)) {
                out.add(o);
            }
        }
        return out;
    }

    private List<String> arenaNames() {
        List<String> names = new ArrayList<>();
        for (IArena a : bw.getArenaUtil().getArenas()) {
            names.add(a.getArenaName());
        }
        return names;
    }
}