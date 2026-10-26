package app.roadbeacon.nostr

import app.roadbeacon.location.GeohashUtil
import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.model.EventType
import app.roadbeacon.storage.hexToBytes
import app.roadbeacon.storage.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kind 1315/1316 events <-> the 110/134-byte frames carried over MeshCore channel datagrams.
 *
 * The frame carries only what cannot be derived from it: key, time, position, type/status and the
 * signature. Everything else (geohashes, `expiration`, `alt`, tag order, coordinate spelling) is
 * rebuilt by fixed rules, so
 *
 *     event -> frame -> event
 *
 * must give back byte-identical tags, the same pubkey, `created_at` and `sig`. Encoding refuses
 * anything that would not survive that round trip: a different tag order, a comma instead of a
 * dot, an extra decimal or a non-empty comment is a different event ID.
 *
 * Decoding does **not** verify the signature: the caller must (and does, for relay events) run
 * [EventSigner.verify] on the rebuilt event. Only a valid signature makes the frame an event.
 *
 * See `docs/ecosystem.md` and `nips/road-events.md` for the format and its open issues.
 */
object BinaryCodec {

    const val REPORT_SIZE = 110
    const val CONFIRMATION_SIZE = 134

    // Flags: bits 7-4 version (0 = v1), bit 3 class, bit 2 ephemeral hint, bits 1-0 reserved.
    private const val FLAG_CONFIRMATION = 0x08
    private const val FLAG_EPHEMERAL = 0x04
    private const val FLAG_RESERVED = 0x03
    private const val VERSION_MASK = 0xF0
    private const val VERSION_1 = 0x00

    private const val STATUS_STILL_THERE = "still_there"
    private const val STATUS_NO_LONGER_THERE = "no_longer_there"

    private const val MAX_UINT32 = 0xFFFF_FFFFL

    // ── encoding ────────────────────────────────────────────────────────────────────────────────

    /**
     * Pack a signed kind 1315 report. Returns null when the event cannot be represented exactly:
     * see the refusal reasons in `nips/road-events.md` (§3.5).
     */
    fun encodeReport(event: NostrEvent, ephemeral: Boolean = false): ByteArray? {
        val fields = reportFields(event) ?: return null

        var flags = 0
        if (ephemeral) flags = flags or FLAG_EPHEMERAL

        val bytes = ByteBuffer.allocate(REPORT_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(flags.toByte())
            put(event.pubkey.hexToBytes())
            putInt(event.createdAt.toInt())
            putInt(fields.latitudeE7)
            putInt(fields.longitudeE7)
            put(fields.type.value.toByte())
            put(event.sig.hexToBytes())
        }.array()

        return bytes.takeIf { roundTrips(event, it) }
    }

    /**
     * Pack a signed kind 1316 confirmation. The frame repeats no coordinates, so [report] must be
     * the report this confirmation names: a receiver needs its position to rebuild the tags.
     */
    fun encodeConfirmation(
        event: NostrEvent,
        report: NostrEvent,
        ephemeral: Boolean = false
    ): ByteArray? {
        val reportCoords = coordinatesOf(report) ?: return null
        if (!confirmationIsCanonical(event, reportCoords)) return null

        var flags = FLAG_CONFIRMATION
        if (ephemeral) flags = flags or FLAG_EPHEMERAL

        val bytes = ByteBuffer.allocate(CONFIRMATION_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(flags.toByte())
            put(event.pubkey.hexToBytes())
            putInt(event.createdAt.toInt())
            put(event.tags.first { it[0] == "e" }[1].hexToBytes())
            put(if (statusOf(event) == ConfirmationStatus.STILL_THERE) 1 else 0)
            put(event.sig.hexToBytes())
        }.array()

        val rebuilt = decodeConfirmation(bytes, reportCoords.first, reportCoords.second)
        return bytes.takeIf {
            rebuilt != null &&
                sameEventBody(event, rebuilt)
        }
    }

    /** True when the frame decodes back to exactly the event that was packed (signature aside). */
    private fun roundTrips(event: NostrEvent, bytes: ByteArray): Boolean {
        val rebuilt = decodeReport(bytes) ?: return false
        return sameEventBody(event, rebuilt)
    }

    private fun sameEventBody(a: NostrEvent, b: NostrEvent): Boolean =
        a.pubkey == b.pubkey &&
            a.createdAt == b.createdAt &&
            a.kind == b.kind &&
            a.content == b.content &&
            a.tags == b.tags &&
            a.sig == b.sig

    private data class ReportFields(
        val latitudeE7: Int,
        val longitudeE7: Int,
        val type: EventType
    )

    private fun reportFields(event: NostrEvent): ReportFields? {
        return try {
            if (event.kind != 1315) return null
            if (event.content.isNotEmpty()) return null
            if (event.createdAt !in 0..MAX_UINT32) return null
            if (!isHex(event.pubkey, 64) || !isHex(event.sig, 128)) return null

            val type = typeOf(event) ?: return null
            val coords = coordinatesOf(event) ?: return null
            if (!CanonicalCoordinates.inRange(coords.first, coords.second)) return null

            // The cheapest complete check: rebuild the tags from the frame fields and compare them
            // wholesale. Any deviation — order, spelling, alt, expiration, geohashes — is refused.
            val expected = reportTags(coords.first, coords.second, type, event.createdAt)
            if (event.tags != expected) return null
            ReportFields(coords.first, coords.second, type)
        } catch (_: Exception) {
            null
        }
    }

    private fun confirmationIsCanonical(
        event: NostrEvent,
        reportCoords: Pair<Int, Int>
    ): Boolean {
        return try {
            if (event.kind != 1316) return false
            if (event.content.isNotEmpty()) return false
            if (event.createdAt !in 0..MAX_UINT32) return false
            if (!isHex(event.pubkey, 64) || !isHex(event.sig, 128)) return false
            if (!CanonicalCoordinates.inRange(reportCoords.first, reportCoords.second)) return false

            val eTag = event.tags.firstOrNull { it.size == 2 && it[0] == "e" } ?: return false
            if (!isHex(eTag[1], 64)) return false
            val status = statusOf(event) ?: return false

            val expected = confirmationTags(
                eTag[1],
                reportCoords.first,
                reportCoords.second,
                status,
                event.createdAt
            )
            event.tags == expected
        } catch (_: Exception) {
            false
        }
    }

    // ── decoding ────────────────────────────────────────────────────────────────────────────────

    /** Decode a 110-byte report frame. The signature is not checked here — see the class doc. */
    fun decodeReport(data: ByteArray): NostrEvent? {
        if (data.size != REPORT_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val flags = buffer.get().toInt() and 0xFF
        if (!flagsAreValid(flags, expectConfirmation = false)) return null

        val pubkey = ByteArray(32).also { buffer.get(it) }
        val createdAt = buffer.int.toLong() and 0xFFFF_FFFFL
        val latitudeE7 = buffer.int
        val longitudeE7 = buffer.int
        val typeValue = buffer.get().toInt() and 0xFF
        val sig = ByteArray(64).also { buffer.get(it) }

        if (!CanonicalCoordinates.inRange(latitudeE7, longitudeE7)) return null
        val type = EventType.fromValueOrNull(typeValue) ?: return null

        val tags = reportTags(latitudeE7, longitudeE7, type, createdAt)
        return NostrEvent(
            pubkey = pubkey.toHex(),
            createdAt = createdAt,
            kind = 1315,
            tags = tags,
            content = "",
            sig = sig.toHex()
        ).withId()
    }

    /**
     * Decode a 134-byte confirmation frame. [reportLatitudeE7]/[reportLongitudeE7] are the
     * coordinates of the report it names — the frame does not carry them, and a confirmation whose
     * coordinates differ from its report's must be dropped (`REFERENCE_MISMATCH`).
     */
    fun decodeConfirmation(
        data: ByteArray,
        reportLatitudeE7: Int,
        reportLongitudeE7: Int
    ): NostrEvent? {
        if (data.size != CONFIRMATION_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val flags = buffer.get().toInt() and 0xFF
        if (!flagsAreValid(flags, expectConfirmation = true)) return null

        val pubkey = ByteArray(32).also { buffer.get(it) }
        val createdAt = buffer.int.toLong() and 0xFFFF_FFFFL
        val eventId = ByteArray(32).also { buffer.get(it) }
        val statusByte = buffer.get().toInt() and 0xFF
        val sig = ByteArray(64).also { buffer.get(it) }

        if (!CanonicalCoordinates.inRange(reportLatitudeE7, reportLongitudeE7)) return null
        val status = when (statusByte) {
            1 -> ConfirmationStatus.STILL_THERE
            0 -> ConfirmationStatus.NO_LONGER_THERE
            else -> return null
        }

        val tags = confirmationTags(
            eventId.toHex(),
            reportLatitudeE7,
            reportLongitudeE7,
            status,
            createdAt
        )
        return NostrEvent(
            pubkey = pubkey.toHex(),
            createdAt = createdAt,
            kind = 1316,
            tags = tags,
            content = "",
            sig = sig.toHex()
        ).withId()
    }

    /** Decode a confirmation against the report event it names. */
    fun decodeConfirmation(data: ByteArray, report: NostrEvent): NostrEvent? {
        val coords = coordinatesOf(report) ?: return null
        return decodeConfirmation(data, coords.first, coords.second)
    }

    /** Decode any frame whose class is a report; confirmations need their report's coordinates. */
    fun decode(data: ByteArray): NostrEvent? = when {
        data.size == REPORT_SIZE -> decodeReport(data)
        else -> null
    }

    /**
     * Get the referenced event ID from a confirmation binary without full decoding.
     * Useful for looking up the original report to get coordinates.
     */
    fun getConfirmationEventId(data: ByteArray): String? {
        if (data.size != CONFIRMATION_SIZE) return null
        if (!isConfirmation(data)) return null
        return data.copyOfRange(37, 69).toHex()
    }

    fun isReport(data: ByteArray): Boolean {
        if (data.size != REPORT_SIZE) return false
        return (data[0].toInt() and FLAG_CONFIRMATION) == 0
    }

    fun isConfirmation(data: ByteArray): Boolean {
        if (data.size != CONFIRMATION_SIZE) return false
        return (data[0].toInt() and FLAG_CONFIRMATION) != 0
    }

    // ── canonical tags ──────────────────────────────────────────────────────────────────────────

    fun reportTags(
        latitudeE7: Int,
        longitudeE7: Int,
        type: EventType,
        createdAt: Long
    ): List<List<String>> {
        val lat = CanonicalCoordinates.degrees(latitudeE7)
        val lon = CanonicalCoordinates.degrees(longitudeE7)
        return listOf(
            listOf("t", type.typeName),
            listOf("g", GeohashUtil.encode(lat, lon, 4)),
            listOf("g", GeohashUtil.encode(lat, lon, 5)),
            listOf("g", GeohashUtil.encode(lat, lon, 6)),
            listOf("lat", CanonicalCoordinates.format(latitudeE7)),
            listOf("lon", CanonicalCoordinates.format(longitudeE7)),
            listOf("expiration", (createdAt + ProtocolText.RELAY_TTL_SECONDS).toString()),
            listOf("alt", ProtocolText.reportAlt(type.typeName))
        )
    }

    fun confirmationTags(
        eventId: String,
        latitudeE7: Int,
        longitudeE7: Int,
        status: ConfirmationStatus,
        createdAt: Long
    ): List<List<String>> {
        val lat = CanonicalCoordinates.degrees(latitudeE7)
        val lon = CanonicalCoordinates.degrees(longitudeE7)
        return listOf(
            listOf("e", eventId),
            listOf("g", GeohashUtil.encode(lat, lon, 4)),
            listOf("g", GeohashUtil.encode(lat, lon, 5)),
            listOf("g", GeohashUtil.encode(lat, lon, 6)),
            listOf("status", status.value),
            listOf("lat", CanonicalCoordinates.format(latitudeE7)),
            listOf("lon", CanonicalCoordinates.format(longitudeE7)),
            listOf("expiration", (createdAt + ProtocolText.RELAY_TTL_SECONDS).toString()),
            listOf("alt", when (status) {
                ConfirmationStatus.STILL_THERE -> ProtocolText.CONFIRMED_ALT
                ConfirmationStatus.NO_LONGER_THERE -> ProtocolText.DENIED_ALT
            })
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun flagsAreValid(flags: Int, expectConfirmation: Boolean): Boolean {
        if (flags and VERSION_MASK != VERSION_1) return false
        if (flags and FLAG_RESERVED != 0) return false
        val isConfirmation = flags and FLAG_CONFIRMATION != 0
        return isConfirmation == expectConfirmation
    }

    private fun coordinatesOf(event: NostrEvent): Pair<Int, Int>? {
        val latTag = event.tags.find { it.size == 2 && it[0] == "lat" } ?: return null
        val lonTag = event.tags.find { it.size == 2 && it[0] == "lon" } ?: return null
        val lat = CanonicalCoordinates.parseLatitude(latTag[1]) as? CanonicalCoordinates.Parsed.Value ?: return null
        val lon = CanonicalCoordinates.parseLongitude(lonTag[1]) as? CanonicalCoordinates.Parsed.Value ?: return null
        return lat.e7 to lon.e7
    }

    private fun typeOf(event: NostrEvent): EventType? {
        val tag = event.tags.find { it.size == 2 && it[0] == "t" } ?: return null
        return EventType.entries.find { it.typeName == tag[1] }
    }

    private fun statusOf(event: NostrEvent): ConfirmationStatus? {
        val tag = event.tags.find { it.size == 2 && it[0] == "status" } ?: return null
        return when (tag[1]) {
            STATUS_STILL_THERE -> ConfirmationStatus.STILL_THERE
            STATUS_NO_LONGER_THERE -> ConfirmationStatus.NO_LONGER_THERE
            else -> null
        }
    }

    private fun isHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun NostrEvent.withId(): NostrEvent = copy(id = computeId())
}
