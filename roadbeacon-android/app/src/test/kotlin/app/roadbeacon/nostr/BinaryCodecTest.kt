package app.roadbeacon.nostr

import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.model.EventType
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Frames are a wire format shared with the independent Roadstr client, so these tests pin the
 * canonical bytes rather than our own conventions. The worked example below was produced by a
 * separate Python implementation and its event ID was recomputed from the NIP-01 serialisation.
 */
class BinaryCodecTest {

    private val pubkey = "96cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453"
    private val reportId = "dac79ddf0e4aeb64f56f454497afd54e7926e19a5d3c062f75c25e226f4fcbca"
    private val vectorFrame = (
        "00" +
            "96cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453" +
            "6553f100" +
            "02e97e26" +
            "0023e45e" +
            "00" +
            "65666600" +
            "0000" +
            "00" +
            "aa".repeat(64)
        ).hexToBytes()

    @Test
    fun `published-style vector decodes to the exact event id and tags`() {
        val decoded = BinaryCodec.decodeReport(vectorFrame)

        assertNotNull(decoded)
        assertEquals(1315, decoded!!.kind)
        assertEquals(pubkey, decoded.pubkey)
        assertEquals(1700000000L, decoded.createdAt)
        assertEquals(reportId, decoded.id)
        assertEquals("", decoded.content)
        assertEquals(
            listOf(
                listOf("lat", "48.856614"),
                listOf("lon", "2.352222"),
                listOf("g", "u09t"),
                listOf("g", "u09tv"),
                listOf("g", "u09tvw"),
                listOf("t", "police"),
                listOf("expiration", "1701209600")
            ),
            decoded.tags
        )
    }

    @Test
    fun `vector re-encodes byte for byte`() {
        val decoded = BinaryCodec.decodeReport(vectorFrame)!!
        assertArrayEquals(vectorFrame, BinaryCodec.encodeReport(decoded))
    }

    @Test
    fun `report without comment is 117 bytes`() {
        val encoded = BinaryCodec.encodeReport(report())!!
        assertEquals(BinaryCodec.REPORT_BASE_SIZE, encoded.size)
        assertEquals(117, encoded.size)
        assertArrayEquals(vectorFrame, encoded)
    }

    @Test
    fun `frame with maxspeed and comment matches the published offsets`() {
        val expected = (
            "00" +
                "96cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453" +
                "6553f100" + "02e97e26" + "0023e45e" + "01" + "65666600" + "0032" + "03" + "616263" +
                "cc".repeat(64)
            ).hexToBytes()
        val event = report(
            type = EventType.SPEED_CAMERA,
            content = "abc",
            speedLimitKmh = 50,
            sig = "cc".repeat(64)
        )
        assertArrayEquals(expected, BinaryCodec.encodeReport(event))
        assertEquals(120, expected.size)
    }

    @Test
    fun `round trip report preserves tags, content, key, time and signature`() {
        val event = report(content = "police checking seatbelts")
        val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)!!

        assertEquals(event.pubkey, decoded.pubkey)
        assertEquals(event.createdAt, decoded.createdAt)
        assertEquals(event.sig, decoded.sig)
        assertEquals(event.tags, decoded.tags)
        assertEquals(event.content, decoded.content)
        assertEquals(event.id, decoded.id)
    }

    @Test
    fun `vote is 134 bytes and needs no report to decode`() {
        val report = report()
        val vote = vote(report.id)
        val encoded = BinaryCodec.encodeConfirmation(vote)!!

        assertEquals(134, encoded.size)
        val decoded = BinaryCodec.decodeConfirmation(encoded)!!
        assertEquals(report.id, decoded.tags[0][1])
        assertEquals("still_there", decoded.tags[1][1])
        assertEquals(vote.id, decoded.id)
        // The frame is valid even though the report was never seen.
        assertNotNull(BinaryCodec.decode(encoded))
    }

    @Test
    fun `comment of 46 bytes fits, 47 does not`() {
        val fits = report(content = "a".repeat(RoadEventFormat.MAX_COMMENT_BYTES))
        val encoded = BinaryCodec.encodeReport(fits)!!
        assertEquals(163, encoded.size)
        assertEquals(fits.content, BinaryCodec.decodeReport(encoded)!!.content)

        assertNull(BinaryCodec.encodeReport(report(content = "a".repeat(47))))
    }

    @Test
    fun `comment limit is counted in UTF-8 bytes, not characters`() {
        val twoByte = "ě".repeat(23) // 46 bytes
        assertEquals(46, twoByte.toByteArray(Charsets.UTF_8).size)
        assertNotNull(BinaryCodec.encodeReport(report(content = twoByte)))

        val tooLong = "ě".repeat(24) // 48 bytes: decodable, never encoded
        assertNull(BinaryCodec.encodeReport(report(content = tooLong)))
    }

    @Test
    fun `control characters keep a comment off the radio`() {
        assertNull(BinaryCodec.encodeReport(report(content = "line\nbreak")))
        assertNull(BinaryCodec.encodeReport(report(content = "tab\there")))
        assertNull(BinaryCodec.encodeReport(report(content = "del\u007F")))
        assertNotNull(BinaryCodec.encodeReport(report(content = "Pozor, ľad!")))
    }

    @Test
    fun `maxspeed round trips and must be 5 to 300`() {
        for (limit in listOf(5, 50, 300)) {
            val event = report(speedLimitKmh = limit)
            val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)!!
            assertEquals(limit.toString(), decoded.tags.last()[1])
        }
        assertNull(BinaryCodec.encodeReport(report(extraTag = listOf("maxspeed", "4"))))
        assertNull(BinaryCodec.encodeReport(report(extraTag = listOf("maxspeed", "301"))))
        assertNull(BinaryCodec.encodeReport(report(extraTag = listOf("maxspeed", "050"))))
    }

    @Test
    fun `every registry type encodes and decodes`() {
        for (type in EventType.entries) {
            val event = report(type = type)
            val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)
            assertNotNull("decode failed for ${type.typeName}", decoded)
            assertEquals(type.typeName, decoded!!.tags.first { it[0] == "t" }[1])
        }
    }

    @Test
    fun `boundary and negative coordinates round trip`() {
        for ((latE6, lonE6) in listOf(
            90_000_000 to 180_000_000,
            -90_000_000 to -180_000_000,
            0 to 0,
            -48_856_614 to -2_352_222
        )) {
            val event = report(latE6 = latE6, lonE6 = lonE6)
            val decoded = BinaryCodec.decodeReport(BinaryCodec.encodeReport(event)!!)
            assertNotNull("decode failed for $latE6,$lonE6", decoded)
            assertEquals(event.tags, decoded!!.tags)
        }
    }

    @Test
    fun `coordinate formatting ignores the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("48.856614", CanonicalCoordinates.format(48_856_614))
            assertEquals("-2.352222", CanonicalCoordinates.format(-2_352_222))
            assertTrue(CanonicalCoordinates.parseLatitude("48,856614") is CanonicalCoordinates.Parsed.Malformed)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `non-canonical reports are refused`() {
        // The previous layout's alt tag.
        assertNull(BinaryCodec.encodeReport(report(extraTag = listOf("alt", "Road event: police"))))
        // Seven decimals from the old client.
        assertNull(BinaryCodec.encodeReport(report(lat = "48.8566140", lon = "2.3522220")))
        // Decimal comma from a comma-locale phone.
        assertNull(BinaryCodec.encodeReport(report(lat = "48,856614")))
        // Tag order is part of the identity.
        assertNull(BinaryCodec.encodeReport(report(moveTypeFirst = true)))
        // Extra tag.
        assertNull(BinaryCodec.encodeReport(report(extraTag = listOf("client", "roadbeacon"))))
        // Expiration not canonical text.
        assertNull(BinaryCodec.encodeReport(report(expiration = "01701209600")))
        // Unknown category.
        assertNull(BinaryCodec.encodeReport(report(typeName = "roadworks")))
    }

    @Test
    fun `vote tags are exactly e and status`() {
        val report = report()
        assertNull(BinaryCodec.encodeConfirmation(vote(report.id).copy(content = "hm")))
        assertNull(
            BinaryCodec.encodeConfirmation(
                vote(report.id).copy(tags = vote(report.id).tags + listOf(listOf("lat", "48.856614")))
            )
        )
        assertNull(
            BinaryCodec.encodeConfirmation(
                vote(report.id).copy(tags = listOf(listOf("e", report.id), listOf("status", "maybe")))
            )
        )
    }

    @Test
    fun `frames with a reserved version, reserved bits or the wrong class are refused`() {
        fun tamper(offset: Int, value: Int) = vectorFrame.copyOf().also { it[offset] = value.toByte() }

        assertNull(BinaryCodec.decodeReport(tamper(0, 0x10))) // version 1
        assertNull(BinaryCodec.decodeReport(tamper(0, 0x01))) // reserved bit
        assertNull(BinaryCodec.decodeReport(tamper(0, 0x08))) // confirmation class, report length
        assertNull(BinaryCodec.decodeReport(tamper(45, 14))) // unknown type
        assertNull(BinaryCodec.decodeReport(tamper(37, 0x3F))) // latitude out of range
        assertNull(BinaryCodec.decodeReport(vectorFrame.copyOfRange(0, 100))) // truncated
    }

    @Test
    fun `invalid UTF-8 in a comment is refused`() {
        val withByte = vectorFrame.copyOfRange(0, 53).toMutableList()
        withByte.add(0xFF.toByte())
        withByte.addAll(vectorFrame.copyOfRange(53, vectorFrame.size).toList())
        val frame = withByte.toByteArray().also { it[52] = 1 }
        assertNull(BinaryCodec.decodeReport(frame))
    }

    @Test
    fun `flags carry the ephemeral hint without touching the signed body`() {
        val normal = BinaryCodec.encodeReport(report(), ephemeral = false)!!
        val ephemeral = BinaryCodec.encodeReport(report(), ephemeral = true)!!

        assertEquals(0, normal[0].toInt() and 0x04)
        assertNotEquals(0, ephemeral[0].toInt() and 0x04)
        assertArrayEquals(normal.copyOfRange(1, normal.size), ephemeral.copyOfRange(1, ephemeral.size))
    }

    @Test
    fun `only the current shared layout is accepted`() {
        assertTrue(BinaryCodec.isCanonical(report()))
        assertTrue(BinaryCodec.isCanonical(vote(report().id)))

        // The deprecated layout: alt tag, t first, seven decimals, nine-tag votes.
        assertFalse(BinaryCodec.isCanonical(report(extraTag = listOf("alt", "Roadstr: police report"))))
        assertFalse(BinaryCodec.isCanonical(report(moveTypeFirst = true)))
        assertFalse(BinaryCodec.isCanonical(report(lat = "48.8566140", lon = "2.3522220")))
        assertFalse(
            BinaryCodec.isCanonical(
                vote(report().id).copy(
                    tags = vote(report().id).tags + listOf(listOf("g", "u09t")),
                ),
            ),
        )
        // Not a road event at all.
        assertFalse(BinaryCodec.isCanonical(report().copy(kind = 1)))
    }

    @Test
    fun `decode dispatches on the class bit`() {
        assertEquals(1315, BinaryCodec.decode(BinaryCodec.encodeReport(report())!!)!!.kind)
        assertEquals(1316, BinaryCodec.decode(BinaryCodec.encodeConfirmation(vote(report().id))!!)!!.kind)
        assertNull(BinaryCodec.decode(ByteArray(0)))
    }

    @Test
    fun `getConfirmationEventId extracts the referenced id`() {
        val vote = vote(report().id)
        val encoded = BinaryCodec.encodeConfirmation(vote)!!
        assertEquals(vote.tags[0][1], BinaryCodec.getConfirmationEventId(encoded))
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun report(
        type: EventType = EventType.POLICE,
        typeName: String? = null,
        latE6: Int = 48_856_614,
        lonE6: Int = 2_352_222,
        lat: String = CanonicalCoordinates.format(latE6),
        lon: String = CanonicalCoordinates.format(lonE6),
        createdAt: Long = 1700000000L,
        expiration: String = "1701209600",
        content: String = "",
        speedLimitKmh: Int? = null,
        extraTag: List<String>? = null,
        moveTypeFirst: Boolean = false,
        sig: String = "aa".repeat(64)
    ): NostrEvent {
        val tags = BinaryCodec.reportTags(
            latE6,
            lonE6,
            type,
            0L,
            speedLimitKmh
        ).toMutableList()
        // Replace the generated coordinate and expiration text when a test needs a
        // non-canonical spelling.
        tags[0] = listOf("lat", lat)
        tags[1] = listOf("lon", lon)
        val expirationIndex = tags.indexOfFirst { it[0] == "expiration" }
        tags[expirationIndex] = listOf("expiration", expiration)
        if (typeName != null) {
            val index = tags.indexOfFirst { it[0] == "t" }
            tags[index] = listOf("t", typeName)
        }
        if (extraTag != null) tags.add(extraTag)
        if (moveTypeFirst) {
            val index = tags.indexOfFirst { it[0] == "t" }
            tags.add(0, tags.removeAt(index))
        }
        return NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 1315,
            tags = tags,
            content = content,
            sig = sig
        ).let { it.copy(id = it.computeId()) }
    }

    private fun vote(
        reportId: String,
        stillThere: Boolean = true,
        createdAt: Long = 1700003600L
    ): NostrEvent {
        val tags = BinaryCodec.confirmationTags(
            reportId,
            if (stillThere) ConfirmationStatus.STILL_THERE else ConfirmationStatus.NO_LONGER_THERE
        )
        return NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 1316,
            tags = tags,
            content = "",
            sig = "bb".repeat(64)
        ).let { it.copy(id = it.computeId()) }
    }
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
