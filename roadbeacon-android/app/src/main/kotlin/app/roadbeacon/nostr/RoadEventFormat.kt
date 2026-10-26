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

    /** Largest comment that fits in one report frame (165 - 117 bytes of fixed fields). */
    const val MAX_COMMENT_BYTES = 48

    /** What the `expiration` tag must be for a report of [type] created at [createdAt]. */
    fun expirationFor(createdAt: Long, type: EventType): Long =
        createdAt + maxOf(RELAY_TTL_SECONDS, type.defaultTTL)
}
