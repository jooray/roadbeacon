package app.roadbeacon.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import app.roadbeacon.storage.toHex
import java.security.MessageDigest

@Serializable
data class NostrEvent(
    val id: String = "",
    val pubkey: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val kind: Int = 0,
    val tags: List<List<String>> = emptyList(),
    val content: String = "",
    val sig: String = ""
) {
    fun computeId(): String {
        val serialized = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(buildJsonArray {
                for (tag in tags) {
                    add(buildJsonArray {
                        for (item in tag) {
                            add(JsonPrimitive(item))
                        }
                    })
                }
            })
            add(JsonPrimitive(content))
        }
        val json = Json.encodeToString(JsonArray.serializer(), serialized)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(json.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true  // Required: Nostr relays expect all fields including "content"
        }

        fun fromJson(jsonStr: String): NostrEvent =
            json.decodeFromString(serializer(), jsonStr)
    }
}
