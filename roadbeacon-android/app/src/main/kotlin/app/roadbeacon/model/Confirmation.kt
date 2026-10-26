package app.roadbeacon.model

enum class ConfirmationStatus(val value: String) {
    STILL_THERE("still_there"),
    NO_LONGER_THERE("no_longer_there");

    companion object {
        fun fromValue(value: String): ConfirmationStatus? =
            entries.find { it.value == value }
    }
}

data class Confirmation(
    val id: String,
    val pubkey: String,
    val eventId: String,
    val status: ConfirmationStatus,
    val createdAt: Long
)
