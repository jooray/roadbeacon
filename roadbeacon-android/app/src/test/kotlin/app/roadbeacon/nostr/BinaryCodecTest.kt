package app.roadbeacon.nostr

import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.model.EventType
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * The frames are a wire format shared with an independent client (`roadstrapp/roadstr-app`), so
 * these tests pin the canonical bytes rather than our own conventions. The worked example is one
 * of its published, independently generated interop vectors.
 */
class BinaryCodecTest {

    // A throw-away test key from the published interop vector set.
    private val vectorPubkey = "96cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453"
    private val vectorReportId = "c4fc8b221dea97c31b73423abafde550d64d06e3b4df771c1d41ee328b3e251a"
    private val vectorFrame = (
        "0096cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453" +
            "6553f1001d1eed7c0166ebab00" +
            "a10bc13575ef963531acc54afc13445f198fe8b507d07d0fd4f2710e358bae17" +
            "c1962feca23cc7022d31e5b813430e6ad74e3e8cc7df126d7bce45798bd0ee05"
        ).hexToBytes()

    @Test
    fun `published vector decodes to the exact event id and tags`() {
        val decoded = BinaryCodec.decodeReport(vectorFrame)

        assertNotNull(decoded)
        assertEquals(1315, decoded!!.kind)
        assertEquals(vectorPubkey, decoded.pubkey)
        assertEquals(1700000000L, decoded.createdAt)
        assertEquals(vectorReportId, decoded.id)
        assertEquals(
            listOf(
                listOf("t", "police"),
                listOf("g", "u09t"),
                listOf("g", "u09tv"),
                listOf("g", "u09tvw"),
                listOf("lat", "48.8566140"),
                listOf("lon", "2.3522219"),
                listOf("expiration", "1701209600"),
                listOf("alt", "Roadstr: police report")
            ),
            decoded.tags
        )
    }

    @Test
    fun `published vector re-encodes byte for byte`() {
        val decoded = BinaryCodec.decodeReport(vectorFrame)!!
        assertArrayEquals(vectorFrame, BinaryCodec.encodeReport(decoded))
    }

    @Test
    fun `encode report produces correct size`() {
        assertArrayEquals(vectorFrame, BinaryCodec.encodeReport(reportEvent())!!)
        assertEquals(BinaryCodec.REPORT_SIZE, BinaryCodec.encodeReport(reportEvent())!!.size)
    }

    @Test
    fun `encode confirmation produces correct size`() {
        val encoded = BinaryCodec.encodeConfirmation(confirmationEvent(), reportEvent())!!
        assertEquals(BinaryCodec.CONFIRMATION_SIZE, encoded.size)
    }

    @Test
    fun `round trip report preserves tags, key, time and signature`() {
        val event = reportEvent()
        val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)!!

        assertEquals(event.pubkey, decoded.pubkey)
        assertEquals(event.createdAt, decoded.createdAt)
        assertEquals(event.sig, decoded.sig)
        assertEquals(event.tags, decoded.tags)
        assertEquals(event.id, decoded.id)
    }

    @Test
    fun `round trip confirmation preserves tags`() {
        val report = reportEvent()
        val event = confirmationEvent()
        val encoded = BinaryCodec.encodeConfirmation(event, report)!!
        val decoded = BinaryCodec.decodeConfirmation(encoded, report)!!

        assertEquals(event.tags, decoded.tags)
        assertEquals(event.pubkey, decoded.pubkey)
        assertEquals(event.sig, decoded.sig)
    }

    @Test
    fun `truncating the seventh decimal is refused, not silently rounded`() {
        // 35.1976312 * 1e7 is 351976311.99999994 in IEEE-754: a truncating encoder lands one unit
        // short and produces a frame nobody can verify. Every event this encoder emits round-trips.
        val event = reportEvent(lat = "35.1976312", lon = "139.6917000")
        val encoded = BinaryCodec.encodeReport(event)
        assertNotNull(encoded)
        assertEquals(event.tags, BinaryCodec.decodeReport(encoded!!)!!.tags)
    }

    @Test
    fun `coordinate formatting ignores the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("48.8566140", CanonicalCoordinates.format(488566140))
            assertEquals("-2.3522219", CanonicalCoordinates.format(-23522219))
            assertTrue(CanonicalCoordinates.parseLatitude("48,8566140") is CanonicalCoordinates.Parsed.Malformed)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `coordinate encoding boundary values`() {
        for ((lat, lon) in listOf(90.0 to 180.0, -90.0 to -180.0, 0.0 to 0.0, -48.8566140 to -2.3522219)) {
            val event = reportEvent(
                lat = CanonicalCoordinates.format(CanonicalCoordinates.latitudeE7(lat)!!),
                lon = CanonicalCoordinates.format(CanonicalCoordinates.longitudeE7(lon)!!)
            )
            val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)
            assertNotNull("decode failed for $lat,$lon", decoded)
            assertEquals(event.tags, decoded!!.tags)
        }
    }

    @Test
    fun `all event types encode and decode`() {
        for (type in EventType.entries) {
            val event = reportEvent(type = type)
            val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)
            assertNotNull("decode failed for ${type.typeName}", decoded)
            assertEquals(type.typeName, decoded!!.tags.first { it[0] == "t" }[1])
        }
    }

    @Test
    fun `flags byte distinguishes report from confirmation and carries the ephemeral hint`() {
        val report = BinaryCodec.encodeReport(reportEvent(), ephemeral = false)!!
        val ephemeral = BinaryCodec.encodeReport(reportEvent(), ephemeral = true)!!
        val confirmation = BinaryCodec.encodeConfirmation(confirmationEvent(), reportEvent())!!

        assertTrue(BinaryCodec.isReport(report))
        assertFalse(BinaryCodec.isConfirmation(report))
        assertTrue(BinaryCodec.isConfirmation(confirmation))
        assertFalse(BinaryCodec.isReport(confirmation))

        assertEquals(0, report[0].toInt() and 0x04)
        assertNotEquals(0, ephemeral[0].toInt() and 0x04)
        // The flags byte is not covered by the signature, so everything else is identical.
        assertArrayEquals(report.copyOfRange(1, report.size), ephemeral.copyOfRange(1, ephemeral.size))
    }

    @Test
    fun `frames with a reserved version or reserved bits are refused`() {
        val versionOne = vectorFrame.copyOf().also { it[0] = 0x10.toByte() }
        val reserved = vectorFrame.copyOf().also { it[0] = 0x01.toByte() }
        assertNull(BinaryCodec.decodeReport(versionOne))
        assertNull(BinaryCodec.decodeReport(reserved))
    }

    @Test
    fun `unknown type and out-of-range coordinates are refused`() {
        val unknownType = vectorFrame.copyOf().also { it[45] = 0x0C.toByte() }
        val badCoordinate = vectorFrame.copyOf().also {
            it[37] = 0x3F; it[38] = 0xFF.toByte(); it[39] = 0xFF.toByte(); it[40] = 0xFF.toByte()
        }
        assertNull(BinaryCodec.decodeReport(unknownType))
        assertNull(BinaryCodec.decodeReport(badCoordinate))
    }

    @Test
    fun `non-representable events are refused with null`() {
        // Non-empty comment.
        assertNull(BinaryCodec.encodeReport(reportEvent(content = "police checking seatbelts")))
        // Brand changed: a different alt changes the id and cannot be rebuilt from a frame.
        assertNull(BinaryCodec.encodeReport(reportEvent(alt = "Roadbeacon: police report")))
        // Extra tag.
        assertNull(BinaryCodec.encodeReport(reportEvent(extraTag = listOf("client", "roadbeacon"))))
        // Wrong tag order.
        assertNull(BinaryCodec.encodeReport(reportEvent(moveAltFirst = true)))
        // Expiration not derived from created_at.
        assertNull(BinaryCodec.encodeReport(reportEvent(expiration = "1700007200")))
        // Decimal comma from a comma-locale phone.
        assertNull(BinaryCodec.encodeReport(reportEvent(lat = "48,8566140")))
        // Unknown category.
        assertNull(BinaryCodec.encodeReport(reportEvent(typeName = "surveillance_camera")))
    }

    @Test
    fun `confirmation needs the report it names`() {
        val confirmation = confirmationEvent()
        // No report: the coordinates that the tags must repeat are unknown.
        assertNull(BinaryCodec.encodeConfirmation(confirmation, reportEvent(lat = "48.0000000")))
        // Coordinates of a different report than the tags repeat.
        assertNull(BinaryCodec.encodeConfirmation(confirmation, reportEvent(lat = "48.8566141")))
    }

    @Test
    fun `wrong sizes and a class bit that disagrees with the length are refused`() {
        assertNull(BinaryCodec.decodeReport(ByteArray(50)))
        assertNull(BinaryCodec.decodeConfirmation(ByteArray(50), 488566140, 23522219))
        assertNull(BinaryCodec.decodeReport(ByteArray(BinaryCodec.CONFIRMATION_SIZE)))
        assertNull(BinaryCodec.decodeConfirmation(ByteArray(BinaryCodec.REPORT_SIZE), 488566140, 23522219))
    }

    @Test
    fun `generic decode handles reports only`() {
        val report = BinaryCodec.encodeReport(reportEvent())!!
        val confirmation = BinaryCodec.encodeConfirmation(confirmationEvent(), reportEvent())!!

        assertEquals(1315, BinaryCodec.decode(report)!!.kind)
        assertNull(BinaryCodec.decode(confirmation)) // confirmations need their report's coordinates
    }

    @Test
    fun `getConfirmationEventId extracts the referenced id`() {
        val encoded = BinaryCodec.encodeConfirmation(confirmationEvent(), reportEvent())!!
        assertEquals(confirmationEvent().tags.first { it[0] == "e" }[1], BinaryCodec.getConfirmationEventId(encoded))
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun reportEvent(
        type: EventType = EventType.POLICE,
        typeName: String? = null,
        lat: String = "48.8566140",
        lon: String = "2.3522219",
        content: String = "",
        alt: String = ProtocolText.reportAlt(type.typeName),
        expiration: String = "1701209600",
        extraTag: List<String>? = null,
        moveAltFirst: Boolean = false
    ): NostrEvent {
        // Geohashes follow the coordinates, exactly as a real event must; when the coordinate text
        // is not canonical the placeholders are irrelevant because encoding is refused anyway.
        val latE7 = (CanonicalCoordinates.parseLatitude(lat) as? CanonicalCoordinates.Parsed.Value)
        val lonE7 = (CanonicalCoordinates.parseLongitude(lon) as? CanonicalCoordinates.Parsed.Value)
        val geohashes = if (latE7 != null && lonE7 != null) {
            val latDeg = CanonicalCoordinates.degrees(latE7.e7)
            val lonDeg = CanonicalCoordinates.degrees(lonE7.e7)
            listOf(4, 5, 6).map { app.roadbeacon.location.GeohashUtil.encode(latDeg, lonDeg, it) }
        } else {
            listOf("u09t", "u09tv", "u09tvw")
        }
        val tags = mutableListOf(
            listOf("t", typeName ?: type.typeName),
            listOf("g", geohashes[0]),
            listOf("g", geohashes[1]),
            listOf("g", geohashes[2]),
            listOf("lat", lat),
            listOf("lon", lon),
            listOf("expiration", expiration),
            listOf("alt", alt)
        )
        if (extraTag != null) tags.add(extraTag)
        if (moveAltFirst) tags.add(0, tags.removeAt(tags.lastIndex))
        return NostrEvent(
            pubkey = vectorPubkey,
            createdAt = 1700000000L,
            kind = 1315,
            tags = tags,
            content = content,
            sig = "a10bc13575ef963531acc54afc13445f198fe8b507d07d0fd4f2710e358bae17" +
                "c1962feca23cc7022d31e5b813430e6ad74e3e8cc7df126d7bce45798bd0ee05"
        ).let { it.copy(id = it.computeId()) }
    }

    private fun confirmationEvent(): NostrEvent {
        val tags = BinaryCodec.confirmationTags(
            eventId = vectorReportId,
            latitudeE7 = 488566140,
            longitudeE7 = 23522219,
            status = ConfirmationStatus.STILL_THERE,
            createdAt = 1700003600L
        )
        return NostrEvent(
            pubkey = vectorPubkey,
            createdAt = 1700003600L,
            kind = 1316,
            tags = tags,
            content = "",
            sig = "b".repeat(128)
        ).let { it.copy(id = it.computeId()) }
    }
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
