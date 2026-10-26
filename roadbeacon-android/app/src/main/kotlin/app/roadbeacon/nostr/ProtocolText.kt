package app.roadbeacon.nostr

/**
 * Text that goes inside signed events and therefore can never be changed without changing every
 * event ID.
 *
 * It still carries the name of the original project — "Roadstr" — because the 110-byte frame
 * format v1 requires the `alt` tag to be exactly this, and an independent client already
 * implements that rule for its radio bridge. Renaming the app does **not** rename these strings.
 * A brand-neutral variant can only arrive as a new frame version, agreed with the other client;
 * see `docs/ecosystem.md`.
 */
object ProtocolText {

    /** `alt` of a kind 1315 report. */
    fun reportAlt(typeName: String): String = "Roadstr: $typeName report"

    /** `alt` of a kind 1316 confirmation with status `still_there`. */
    const val CONFIRMED_ALT = "Roadstr: event confirmed"

    /** `alt` of a kind 1316 confirmation with status `no_longer_there`. */
    const val DENIED_ALT = "Roadstr: event denied"

    /** Relay-side garbage collection time (NIP-40); fixed for every event of this format. */
    const val RELAY_TTL_SECONDS = 1_209_600L
}
