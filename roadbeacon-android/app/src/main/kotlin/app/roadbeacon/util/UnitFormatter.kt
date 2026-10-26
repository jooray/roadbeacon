package app.roadbeacon.util

import kotlin.math.roundToInt

enum class UnitSystem {
    METRIC,
    IMPERIAL;

    companion object {
        fun fromStored(value: String?): UnitSystem =
            if (value?.lowercase() == "imperial") IMPERIAL else METRIC
    }
}

/**
 * Central place for formatting distances and speeds for display.
 *
 * Storage and internal logic stay metric (meters, km/h); only the rendered
 * string changes when the user picks imperial units.
 */
object UnitFormatter {

    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_MILE = 1609.34
    private const val MPH_PER_KMH = 0.621371

    /** Format a distance given in meters. */
    fun formatDistanceMeters(meters: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC ->
            if (meters >= 1000) "%.1f km".format(meters / 1000.0)
            else "${meters.roundToInt()} m"
        UnitSystem.IMPERIAL -> {
            val miles = meters / METERS_PER_MILE
            if (miles >= 0.5) "%.1f mi".format(miles)
            else "${(meters * FEET_PER_METER).roundToInt()} ft"
        }
    }

    /** Format a speed given in km/h. */
    fun formatSpeedKmh(kmh: Int, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "$kmh km/h"
        UnitSystem.IMPERIAL -> "${(kmh * MPH_PER_KMH).roundToInt()} mph"
    }
}
