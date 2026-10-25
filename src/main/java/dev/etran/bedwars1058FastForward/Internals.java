package dev.etran.bedwars1058FastForward;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * BedWars1058 keeps its countdowns and generator tiers in private fields of its implementation
 * classes (Arena, GamePlayingTask, OreGenerator) and does not expose setters in the API.
 * Everything that needs those fields goes through here so a version mismatch produces one clear error.
 */
final class Internals {

    private static final Map<String, Field> CACHE = new HashMap<>();

    private Internals() {
    }

    static Field field(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        Field cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new IllegalStateException("This BedWars1058 build has no field '" + name + "' on "
                + type.getSimpleName() + ". Run /bwff debug and report it.");
    }

    static int getInt(Object target, String name) {
        try {
            return field(target.getClass(), name).getInt(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + name + ": " + e.getMessage());
        }
    }

    static void setInt(Object target, String name, int value) {
        try {
            field(target.getClass(), name).setInt(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write " + name + ": " + e.getMessage());
        }
    }

    static boolean has(Object target, String name) {
        try {
            field(target.getClass(), name);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Current middle-generator tiers (1-3). Read from the arena's fields because older API jars lack IArena#getDiamondTier. */
    static int diamondTier(Object arena) {
        return getInt(arena, "diamondTier");
    }

    static int emeraldTier(Object arena) {
        return getInt(arena, "emeraldTier");
    }
}