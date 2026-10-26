package app.roadbeacon.model

data class RoadEvent(
    val id: String,
    val pubkey: String,
    val type: EventType,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    val expiration: Long,
    val content: String = "",
    val geohashes: List<String> = emptyList(),
    val confirmCount: Int = 0,
    val denyCount: Int = 0,
    val alreadyAlerted: Boolean = false
)
