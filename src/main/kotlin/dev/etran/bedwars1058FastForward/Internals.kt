package dev.etran.bedwars1058FastForward

import java.lang.reflect.Field

/**
 * BedWars1058 keeps its countdowns and generator tiers in private fields of its implementation
 * classes (Arena, GamePlayingTask, OreGenerator) and does not expose setters in the API.
 * Everything that needs those fields goes through here so a version mismatch produces one clear error.
 */
internal object Internals {

    private val cache = HashMap<String, Field>()

    fun field(type: Class<*>, name: String): Field {
        val key = "${type.name}#$name"
        cache[key]?.let { return it }

        var c: Class<*>? = type
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                cache[key] = f
                return f
            } catch (ignored: NoSuchFieldException) {
                // keep walking up
            }
            c = c.superclass
        }
        throw IllegalStateException(
            "This BedWars1058 build has no field '$name' on ${type.simpleName}. Run /bwff debug and report it."
        )
    }

    fun getInt(target: Any, name: String): Int =
        try {
            field(target.javaClass, name).getInt(target)
        } catch (e: IllegalAccessException) {
            throw IllegalStateException("Cannot read $name: ${e.message}")
        }

    fun setInt(target: Any, name: String, value: Int) {
        try {
            field(target.javaClass, name).setInt(target, value)
        } catch (e: IllegalAccessException) {
            throw IllegalStateException("Cannot write $name: ${e.message}")
        }
    }

    fun has(target: Any, name: String): Boolean =
        try {
            field(target.javaClass, name)
            true
        } catch (e: IllegalStateException) {
            false
        }

    /** Current middle-generator tiers (1-3). Read from the arena's fields because older API jars lack IArena#getDiamondTier. */
    fun diamondTier(arena: Any): Int = getInt(arena, "diamondTier")

    fun emeraldTier(arena: Any): Int = getInt(arena, "emeraldTier")
}
