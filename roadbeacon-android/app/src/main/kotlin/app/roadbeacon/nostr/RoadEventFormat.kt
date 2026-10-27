package app.roadbeacon.nostr

import app.roadbeacon.model.EventType

/**
 * Constants of the road-event format that every implementation must agree on.
 *
 * The format is the one the independent Roadstr client writes natively (see `nips/road-events.md`):
 * no `alt`, six-decimal coordinates, two-tag votes, and an explicit `expiration` that is
 * `created_at + max(14 days, type lifetime)` — long enough for "still there" votes to extend a
 * report, and never shorter than the type's own lifetime.
 */
object RoadEventFormat {

    /** Floor for relay-side retention (NIP-40): 14 days. */
    const val RELAY_TTL_SECONDS = 1_209_600L

    /**
     * Largest comment an encoder may put in a report frame: 117 + 46 = 163 bytes, the most a
     * MeshCore companion (BLE) link or MeshCore Open's local API accepts for one datagram.
     */
    const val MAX_COMMENT_BYTES = 46

    /**
     * Largest comment a decoder accepts: 117 + 48 = 165 bytes, the radio's own payload limit. Such
     * frames are valid but no encoder produces them.
     */
    const val MAX_DECODED_COMMENT_BYTES = 48

    /** C0 controls and DEL are not allowed in a comment that travels as a frame. */
    fun commentIsFramable(comment: String): Boolean =
        comment.none { it.code < 0x20 || it.code == 0x7F }

    /** What the `expiration` tag must be for a report of [type] created at [createdAt]. */
    fun expirationFor(createdAt: Long, type: EventType): Long =
        createdAt + maxOf(RELAY_TTL_SECONDS, type.defaultTTL)
}
