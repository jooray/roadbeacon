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
        val bridge = MeshBridge(
            settings = config,
            transport = transport,
            scope = scope,
            onInbound = { shown += it; if (it.kind == 1315) cached += it.id },
            publishToRelays = { published += it },
            position = { fix },
            reportExists = { it in cached },
            liveReportLocation = { id ->
                when (id) {
                    report.id -> 48.148598 to 17.107748
                    otherReport.id -> 48.716385 to 21.261074
                    else -> null
                }.takeIf { id in cached }
            },
            clock = { nowMs },
            random = Random(1)
        )

        fun hear(event: NostrEvent, receivedAt: String? = null) =
            bridge.onDatagram(MeshDatagram(frame(event), 0.0, receivedAt))
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
        h.bridge.onDatagram(MeshDatagram(tampered, 0.0, null))
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
    fun `relay events near the driver go to the radio after a pause`() = runTest {
        val h = Harness(this)
        h.cached += report.id
        h.bridge.onRelayEvent(report)
        runCurrent()
        assertTrue(h.transport.sent.isEmpty())
        advanceTimeBy(16_000)
        assertEquals(1, h.transport.sent.size)
        assertEquals(report, BinaryCodec.decode(h.transport.sent[0]))
    }

    @Test
    fun `a relay event heard on the radio during the pause is not sent`() = runTest {
        val h = Harness(this)
        h.cached += report.id
        h.bridge.onRelayEvent(report)
        h.hear(report) // another bridge was faster
        advanceUntilIdle()
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test
    fun `relay to radio needs a fresh fix, a live report and 40 km`() = runTest {
        val noFix = Harness(this)
        noFix.fix = null
        noFix.cached += report.id
        noFix.bridge.onRelayEvent(report)

        val notShown = Harness(this) // not in the cache, or no longer shown
        notShown.bridge.onRelayEvent(report)

        val far = Harness(this)
        far.cached += otherReport.id // Košice, ~300 km away
        far.nowMs = (otherReport.createdAt + 60) * 1000
        far.bridge.onRelayEvent(otherReport)

        advanceUntilIdle()
        assertTrue(noFix.transport.sent.isEmpty())
        assertTrue(notShown.transport.sent.isEmpty())
        assertTrue(far.transport.sent.isEmpty())
    }

    @Test
    fun `relay votes use the position of the report they are about`() = runTest {
        val h = Harness(this)
        h.cached += report.id
        h.bridge.onRelayEvent(yes)
        advanceUntilIdle()
        assertEquals(yes, BinaryCodec.decode(h.transport.sent.single()))
    }

    @Test
    fun `a radio event echoed by the relays is not sent back`() = runTest {
        val h = Harness(this)
        h.hear(report)
        h.bridge.onRelayEvent(report)
        advanceUntilIdle()
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test
    fun `relay to radio keeps a 20 second gap`() = runTest {
        val h = Harness(this)
        h.cached += report.id
        h.bridge.onRelayEvent(report)
        h.bridge.onRelayEvent(yes)
        advanceUntilIdle()
        assertEquals(1, h.transport.sent.size)

        h.nowMs += 21_000
        h.bridge.onRelayEvent(no)
        advanceUntilIdle()
        assertEquals(2, h.transport.sent.size)
    }

    @Test
    fun `radio input is capped at 60 frames a minute`() = runTest {
        val h = Harness(this)
        repeat(60) { h.bridge.onDatagram(MeshDatagram(ByteArray(10), 0.0, null)) }
        h.hear(report)
        assertTrue(h.shown.isEmpty())
        h.nowMs += 61_000
        h.hear(report)
        assertEquals(1, h.shown.size)
    }
}
