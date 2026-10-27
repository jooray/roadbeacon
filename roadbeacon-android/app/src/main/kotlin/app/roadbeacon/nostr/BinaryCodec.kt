package app.roadbeacon.nostr

import app.roadbeacon.location.GeohashUtil
import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.model.EventType
import app.roadbeacon.storage.hexToBytes
import app.roadbeacon.storage.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Kind 1315/1316 events <-> the frames carried over MeshCore channel datagrams.
 *
 * The frame carries only what cannot be derived from it: key, time, position, type, expiration,
 * optional speed limit and comment, and the signature. Everything else (geohashes, tag order,
 * coordinate spelling) is rebuilt by fixed rules, so
 *
 *     event -> frame -> event
 *
 * must give back byte-identical tags, the same pubkey, `created_at`, `content` and `sig`.
 * Encoding refuses anything that would not survive that round trip: a different tag order, a
 * comma instead of a dot, an extra decimal, an extra tag or a comment that does not fit.
 *
 * A vote is two tags (`e`, `status`) and needs nothing else, so the 134-byte confirmation frame is
 * self-contained — unlike the old layout, no lookup of the report is required.
 *
 * Decoding does **not** verify the signature: the caller must (and does, for relay events) run
 * [EventSigner.verify] on the rebuilt event. Only a valid signature makes the frame an event.
 *
 * See `nips/road-events.md`.
 */
object BinaryCodec {

    /** Fixed part of a report frame: flags..content length + signature. */
    const val REPORT_BASE_SIZE = 117

    /** Largest report frame an encoder produces: a comment of [RoadEventFormat.MAX_COMMENT_BYTES]. */
    const val REPORT_MAX_SIZE = REPORT_BASE_SIZE + RoadEventFormat.MAX_COMMENT_BYTES

    /** Largest report frame a decoder accepts (see [RoadEventFormat.MAX_DECODED_COMMENT_BYTES]). */
    const val REPORT_MAX_DECODED_SIZE = REPORT_BASE_SIZE + RoadEventFormat.MAX_DECODED_COMMENT_BYTES

    const val CONFIRMATION_SIZE = 134

    private const val SIG_SIZE = 64
    private const val PUBKEY_SIZE = 32

    // Report offsets.
    private const val OFF_PUBKEY = 1
    private const val OFF_CREATED_AT = 33
    private const val OFF_LATITUDE = 37
    private const val OFF_LONGITUDE = 41
    private const val OFF_TYPE = 45
    private const val OFF_EXPIRATION = 46
    private const val OFF_MAXSPEED = 50
    private const val OFF_CONTENT_LENGTH = 52
    private const val OFF_CONTENT = 53
    private const val OFF_REPORT_SIG = OFF_CONTENT // signature follows the (possibly empty) content

    // Confirmation offsets.
    private const val OFF_CONFIRMATION_ID = 37
    private const val OFF_CONFIRMATION_STATUS = 69
    private const val OFF_CONFIRMATION_SIG = 70

    // Flags: bits 7-4 version (0), bit 3 class, bit 2 ephemeral hint, bits 1-0 reserved.
    private const val FLAG_CONFIRMATION = 0x08
    private const val FLAG_EPHEMERAL = 0x04
    private const val FLAG_RESERVED = 0x03
    private const val VERSION_MASK = 0xF0
    private const val VERSION_1 = 0x00

    private const val STATUS_STILL_THERE = "still_there"
    private const val STATUS_NO_LONGER_THERE = "no_longer_there"

    private const val MAX_UINT32 = 0xFFFF_FFFFL
    private const val MAX_UINT16 = 0xFFFF

    // ── encoding ────────────────────────────────────────────────────────────────────────────────

    /**
     * Pack a signed kind 1315 report. Returns null when the event cannot be represented exactly:
     * wrong kind, extra tags, non-canonical text, a comment over [RoadEventFormat.MAX_COMMENT_BYTES]
     * bytes or with control characters, or anything else that would not decode back to the same event.
     */
    fun encodeReport(event: NostrEvent, ephemeral: Boolean = false): ByteArray? {
        val fields = reportFields(event) ?: return null
        val contentBytes = event.content.toByteArray(StandardCharsets.UTF_8)
        if (contentBytes.size > RoadEventFormat.MAX_COMMENT_BYTES) return null
        if (!RoadEventFormat.commentIsFramable(event.content)) return null

        val size = REPORT_BASE_SIZE + contentBytes.size
        val bytes = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put((if (ephemeral) FLAG_EPHEMERAL else 0).toByte())
            put(event.pubkey.hexToBytes())
            putInt(event.createdAt.toInt())
            putInt(fields.latitudeE6)
            putInt(fields.longitudeE6)
            put(fields.type.value.toByte())
            putInt(fields.expiration.toInt())
            putShort(fields.speedLimitKmh.toShort())
            put(contentBytes.size.toByte())
            put(contentBytes)
            put(event.sig.hexToBytes())
        }.array()

        return bytes.takeIf { roundTrips(event, it) }
    }

    /** Pack a signed kind 1316 vote. Always 134 bytes and independent of any other event. */
    fun encodeConfirmation(event: NostrEvent, ephemeral: Boolean = false): ByteArray? {
        if (!voteIsCanonical(event)) return null
        val eventId = event.tags[0][1]

        val bytes = ByteBuffer.allocate(CONFIRMATION_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put((FLAG_CONFIRMATION or if (ephemeral) FLAG_EPHEMERAL else 0).toByte())
            put(event.pubkey.hexToBytes())
            putInt(event.createdAt.toInt())
            put(eventId.hexToBytes())
            put((if (statusOf(event) == ConfirmationStatus.STILL_THERE) 1 else 0).toByte())
            put(event.sig.hexToBytes())
        }.array()

        val rebuilt = decodeConfirmation(bytes) ?: return null
        return bytes.takeIf { sameEventBody(event, rebuilt) }
    }

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
        val latitudeE6: Int,
        val longitudeE6: Int,
        val type: EventType,
        val expiration: Long,
        val speedLimitKmh: Int
    )

    private fun reportFields(event: NostrEvent): ReportFields? {
        return try {
            if (event.kind != 1315) return null
            if (event.createdAt !in 0..MAX_UINT32) return null
            if (!isHex(event.pubkey, 64) || !isHex(event.sig, 128)) return null

            val type = typeOf(event) ?: return null
            val coords = coordinatesOf(event) ?: return null
            if (!CanonicalCoordinates.inRange(coords.first, coords.second)) return null
            val expiration = expirationOf(event) ?: return null
            val speedLimit = speedLimitOf(event) ?: return null

            // The cheapest complete check: rebuild the tags from the frame fields and compare them
            // wholesale. Any deviation — order, spelling, extra tag — is refused.
            val expected = reportTags(
                coords.first,
                coords.second,
                type,
                expiration,
                if (speedLimit == 0) null else speedLimit
            )
            if (event.tags != expected) return null
            ReportFields(coords.first, coords.second, type, expiration, speedLimit)
        } catch (_: Exception) {
            null
        }
    }

    private fun voteIsCanonical(event: NostrEvent): Boolean {
        return try {
            if (event.kind != 1316) return false
            if (event.content.isNotEmpty()) return false
            if (event.createdAt !in 0..MAX_UINT32) return false
            if (!isHex(event.pubkey, 64) || !isHex(event.sig, 128)) return false

            val status = statusOf(event) ?: return false
            val expected = confirmationTags(event.tags[0][1], status)
            event.tags == expected && isHex(event.tags[0][1], 64)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when [event] is in the current shared layout and could be framed. Anything else —
     * including events of the deprecated layout with an `alt` tag or seven-decimal coordinates —
     * is not supported any more.
     */
    fun isCanonical(event: NostrEvent): Boolean = when (event.kind) {
        1315 -> reportFields(event) != null
        1316 -> voteIsCanonical(event)
        else -> false
    }

    // ── decoding ────────────────────────────────────────────────────────────────────────────────

    /** Decode a report frame. The signature is not checked here — see the class doc. */
    fun decodeReport(data: ByteArray): NostrEvent? {
        if (data.size !in REPORT_BASE_SIZE..REPORT_MAX_DECODED_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val flags = buffer.get().toInt() and 0xFF
        if (!flagsAreValid(flags, expectConfirmation = false)) return null

        val contentLength = data[OFF_CONTENT_LENGTH].toInt() and 0xFF
        if (contentLength > RoadEventFormat.MAX_DECODED_COMMENT_BYTES) return null
        if (data.size != REPORT_BASE_SIZE + contentLength) return null

        val pubkey = ByteArray(PUBKEY_SIZE).also { buffer.get(it) }
        val createdAt = buffer.int.toLong() and MAX_UINT32
        val latitudeE6 = buffer.int
        val longitudeE6 = buffer.int
        val typeValue = buffer.get().toInt() and 0xFF
        val expiration = buffer.int.toLong() and MAX_UINT32
        val speedLimit = buffer.short.toInt() and MAX_UINT16

        if (!CanonicalCoordinates.inRange(latitudeE6, longitudeE6)) return null
        val type = EventType.fromValueOrNull(typeValue) ?: return null
        if (speedLimit > 300) return null
        if (speedLimit in 1..4) return null

        val contentBytes = data.copyOfRange(OFF_CONTENT, OFF_CONTENT + contentLength)
        val content = decodeUtf8(contentBytes) ?: return null
        if (!RoadEventFormat.commentIsFramable(content)) return null
        val sigOffset = OFF_REPORT_SIG + contentLength
        if (sigOffset + SIG_SIZE != data.size) return null
        val sig = data.copyOfRange(sigOffset, sigOffset + SIG_SIZE)

        val tags = reportTags(
            latitudeE6,
            longitudeE6,
            type,
            expiration,
            if (speedLimit == 0) null else speedLimit
        )
        return NostrEvent(
            pubkey = pubkey.toHex(),
            createdAt = createdAt,
            kind = 1315,
            tags = tags,
            content = content,
            sig = sig.toHex()
        ).withId()
    }

    /** Decode a 134-byte vote frame. Self-contained: no report lookup is needed. */
    fun decodeConfirmation(data: ByteArray): NostrEvent? {
        if (data.size != CONFIRMATION_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val flags = buffer.get().toInt() and 0xFF
        if (!flagsAreValid(flags, expectConfirmation = true)) return null

        val pubkey = ByteArray(PUBKEY_SIZE).also { buffer.get(it) }
        val createdAt = buffer.int.toLong() and MAX_UINT32
        val eventId = ByteArray(32).also { buffer.get(it) }
        val statusByte = buffer.get().toInt() and 0xFF
        val sig = ByteArray(SIG_SIZE).also { buffer.get(it) }

        val status = when (statusByte) {
            1 -> ConfirmationStatus.STILL_THERE
            0 -> ConfirmationStatus.NO_LONGER_THERE
            else -> return null
        }

        val tags = confirmationTags(eventId.toHex(), status)
        return NostrEvent(
            pubkey = pubkey.toHex(),
            createdAt = createdAt,
            kind = 1316,
            tags = tags,
            content = "",
            sig = sig.toHex()
        ).withId()
    }

    /** Decode any frame, choosing by the class bit. */
    fun decode(data: ByteArray): NostrEvent? = when {
        data.isEmpty() -> null
        data[0].toInt() and FLAG_CONFIRMATION != 0 -> decodeConfirmation(data)
        else -> decodeReport(data)
    }

    /**
     * Get the referenced event ID from a confirmation binary without full decoding.
     * Useful for looking up the report before applying a vote.
     */
    fun getConfirmationEventId(data: ByteArray): String? {
        if (data.size != CONFIRMATION_SIZE) return null
        if (!isConfirmation(data)) return null
        return data.copyOfRange(OFF_CONFIRMATION_ID, OFF_CONFIRMATION_ID + 32).toHex()
    }

    fun isReport(data: ByteArray): Boolean {
        if (data.size !in REPORT_BASE_SIZE..REPORT_MAX_DECODED_SIZE) return false
        return (data[0].toInt() and FLAG_CONFIRMATION) == 0
    }

    fun isConfirmation(data: ByteArray): Boolean {
        if (data.size != CONFIRMATION_SIZE) return false
        return (data[0].toInt() and FLAG_CONFIRMATION) != 0
    }

    // ── canonical tags ──────────────────────────────────────────────────────────────────────────

    /**
     * Tags of a report in the shared layout: `lat, lon, g4, g5, g6, t, expiration[, maxspeed]`.
     * Geohashes are derived from the same six-decimal value that the text names.
     */
    fun reportTags(
        latitudeE6: Int,
        longitudeE6: Int,
        type: EventType,
        expiration: Long,
        speedLimitKmh: Int? = null
    ): List<List<String>> {
        val lat = CanonicalCoordinates.degrees(latitudeE6)
        val lon = CanonicalCoordinates.degrees(longitudeE6)
        return buildList {
            add(listOf("lat", CanonicalCoordinates.format(latitudeE6)))
            add(listOf("lon", CanonicalCoordinates.format(longitudeE6)))
            add(listOf("g", GeohashUtil.encode(lat, lon, 4)))
            add(listOf("g", GeohashUtil.encode(lat, lon, 5)))
            add(listOf("g", GeohashUtil.encode(lat, lon, 6)))
            add(listOf("t", type.typeName))
            add(listOf("expiration", expiration.toString()))
            if (speedLimitKmh != null) add(listOf("maxspeed", speedLimitKmh.toString()))
        }
    }

    /** Tags of a vote: `e, status`. Nothing else. */
    fun confirmationTags(eventId: String, status: ConfirmationStatus): List<List<String>> = listOf(
        listOf("e", eventId),
        listOf("status", status.value)
    )

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
        val lat = CanonicalCoordinates.parseLatitude(latTag[1]) as? CanonicalCoordinates.Parsed.Value
            ?: return null
        val lon = CanonicalCoordinates.parseLongitude(lonTag[1]) as? CanonicalCoordinates.Parsed.Value
            ?: return null
        return lat.e6 to lon.e6
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

    private fun expirationOf(event: NostrEvent): Long? {
        val tag = event.tags.singleOrNull { it.size == 2 && it[0] == "expiration" } ?: return null
        return tag[1].toLongOrNull()?.takeIf { it in 0..MAX_UINT32 && it.toString() == tag[1] }
    }

    /** 0 means absent, 5..300 a speed limit. */
    private fun speedLimitOf(event: NostrEvent): Int? {
        val tag = event.tags.singleOrNull { it.size == 2 && it[0] == "maxspeed" } ?: return 0
        val value = tag[1].toIntOrNull() ?: return null
        if (value !in 5..300) return null
        return value.takeIf { it.toString() == tag[1] }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun isHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun NostrEvent.withId(): NostrEvent = copy(id = computeId())
}
