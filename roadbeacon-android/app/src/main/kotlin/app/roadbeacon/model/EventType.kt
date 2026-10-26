package app.roadbeacon.model

enum class EventType(
    val value: Int,
    val typeName: String,
    val defaultTTL: Long,
    val colorHex: String,
    val displayName: String,
    val iconName: String
) {
    POLICE(0, "police", 7200L, "#0000FF", "Police", "ic_action_alert"),
    SPEED_CAMERA(1, "speed_camera", 2592000L, "#800080", "Speed Camera", "ic_action_max_speed"),
    TRAFFIC_JAM(2, "traffic_jam", 3600L, "#FF8C00", "Traffic Jam", "ic_action_car"),
    ACCIDENT(3, "accident", 10800L, "#FF0000", "Accident", "ic_action_car_info"),
    ROAD_CLOSURE(4, "road_closure", 604800L, "#8B0000", "Road Closure", "ic_action_stop"),
    CONSTRUCTION(5, "construction", 604800L, "#FFD700", "Construction", "ic_action_road_works_dark"),
    HAZARD(6, "hazard", 14400L, "#FF4500", "Hazard", "ic_action_placard_hazard"),
    ROAD_CONDITION(7, "road_condition", 21600L, "#4682B4", "Road Condition", "ic_action_offroad"),
    POTHOLE(8, "pothole", 604800L, "#795548", "Pothole", "ic_action_circle"),
    FOG(9, "fog", 10800L, "#9E9E9E", "Fog", "ic_action_wind"),
    ICE(10, "ice", 21600L, "#00CED1", "Ice", "ic_action_alert_circle"),
    ANIMAL(11, "animal", 3600L, "#4CAF50", "Animal", "ic_action_flag"),
    OTHER(255, "other", 7200L, "#808080", "Other", "ic_action_info");

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
