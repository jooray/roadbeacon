package app.roadbeacon.mesh

import app.roadbeacon.nostr.BinaryCodec
import app.roadbeacon.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The bridge against a fake radio, with the signed events of the shared conformance vectors. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val vectors = json.parseToJsonElement(
        javaClass.classLoader!!.getResource("road-events-v1.json")!!.readText()
    ).jsonObject["valid"]!!.jsonArray.map { it.jsonObject }

    private fun vector(name: String): NostrEvent = vectors.first {
        it["name"]!!.jsonPrimitive.content == name
    }.let { json.decodeFromJsonElement(NostrEvent.serializer(), it["event"]!! as JsonObject) }

    // Police report in Bratislava at t0, and two votes on it.
    private val report = vector("report_minimal")
    private val yes = vector("vote_still_there")
    private val no = vector("vote_no_longer_there")
    private val otherReport = vector("report_maxspeed_comment")
    private val here = 48.15 to 17.11

    private class FakeTransport : MeshTransport {
        override val datagrams = MutableSharedFlow<MeshDatagram>()
        override val connected = MutableStateFlow(true)
        val sent = mutableListOf<ByteArray>()
        var accept = true
        override fun start() {}
        override fun stop() {}
        override suspend fun send(frame: ByteArray): Boolean {
            if (!accept || !connected.value) return false
            sent += frame
            return true
        }

        var region: String? = "sk-ba"
        val scoped = mutableListOf<Pair<ByteArray, String>>()
        override val radio = RadioParams(11, 250_000, 5)
        override suspend fun summaryRegion(lat: Double, lon: Double): String? = region
        override suspend fun sendScoped(frame: ByteArray, region: String): Boolean {
            if (!accept || !connected.value) return false
            scoped += frame to region
            return true
        }
    }

    private class FakeConfig : MeshBridgeConfig {
        override var meshEnabled = true
        override var meshSendOwn = true
        override var meshReceive = true
        override var meshBridgeToNostr = true
        override var meshBridgeToRadio = true
        override var meshCursor: String? = null
    }

    private inner class Harness(scope: TestScope) {
        val transport = FakeTransport()
        val config = FakeConfig()
        val shown = mutableListOf<NostrEvent>()
        val published = mutableListOf<NostrEvent>()
        val cached = mutableSetOf<String>()
        var fix: Pair<Double, Double>? = here
        var nowMs = (report.createdAt + 700) * 1000
        val candidates = mutableListOf<SummaryPlanner.Candidate>()
        val unverified = mutableListOf<SummaryEntry>()
        var claimWins = true
        val claimed = mutableListOf<Pair<String, Int>>()
        val bridge = MeshBridge(
            settings = config,
            transport = transport,
            scope = scope,
            onInbound = { shown += it; if (it.kind == 1315) cached += it.id },
            publishToRelays = { published += it },
            position = { fix },
            reportExists = { it in cached },
            summaryCandidates = { candidates.toList() },
            onUnverified = { entry, _ -> unverified += entry },
            claims = BridgeClaims { area, hops -> claimed += area to hops; claimWins },
            clock = { nowMs },
            random = Random(1)
        )

        fun hear(event: NostrEvent, receivedAt: String? = null, hops: Int? = null) =
            bridge.onDatagram(MeshDatagram(frame(event), 0.0, receivedAt, hops))

        fun hearRaw(payload: ByteArray, hops: Int? = 0) = bridge.onDatagram(MeshDatagram(payload, 0.0, null, hops))

        /** A signed relay report [minutesOld] old, [metersNorth] of the driver. */
        fun relayReport(id: String, minutesOld: Long, metersNorth: Int = 500, category: Int = 3) =
            SummaryPlanner.Candidate(
                id, MeshBridge.toE6(here.first) + metersNorth * 9, MeshBridge.toE6(here.second), category,
                createdAt = nowMs / 1000 - minutesOld * 60, shownUntil = nowMs / 1000 + 3 * 3600,
                stillThere = 1, gone = 0
            ).also { candidates += it }
    }

    private fun frame(event: NostrEvent): ByteArray = when (event.kind) {
        1315 -> BinaryCodec.encodeReport(event)!!
        else -> BinaryCodec.encodeConfirmation(event)!!
    }

    @Test
    fun `a radio report is verified, shown and bridged unchanged`() = runTest {
        val h = Harness(this)
        h.hear(report)
        assertEquals(listOf(report), h.shown)
        assertEquals(listOf(report), h.published)
    }

    @Test
    fun `a tampered frame is dropped before anything sees it`() = runTest {
        val h = Harness(this)
        val tampered = frame(report).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        h.bridge.onDatagram(MeshDatagram(tampered, 0.0, null, null))
        assertTrue(h.shown.isEmpty())
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `a frame heard twice is handled once`() = runTest {
        val h = Harness(this)
        h.hear(report)
        h.hear(report)
        assertEquals(1, h.shown.size)
        assertEquals(1, h.published.size)
    }

    @Test
    fun `switches off mean nothing is shown or bridged`() = runTest {
        val h = Harness(this)
        h.config.meshBridgeToNostr = false
        h.hear(report)
        assertEquals(1, h.shown.size)
        assertTrue(h.published.isEmpty())

        val off = Harness(this)
        off.config.meshReceive = false
        off.hear(report)
        assertTrue(off.shown.isEmpty())
    }

    @Test
    fun `a vote before its report is bridged at once and shown when the report lands`() = runTest {
        val h = Harness(this)
        h.hear(yes)
        assertTrue(h.shown.isEmpty())
        assertEquals(listOf(yes), h.published)

        // The report may come from a relay: the service tells the bridge once it is cached.
        h.cached += report.id
        h.bridge.onReportKnown(report.id)
        assertEquals(listOf(yes), h.shown)
        h.bridge.onReportKnown(report.id)
        assertEquals(1, h.shown.size)
    }

    @Test
    fun `parked votes expire after fifteen minutes`() = runTest {
        val h = Harness(this)
        h.hear(yes)
        h.nowMs += 16 * 60 * 1000
        h.cached += report.id
        h.bridge.onReportKnown(report.id)
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `stale and future reports are dropped`() = runTest {
        val late = Harness(this)
        late.nowMs = (report.createdAt + 5 * 3600) * 1000 // police lives 4 hours
        late.hear(report)
        assertTrue(late.shown.isEmpty())

        val early = Harness(this)
        early.nowMs = (report.createdAt - 600) * 1000 // ten minutes before it was made
        early.hear(report)
        assertTrue(early.shown.isEmpty())
    }

    @Test
    fun `the replay cursor only moves forward`() = runTest {
        val h = Harness(this)
        h.hear(report, "2025-10-27T08:10:00.123456Z")
        h.hear(yes, "2025-10-27T08:09:00.000Z")
        assertEquals("2025-10-27T08:10:00.123456Z", h.config.meshCursor)
        h.hear(no, "2025-10-27T08:11:00Z")
        assertEquals("2025-10-27T08:11:00Z", h.config.meshCursor)
    }

    @Test
    fun `own events go out now, or when the radio comes back`() = runTest {
        val h = Harness(this)
        h.bridge.start()
        h.bridge.onOwnEvent(report)
        runCurrent()
        assertEquals(1, h.transport.sent.size)

        h.transport.connected.value = false
        h.bridge.onOwnEvent(yes)
        runCurrent()
        assertEquals(1, h.transport.sent.size)

        h.transport.connected.value = true
        runCurrent()
        assertEquals(2, h.transport.sent.size)
        assertEquals(yes, BinaryCodec.decode(h.transport.sent[1]))
        h.bridge.stop()
    }

    @Test
    fun `an own event heard on the radio first is not sent again`() = runTest {
        val h = Harness(this)
        h.hear(report)
        h.bridge.onOwnEvent(report)
        advanceUntilIdle()
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test
    fun `a relay event never goes to the radio itself, and without demand nothing does`() = runTest {
        val h = Harness(this)
        h.cached += report.id
        h.relayReport("r1", 5)
        h.bridge.onRelayEvent(report)
        h.bridge.onRelayEvent(yes)
        advanceUntilIdle()
        assertTrue(h.transport.sent.isEmpty())
        assertTrue(h.transport.scoped.isEmpty())
    }

    @Test
    fun `hearing a driver nearby brings one scoped summary after a hold, not the heard report`() = runTest {
        val h = Harness(this)
        h.relayReport("r1", 5)
        h.relayReport(report.id, 10) // the heard one: the mesh has it already
        h.hear(report, hops = 2)
        runCurrent()
        assertTrue("receiving never transmits", h.transport.sent.isEmpty() && h.transport.scoped.isEmpty())
        advanceTimeBy(1_999)
        assertTrue(h.transport.scoped.isEmpty())
        advanceUntilIdle()
        val (frame, region) = h.transport.scoped.single()
        assertEquals("sk-ba", region)
        assertEquals(13, frame.size)
        val entry = BridgeSummaryCodec.decodeSummary(frame)!!.single()
        assertEquals(3, entry.category)
        assertEquals(1, entry.stillThere)
        assertEquals(listOf("u2s1" to 2), h.claimed)
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test
    fun `summaries are off by default and need the switch`() = runTest {
        val h = Harness(this)
        h.config.meshBridgeToRadio = false
        h.relayReport("r1", 5)
        h.hear(report)
        advanceUntilIdle()
        assertTrue(h.transport.scoped.isEmpty())
        assertTrue(h.claimed.isEmpty())
    }

    @Test
    fun `no region and no fix mean no summary, never a plain flood`() = runTest {
        val noRegion = Harness(this)
        noRegion.transport.region = null
        noRegion.relayReport("r1", 5)
        noRegion.hear(report)

        val noFix = Harness(this)
        noFix.fix = null
        noFix.relayReport("r1", 5)
        noFix.hear(report)

        advanceUntilIdle()
        assertTrue(noRegion.transport.scoped.isEmpty() && noRegion.transport.sent.isEmpty())
        assertTrue(noFix.transport.scoped.isEmpty() && noFix.transport.sent.isEmpty())
    }

    @Test
    fun `demand from far away is not demand`() = runTest {
        val h = Harness(this)
        h.nowMs = (otherReport.createdAt + 60) * 1000
        h.relayReport("r1", 5)
        h.hear(otherReport) // Košice, ~300 km from the driver
        advanceUntilIdle()
        assertTrue(h.transport.scoped.isEmpty())
    }

    @Test
    fun `a lost claim sends nothing and counts the area as served`() = runTest {
        val h = Harness(this)
        h.claimWins = false
        h.relayReport("r1", 5)
        h.hear(report)
        advanceUntilIdle()
        assertTrue(h.transport.scoped.isEmpty())

        h.claimWins = true
        h.nowMs += 10 * 60_000
        h.relayReport("r2", 1)
        h.hear(yes)
        advanceUntilIdle()
        assertTrue(h.transport.scoped.isEmpty())
        assertEquals(1, h.claimed.size)
    }

    @Test
    fun `one summary per area per 30 minutes, and only with something new`() = runTest {
        val h = Harness(this)
        h.relayReport("r1", 5)
        h.hear(report)
        advanceUntilIdle()
        assertEquals(1, h.transport.scoped.size)

        h.nowMs += 20 * 60_000
        h.relayReport("r2", 1)
        h.bridge.onRelayEvent(otherReport.copy(id = "x", tags = report.tags))
        advanceUntilIdle()
        assertEquals(1, h.transport.scoped.size)

        h.nowMs += 11 * 60_000
        h.bridge.onRelayEvent(report)
        advanceUntilIdle()
        assertEquals(2, h.transport.scoped.size)
        assertEquals(2, BridgeSummaryCodec.decodeSummary(h.transport.scoped[1].first)!!.size)

        // Nothing new: no third summary even after the interval.
        h.nowMs += 31 * 60_000
        h.bridge.onRelayEvent(report)
        advanceUntilIdle()
        assertEquals(2, h.transport.scoped.size)
    }

    @Test
    fun `a request is demand with its own filter and a 10 minute interval`() = runTest {
        val h = Harness(this)
        h.relayReport("police", 5, category = 0)
        h.relayReport("accident", 3, category = 3)
        val ask = BridgeSummaryCodec.encodeRequest(
            SummaryRequest(MeshBridge.toE6(here.first), MeshBridge.toE6(here.second), 10, 3600, listOf(0))
        )
        h.hearRaw(ask)
        advanceUntilIdle()
        val first = BridgeSummaryCodec.decodeSummary(h.transport.scoped.single().first)!!
        assertEquals(listOf(0), first.map { it.category })

        h.nowMs += 11 * 60_000
        h.relayReport("police2", 1, metersNorth = 900, category = 0)
        h.hearRaw(ask)
        advanceUntilIdle()
        assertEquals(2, h.transport.scoped.size)
    }

    @Test
    fun `another bridge's summary is shown unverified and covers the area`() = runTest {
        val h = Harness(this)
        h.relayReport("r1", 5)
        h.hear(report)
        runCurrent()
        val theirs = BridgeSummaryCodec.encodeSummary(
            MeshBridge.toE6(here.first), MeshBridge.toE6(here.second),
            listOf(SummaryEntry(MeshBridge.toE6(here.first), MeshBridge.toE6(here.second), 0, 600, 3600))
        )!!
        h.hearRaw(theirs)
        advanceUntilIdle()
        assertTrue(h.transport.scoped.isEmpty())
        assertEquals(0, h.unverified.single().category)
        assertEquals("only the signed report goes to the relays", listOf(report), h.published)
    }

    @Test
    fun `summaries and requests are never published to the relays`() = runTest {
        val h = Harness(this)
        h.config.meshBridgeToRadio = false
        h.hearRaw(BridgeSummaryCodec.encodeRequest(SummaryRequest(48_150_000, 17_110_000, 0, null, emptyList())))
        h.hearRaw(BridgeSummaryCodec.encodeSummary(48_150_000, 17_110_000, listOf(SummaryEntry(48_150_000, 17_110_000, 0, 60, 600)))!!)
        advanceUntilIdle()
        assertTrue(h.published.isEmpty())
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `radio input is capped at 60 frames a minute`() = runTest {
        val h = Harness(this)
        repeat(60) { h.bridge.onDatagram(MeshDatagram(ByteArray(10), 0.0, null, null)) }
        h.hear(report)
        assertTrue(h.shown.isEmpty())
        h.nowMs += 61_000
        h.hear(report)
        assertEquals(1, h.shown.size)
    }
}
