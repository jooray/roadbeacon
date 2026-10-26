package app.roadbeacon.model

/**
 * Road report categories, as defined once for every client of the format.
 *
 * [value] is the byte carried in the compact frame and [typeName] is the `t` tag. Both are part of
 * the wire format: renaming or renumbering a category changes every event that uses it. The table
 * (including the effective lifetimes) is shared with the independent Roadstr client — see
 * `nips/road-events.md`.
 *
 * Lifetimes are display lifetimes, not relay retention: an event's `expiration` tag is
 * `created_at + max(14 days, lifetime)`, so votes can keep a report alive.
 */
enum class EventType(
    val value: Int,
    val typeName: String,
    val defaultTTL: Long,
    val colorHex: String,
    val displayName: String,
    val iconName: String
) {
    POLICE(0, "police", 4 * 3600L, "#0000FF", "Police", "ic_action_alert"),
    POLICE_STATION(12, "police_station", 30 * 86400L, "#1E3A8A", "Police Station", "ic_action_flag"),
    SPEED_CAMERA(1, "speed_camera", 30 * 86400L, "#800080", "Speed Camera", "ic_action_max_speed"),
    TRAFFIC_JAM(2, "traffic_jam", 3600L, "#FF8C00", "Traffic Jam", "ic_action_car"),
    ACCIDENT(3, "accident", 4 * 3600L, "#FF0000", "Accident", "ic_action_car_info"),
    ROAD_CLOSURE(4, "road_closure", 24 * 3600L, "#8B0000", "Road Closure", "ic_action_stop"),
    CONSTRUCTION(5, "construction", 15 * 86400L, "#FFD700", "Construction", "ic_action_road_works_dark"),
    HAZARD(6, "hazard", 4 * 3600L, "#FF4500", "Hazard", "ic_action_placard_hazard"),
    ROAD_CONDITION(7, "road_condition", 15 * 86400L, "#4682B4", "Road Condition", "ic_action_offroad"),
    POTHOLE(8, "pothole", 7 * 86400L, "#795548", "Pothole", "ic_action_circle"),
    FOG(9, "fog", 4 * 3600L, "#9E9E9E", "Fog", "ic_action_wind"),
    ICE(10, "ice", 4 * 3600L, "#00CED1", "Ice", "ic_action_alert_circle"),
    ANIMAL(11, "animal", 3600L, "#4CAF50", "Animal", "ic_action_flag"),
    SURVEILLANCE_CAMERA(13, "surveillance_camera", 30 * 86400L, "#4C1D95", "Surveillance Camera", "ic_action_alert_circle"),
    OTHER(255, "other", 4 * 3600L, "#808080", "Other", "ic_action_info");

    companion object {
        fun fromValue(value: Int): EventType =
            entries.find { it.value == value } ?: OTHER

        /**
         * Like [fromValue], but null for a value outside the registry. Used when decoding frames:
         * an unknown type must be dropped, not silently turned into "other", because that would
         * change the `t` tag and with it the event ID.
         */
        fun fromValueOrNull(value: Int): EventType? =
            entries.find { it.value == value }

        fun fromTypeName(name: String): EventType =
            entries.find { it.typeName == name } ?: OTHER
    }
}
