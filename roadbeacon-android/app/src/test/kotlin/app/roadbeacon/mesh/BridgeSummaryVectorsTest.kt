package app.roadbeacon.mesh

import app.roadbeacon.nostr.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Every case of nips/vectors/road-bridge-v1.json (SPECIFICATION.md 4.2-4.3). */
class BridgeSummaryVectorsTest {
    private val root = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("road-bridge-v1.json")!!.readText()
    ).jsonObject

    private fun cases(name: String) = root[name]!!.jsonArray.map { it.jsonObject }
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long

    @Test
    fun `duration codes`() {
        for (c in cases("duration_encode")) {
            assertEquals(c["code"]!!.jsonPrimitive.intOrNull, BridgeSummaryCodec.durationEncode(c.long("seconds")))
        }
        for (c in cases("duration_decode")) {
            assertEquals(c["seconds"]!!.jsonPrimitive.longOrNull, BridgeSummaryCodec.durationDecode(c.int("code")))
        }
    }

    @Test
    fun `valid summaries encode and decode exactly`() {
        for (c in cases("summary_valid")) {
            val point = c["point"]!!.jsonObject
            val entries = c["entries"]!!.jsonArray.map { it.jsonObject }.map {
                SummaryEntry(it.int("lat_e6"), it.int("lon_e6"), it.int("category"), it.long("age_s"),
                    it.long("remaining_s"), it.int("still"), it.int("gone"))
            }
            val frame = BridgeSummaryCodec.encodeSummary(point.int("lat_e6"), point.int("lon_e6"), entries)
            assertArrayEquals(c["name"].toString(), hex(c["frame"]!!.jsonPrimitive.content), frame)
            assertEquals(c.int("content_bytes"), frame!!.size)
            val decoded = BridgeSummaryCodec.decodeSummary(frame)!!
            val expected = c["decoded"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expected.size, decoded.size)
            for ((e, d) in expected.zip(decoded)) {
                assertEquals(e.int("lat_e6"), d.latE6)
                assertEquals(e.int("lon_e6"), d.lonE6)
                assertEquals(e.int("category"), d.category)
                assertEquals(e.long("age_s"), d.ageSeconds)
                assertEquals(e.long("remaining_s"), d.remainingSeconds)
                assertEquals(e.int("still"), d.stillThere)
                assertEquals(e.int("gone"), d.gone)
                assertEquals(e["id"]!!.jsonPrimitive.content, d.localId)
            }
        }
    }

    @Test
    fun `invalid summaries are dropped whole`() {
        for (c in cases("summary_invalid")) {
            assertNull(c["name"].toString(), BridgeSummaryCodec.decodeSummary(hex(c["frame"]!!.jsonPrimitive.content)))
        }
    }

    @Test
    fun `bad entries are dropped one by one`() {
        for (c in cases("summary_entries_dropped")) {
            val decoded = BridgeSummaryCodec.decodeSummary(hex(c["frame"]!!.jsonPrimitive.content))
            assertEquals(c["name"].toString(), c.int("kept_entries"), decoded!!.size)
        }
    }

    @Test
    fun `requests`() {
        for (c in cases("request_valid")) {
            val frame = hex(c["frame"]!!.jsonPrimitive.content)
            val d = c["decoded"]!!.jsonObject
            val expected = SummaryRequest(
                d.int("lat_e6"), d.int("lon_e6"), d.int("radius_km"), d["since_s"]!!.jsonPrimitive.longOrNull,
                d["categories"]!!.jsonArray.map { it.jsonPrimitive.int }
            )
            assertEquals(expected, BridgeSummaryCodec.decodeRequest(frame))
            // Encoding the decoded request gives the frame back (radius 0 means 25 on the way in).
            val radius = if (frame[5].toInt() == 0) 0 else expected.radiusKm
            assertArrayEquals(frame, BridgeSummaryCodec.encodeRequest(expected.copy(radiusKm = radius)))
        }
        for (c in cases("request_invalid")) {
            assertNull(c["name"].toString(), BridgeSummaryCodec.decodeRequest(hex(c["frame"]!!.jsonPrimitive.content)))
        }
    }

    @Test
    fun `claim winner`() {
        for (c in cases("claims")) {
            val area = c["area"]!!.jsonPrimitive.content
            val events = c["claims"]!!.jsonArray.map { it.jsonObject }.mapIndexed { i, claim ->
                NostrEvent(
                    id = "claim$i", pubkey = claim["pubkey"]!!.jsonPrimitive.content, createdAt = 1_000,
                    kind = NostrBridgeClaims.KIND_CLAIM,
                    tags = listOf(listOf("g", area), listOf("hops", claim.int("hops").toString()))
                )
            }
            val winner = NostrBridgeClaims.winner(events, area, 1_000)
            assertEquals(c["name"].toString(), "claim${c.int("winner")}", winner?.id)
        }
    }

    @Test
    fun `claims outside the window or for another area do not count`() {
        val key = "1f79078f1df1ae5d751c2f13d66c7d15b2a3da11cf904015070ededbdf5d8668"
        fun claim(id: String, at: Long, area: String, hops: Int) = NostrEvent(
            id = id, pubkey = key, createdAt = at, kind = NostrBridgeClaims.KIND_CLAIM,
            tags = listOf(listOf("g", area), listOf("hops", hops.toString()))
        )
        val own = claim("own", 1_000, "u2s1", 3)
        val old = claim("old", 900, "u2s1", 0)
        val other = claim("other", 1_000, "u2s4", 0)
        assertEquals("own", NostrBridgeClaims.winner(listOf(own, old, other), "u2s1", 1_000)?.id)
        assertNotNull(NostrBridgeClaims.winner(listOf(own), "u2s1", 1_000))
    }

    @Test
    fun `airtime`() {
        for (c in cases("airtime")) {
            val bytes = BridgeSummaryCodec.packetBytes(c.int("content_bytes"), c["region"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(c.int("packet_bytes"), bytes)
            val ms = BridgeSummaryCodec.airtimeMs(c.int("sf"), c.int("bw_hz"), c.int("cr"), bytes)
            assertEquals(c["airtime_ms"]!!.jsonPrimitive.content.toDouble(), ms, 0.001)
        }
    }

    @Test
    fun `older decoders refuse the new frames`() {
        val summary = hex(cases("summary_valid").first()["frame"]!!.jsonPrimitive.content)
        val request = hex(cases("request_valid").first()["frame"]!!.jsonPrimitive.content)
        assertNull(app.roadbeacon.nostr.BinaryCodec.decode(summary))
        assertNull(app.roadbeacon.nostr.BinaryCodec.decode(request))
        assertEquals(BridgeSummaryCodec.SUMMARY, BridgeSummaryCodec.family(summary))
        assertEquals(BridgeSummaryCodec.REQUEST, BridgeSummaryCodec.family(request))
    }
}
