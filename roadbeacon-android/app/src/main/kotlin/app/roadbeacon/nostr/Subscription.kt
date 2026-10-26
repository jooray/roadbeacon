package app.roadbeacon.nostr

import kotlinx.serialization.json.*

data class NostrFilter(
    val kinds: List<Int> = emptyList(),
    val gTags: List<String> = emptyList(),
    val eTags: List<String> = emptyList(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (kinds.isNotEmpty()) {
            put("kinds", buildJsonArray { kinds.forEach { add(JsonPrimitive(it)) } })
        }
        if (gTags.isNotEmpty()) {
            put("#g", buildJsonArray { gTags.forEach { add(JsonPrimitive(it)) } })
        }
        if (eTags.isNotEmpty()) {
            put("#e", buildJsonArray { eTags.forEach { add(JsonPrimitive(it)) } })
        }
        since?.let { put("since", JsonPrimitive(it)) }
        until?.let { put("until", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
    }
}
