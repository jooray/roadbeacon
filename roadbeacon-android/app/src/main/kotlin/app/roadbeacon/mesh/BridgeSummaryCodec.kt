package app.roadbeacon.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/** One report in a bridge summary. Coordinates are degrees x 10^6, as in the signed frames. */
data class SummaryEntry(
    val latE6: Int,
    val lonE6: Int,
    val category: Int,
    val ageSeconds: Long,
    val remainingSeconds: Long,
    val stillThere: Int = 0,
    val gone: Int = 0
) {
    /** Local ID of an unverified entry: the same report in the next summary gets the same one. */
    val localId: String get() = BridgeSummaryCodec.localId(category, latE6, lonE6)
}

/** A summary request (frame `0x20`). [sinceSeconds] null = any age; empty [categories] = all. */
data class SummaryRequest(
    val latE6: Int,
    val lonE6: Int,
    val radiusKm: Int,
    val sinceSeconds: Long?,
    val categories: List<Int>
)

/**
 * Bridge summary (`0x10`) and summary request (`0x20`) frames on data_type 0x0150,
 * SPECIFICATION.md section 4.2. Unsigned by design: what they carry is shown as unverified.
 * Conformance: nips/vectors/road-bridge-v1.json.
 */
object BridgeSummaryCodec {
    const val SUMMARY = 0x10
    const val REQUEST = 0x20
    const val MAX_ENTRIES = 5
    const val REQUEST_BYTES = 13
    const val MAX_RADIUS_KM = 25
    private const val HEADER = 5
    private const val ENTRY = 8

    /** Category lifetimes (seconds) by registry value; also the set of known categories. */
    val LIFETIME: Map<Int, Long> = mapOf(
        0 to 4 * 3600L, 1 to 30 * 86400L, 2 to 3600L, 3 to 4 * 3600L, 4 to 24 * 3600L,
        5 to 15 * 86400L, 6 to 4 * 3600L, 7 to 15 * 86400L, 8 to 7 * 86400L, 9 to 4 * 3600L,
        10 to 4 * 3600L, 11 to 3600L, 12 to 30 * 86400L, 13 to 30 * 86400L, 255 to 4 * 3600L
    )

    /** The family of a 0x0150 payload by its first byte: 0 signed frames, 0x10, 0x20, or -1. */
    fun family(payload: ByteArray): Int {
        if (payload.isEmpty()) return -1
        return when (val high = payload[0].toInt() and 0xF0) {
            0x00, SUMMARY, REQUEST -> high
            else -> -1
        }
    }

    // ── duration code ───────────────────────────────────────────────────────────────────────────

    /** Rounded down; null when over 30 days. */
    fun durationEncode(seconds: Long): Int? {
        require(seconds >= 0)
        val minutes = seconds / 60
        if (minutes < 60) return minutes.toInt()
        val hours = seconds / 3600
        if (hours < 24) return (59 + hours).toInt()
        val days = seconds / 86400
        if (days <= 30) return (82 + days).toInt()
        return null
    }

    /** Seconds, or null for 113..255 (reserved, and 255 = more than 30 days). */
    fun durationDecode(code: Int): Long? = when (code) {
        in 0..59 -> code * 60L
        in 60..82 -> (code - 59) * 3600L
        in 83..112 -> (code - 82) * 86400L
        else -> null
    }

    // ── coordinates ─────────────────────────────────────────────────────────────────────────────

    private fun roundHalfAway(num: Long, den: Long): Long {
        val q = abs(num) / den
        val r = abs(num) % den
        val rounded = if (2 * r >= den) q + 1 else q
        return if (num >= 0) rounded else -rounded
    }

    fun ref(e6: Int): Int = roundHalfAway(e6.toLong(), 10_000).toInt()

    /** The int16 offset of [e6] from [ref], or null when it does not fit. */
    fun offset(e6: Int, ref: Int): Int? {
        val off = roundHalfAway(e6.toLong() - ref * 10_000L, 10)
        return if (off in Short.MIN_VALUE..Short.MAX_VALUE) off.toInt() else null
    }

    fun localId(category: Int, latE6: Int, lonE6: Int): String {
        val bytes = ByteBuffer.allocate(9).put(category.toByte()).putInt(latE6).putInt(lonE6).array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "bridge:" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** True when [latE6]/[lonE6] can be carried in a summary about [pointLatE6]/[pointLonE6]. */
    fun fits(pointLatE6: Int, pointLonE6: Int, latE6: Int, lonE6: Int): Boolean =
        offset(latE6, ref(pointLatE6)) != null && offset(lonE6, ref(pointLonE6)) != null

    // ── summary ─────────────────────────────────────────────────────────────────────────────────

    /** Null when [entries] is empty, too long, or an entry does not fit the offset range. */
    fun encodeSummary(pointLatE6: Int, pointLonE6: Int, entries: List<SummaryEntry>): ByteArray? {
        if (entries.isEmpty() || entries.size > MAX_ENTRIES) return null
        val refLat = ref(pointLatE6)
        val refLon = ref(pointLonE6)
        val buffer = ByteBuffer.allocate(HEADER + ENTRY * entries.size)
        buffer.put(SUMMARY.toByte()).putShort(refLat.toShort()).putShort(refLon.toShort())
        for (e in entries) {
            val dLat = offset(e.latE6, refLat) ?: return null
            val dLon = offset(e.lonE6, refLon) ?: return null
            if (e.category !in LIFETIME) return null
            val remaining = durationEncode(e.remainingSeconds) ?: return null
            if (remaining == 0) return null
            val age = durationEncode(e.ageSeconds) ?: 255
            val votes = (e.stillThere.coerceIn(0, 15) shl 4) or e.gone.coerceIn(0, 15)
            buffer.putShort(dLat.toShort()).putShort(dLon.toShort())
            buffer.put(e.category.toByte()).put(age.toByte()).put(remaining.toByte()).put(votes.toByte())
        }
        return buffer.array()
    }

    /**
     * The entries of a summary, with ages and remaining times as decoded (rounded down by the
     * sender). Null for a frame that is dropped as a whole; bad entries are left out.
     */
    fun decodeSummary(frame: ByteArray): List<SummaryEntry>? {
        val n = (frame.size - HEADER) / ENTRY
        if (frame.size < HEADER + ENTRY || (frame.size - HEADER) % ENTRY != 0 || n > MAX_ENTRIES) return null
        if (frame[0].toInt() and 0xFF != SUMMARY) return null
        val buffer = ByteBuffer.wrap(frame)
        buffer.get()
        val refLat = buffer.short.toInt()
        val refLon = buffer.short.toInt()
        if (refLat !in -9000..9000 || refLon !in -18000..18000) return null
        val out = ArrayList<SummaryEntry>(n)
        repeat(n) {
            val dLat = buffer.short.toInt()
            val dLon = buffer.short.toInt()
            val category = buffer.get().toInt() and 0xFF
            val age = durationDecode(buffer.get().toInt() and 0xFF)
            val remaining = durationDecode(buffer.get().toInt() and 0xFF)
            val votes = buffer.get().toInt() and 0xFF
            val latE6 = refLat * 10_000 + dLat * 10
            val lonE6 = refLon * 10_000 + dLon * 10
            val lifetime = LIFETIME[category] ?: return@repeat
            if (remaining == null || remaining == 0L) return@repeat
            if (age == null || age > lifetime) return@repeat
            if (abs(latE6) > 90_000_000 || abs(lonE6) > 180_000_000) return@repeat
            out += SummaryEntry(latE6, lonE6, category, age, remaining, votes ushr 4, votes and 0x0F)
        }
        return out
    }

    // ── request ─────────────────────────────────────────────────────────────────────────────────

    fun encodeRequest(request: SummaryRequest): ByteArray {
        require(request.radiusKm in 0..MAX_RADIUS_KM)
        var mask = 0
        for (c in request.categories) mask = mask or (1 shl (if (c == 255) 15 else c))
        val since = request.sinceSeconds?.let { durationEncode(it) ?: 255 } ?: 255
        return ByteBuffer.allocate(REQUEST_BYTES)
            .put(REQUEST.toByte())
            .putShort(ref(request.latE6).toShort()).putShort(ref(request.lonE6).toShort())
            .put(request.radiusKm.toByte()).put(since.toByte()).putShort(mask.toShort())
            .array()
    }

    /** Null for a request that is dropped. A radius of 0 decodes as the default, 25 km. */
    fun decodeRequest(frame: ByteArray): SummaryRequest? {
        if (frame.size != REQUEST_BYTES || frame[0].toInt() and 0xFF != REQUEST) return null
        val buffer = ByteBuffer.wrap(frame)
        buffer.get()
        val lat = buffer.short.toInt()
        val lon = buffer.short.toInt()
        val radius = buffer.get().toInt() and 0xFF
        val sinceCode = buffer.get().toInt() and 0xFF
        val mask = buffer.short.toInt() and 0xFFFF
        if ((9 until 13).any { frame[it].toInt() != 0 }) return null
        if (lat !in -9000..9000 || lon !in -18000..18000) return null
        if (radius > MAX_RADIUS_KM) return null
        val since = if (sinceCode == 255) null else (durationDecode(sinceCode) ?: return null)
        val categories = (0..13).filter { mask shr it and 1 == 1 } + (if (mask shr 15 and 1 == 1) listOf(255) else emptyList())
        return SummaryRequest(lat * 10_000, lon * 10_000, if (radius == 0) MAX_RADIUS_KM else radius, since, categories)
    }

    // ── claim winner and airtime ────────────────────────────────────────────────────────────────

    /** Sort key of a claim: fewest hops, then the lowest SHA-256(pubkey ‖ area), unsigned. */
    fun claimHash(pubkeyHex: String, area: String): ByteArray {
        val key = pubkeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(key + area.toByteArray(Charsets.UTF_8))
    }

    fun compareClaims(a: Pair<Int, ByteArray>, b: Pair<Int, ByteArray>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        for (i in a.second.indices) {
            val x = a.second[i].toInt() and 0xFF
            val y = b.second[i].toInt() and 0xFF
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Bytes on air for [contentBytes] of channel data: 5 B header + 16 B blocks (+4 B region). */
    fun packetBytes(contentBytes: Int, region: Boolean): Int =
        5 + ceil((3 + contentBytes) / 16.0).toInt() * 16 + if (region) 4 else 0

    /** LoRa time on air (Semtech formula, explicit header, CRC, MeshCore's preamble), milliseconds. */
    fun airtimeMs(sf: Int, bwHz: Int, cr: Int, packetBytes: Int): Double {
        val tSym = 2.0.pow(sf) / bwHz * 1000
        val de = if (tSym > 16) 1 else 0
        val preamble = if (sf <= 8) 32 else 16
        val num = 8 * packetBytes - 4 * sf + 28 + 16
        val symbols = 8 + maxOf(ceil(num.toDouble() / (4 * (sf - 2 * de))).toInt() * cr, 0)
        return (preamble + 4.25 + symbols) * tSym
    }
}
