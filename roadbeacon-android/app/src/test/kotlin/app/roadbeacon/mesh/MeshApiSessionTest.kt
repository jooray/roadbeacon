package app.roadbeacon.mesh

import app.roadbeacon.mesh.MeshApiSession.Action
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local API decisions, message by message, without a socket. */
class MeshApiSessionTest {

    private var now = 1_000_000L
    private val session = MeshApiSession("#roadstr") { now }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun hello(connected: Boolean?) = json(
        """{"type":"hello","protocol":1,"device":{"name":"JRJ-nomad"""" +
            (connected?.let { ""","connected":$it""" } ?: "") + "}}"
    )

    private fun device(connected: Boolean, vararg channels: String) = json(
        """{"type":"device","device":{"connected":$connected,"name":"JRJ-nomad"},"channels":[""" +
            channels.joinToString(",") { """{"index":19,"name":"$it"}""" } + "]}"
    )

    private val ok = json("""{"id":1,"ok":true,"result":{"subscriptionId":"s1"}}""")
    private fun error(code: String) = json("""{"id":1,"ok":false,"error":{"code":"$code","message":"x"}}""")

    private fun connectedAndSubscribed() {
        session.connectionStarted()
        assertEquals(Action.Subscribe, session.onHello(hello(true)))
        assertEquals(Action.None, session.onSubscribeAnswer(ok))
    }

    @Test
    fun `hello with the radio subscribes and the answer connects`() {
        connectedAndSubscribed()
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
        assertTrue(session.isSubscribed)
    }

    @Test
    fun `hello without the connected flag still subscribes`() {
        session.connectionStarted()
        assertEquals(Action.Subscribe, session.onHello(hello(null)))
    }

    @Test
    fun `no radio waits for a device message, then subscribes`() {
        session.connectionStarted()
        assertEquals(Action.None, session.onHello(hello(false)))
        assertEquals(MeshApiState.NoRadio, session.state)
        assertEquals(Action.None, session.onDevice(device(false)))
        assertEquals(MeshApiState.NoRadio, session.state)
        assertEquals(Action.Subscribe, session.onDevice(device(true, "#roadstr")))
        session.onSubscribeAnswer(ok)
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
    }

    @Test
    fun `radio coming back without the channel still subscribes once to find out`() {
        session.connectionStarted()
        session.onHello(hello(false))
        assertEquals(Action.Subscribe, session.onDevice(device(true, "Public")))
    }

    @Test
    fun `radio gone and back keeps the one subscription`() {
        connectedAndSubscribed()
        assertEquals(Action.None, session.onDevice(device(false)))
        assertEquals(MeshApiState.NoRadio, session.state)
        assertTrue(session.isSubscribed)
        assertEquals(Action.None, session.onDevice(device(true, "#roadstr")))
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
    }

    @Test
    fun `device message while subscribed changes nothing`() {
        connectedAndSubscribed()
        assertEquals(Action.None, session.onDevice(device(true, "#roadstr")))
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
    }

    @Test
    fun `unknown channel without a radio is no radio`() {
        session.connectionStarted()
        session.onHello(hello(null))
        session.onDevice(device(false))
        assertEquals(Action.None, session.onSubscribeAnswer(error("unknown_channel")))
        assertEquals(MeshApiState.NoRadio, session.state)
    }

    @Test
    fun `other errors are refused with their code`() {
        session.connectionStarted()
        session.onHello(hello(true))
        assertEquals(Action.None, session.onSubscribeAnswer(error("rate_limited")))
        assertEquals(MeshApiState.Refused("rate_limited"), session.state)
        session.connectionStarted()
        session.onHello(hello(true))
        session.onSubscribeAnswer(null)
        assertEquals(MeshApiState.Refused(null), session.state)
    }

    @Test
    fun `missing channel asks once, then subscribes`() {
        session.connectionStarted()
        session.onHello(hello(true))
        assertEquals(Action.Join, session.onSubscribeAnswer(error("unknown_channel")))
        assertEquals(MeshApiState.WaitingForChannel, session.state)
        // The server announces the new channel too; the join answer already subscribes.
        assertEquals(Action.None, session.onDevice(device(true, "#roadstr")))
        assertEquals(
            Action.Subscribe,
            session.onJoinAnswer(json("""{"id":2,"ok":true,"result":{"index":19,"name":"#roadstr","created":true}}"""))
        )
        session.onSubscribeAnswer(ok)
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
    }

    @Test
    fun `join is asked once per connection`() {
        session.connectionStarted()
        session.onHello(hello(true))
        assertEquals(Action.Join, session.onSubscribeAnswer(error("unknown_channel")))
        assertEquals(Action.None, session.onJoinAnswer(error("no_free_slot")))
        assertEquals(MeshApiState.ChannelMissing("no_free_slot"), session.state)
        assertEquals(Action.Subscribe, session.onRetry(null))
        assertEquals(Action.None, session.onSubscribeAnswer(error("unknown_channel")))
        assertEquals(MeshApiState.ChannelMissing("no_free_slot"), session.state)
        // A new connection may ask again.
        session.connectionStarted()
        session.onHello(hello(true))
        assertEquals(Action.Join, session.onSubscribeAnswer(error("unknown_channel")))
    }

    @Test
    fun `a denial pauses asking for ten minutes, across connections`() {
        session.connectionStarted()
        session.onHello(hello(true))
        session.onSubscribeAnswer(error("unknown_channel"))
        assertEquals(Action.None, session.onJoinAnswer(error("denied")))
        assertEquals(MeshApiState.ChannelDenied, session.state)

        now += MeshApiSession.DENIED_PAUSE_MS - 1
        session.connectionStarted()
        session.onHello(hello(true))
        assertEquals(Action.None, session.onSubscribeAnswer(error("unknown_channel")))
        assertEquals(MeshApiState.ChannelDenied, session.state)

        now += 1
        session.connectionStarted()
        session.onHello(hello(true))
        assertEquals(Action.Join, session.onSubscribeAnswer(error("unknown_channel")))
    }

    @Test
    fun `a new session asks again at once`() {
        session.connectionStarted()
        session.onHello(hello(true))
        session.onSubscribeAnswer(error("unknown_channel"))
        session.onJoinAnswer(error("denied"))
        val fresh = MeshApiSession("#roadstr") { now }
        fresh.connectionStarted()
        fresh.onHello(hello(true))
        assertEquals(Action.Join, fresh.onSubscribeAnswer(error("unknown_channel")))
    }

    @Test
    fun `channel added in MeshCore Open after a denial subscribes`() {
        session.connectionStarted()
        session.onHello(hello(true))
        session.onSubscribeAnswer(error("unknown_channel"))
        session.onJoinAnswer(error("denied"))
        assertEquals(Action.None, session.onDevice(device(true, "Public")))
        assertEquals(Action.Subscribe, session.onDevice(device(true, "Public", "#roadstr")))
    }

    @Test
    fun `retry only for servers that never send device`() {
        session.connectionStarted()
        assertFalse(session.retryDue())
        session.onHello(hello(true))
        assertFalse(session.retryDue()) // subscription in flight
        session.onSubscribeAnswer(error("rate_limited"))
        assertTrue(session.retryDue())
        session.onDevice(device(true, "Public"))
        assertFalse(session.retryDue())
    }

    @Test
    fun `retry reads device info before subscribing`() {
        session.connectionStarted()
        session.onHello(hello(false))
        assertTrue(session.retryDue())
        val stillNoRadio = json("""{"id":3,"ok":true,"result":{"connected":false,"channels":[]}}""")
        assertEquals(Action.None, session.onRetry(stillNoRadio))
        assertEquals(MeshApiState.NoRadio, session.state)
        val radio = json("""{"id":4,"ok":true,"result":{"connected":true,"name":"JRJ-nomad","channels":[]}}""")
        assertEquals(Action.Subscribe, session.onRetry(radio))
    }

    @Test
    fun `unknown region is shown until a send goes through`() {
        connectedAndSubscribed()
        assertFalse(session.onSendAnswer(error("unknown_region"), "sk-xx"))
        assertEquals(MeshApiState.Connected("JRJ-nomad", "sk-xx"), session.state)
        assertTrue(session.onSendAnswer(json("""{"id":5,"ok":true,"result":{"queued":true}}"""), null))
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
        // Without a region set, bad_request is not about the region.
        assertFalse(session.onSendAnswer(error("bad_request"), null))
        assertEquals(MeshApiState.Connected("JRJ-nomad"), session.state)
    }

    @Test
    fun `send params carry the region only when set`() {
        val bytes = byteArrayOf(1, 2, 3)
        val without = MeshApiSession.sendParams("#roadstr", 0xFF52, bytes, null)
        assertFalse(without.containsKey("region"))
        assertEquals("AQID", without["payload"]!!.jsonPrimitive.content)
        assertEquals(0xFF52, without["dataType"]!!.jsonPrimitive.content.toInt())
        assertFalse(MeshApiSession.sendParams("#roadstr", 0xFF52, bytes, "").containsKey("region"))
        assertEquals("sk-ba", MeshApiSession.sendParams("#roadstr", 0xFF52, bytes, "sk-ba")["region"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscription asks for both data types`() {
        val params = MeshApiSession.subscribeParams("#roadstr", listOf(0xFF52, 0x0150), "2026-11-01T10:00:00.000Z")
        assertEquals("""[65362,336]""", params["dataTypes"].toString())
        assertEquals("2026-11-01T10:00:00.000Z", params["since"]!!.jsonPrimitive.content)
        assertFalse(MeshApiSession.subscribeParams("#roadstr", listOf(0xFF52), null).containsKey("since"))
    }

    private fun event(dataType: Int, hops: String = "2", outgoing: Boolean = false, channel: String = "#roadstr") = json(
        """{"type":"channel.data","channel":"$channel","channelIndex":19,"dataType":$dataType,""" +
            """"payload":"AQID","snrDb":-7.25,"receivedAt":"2026-11-01T10:00:00.000Z","outgoing":$outgoing,"hops":$hops}"""
    )

    @Test
    fun `datagrams of either data type are read, with hops`() {
        val types = listOf(0xFF52, 0x0150)
        val dev = MeshApiSession.datagram(event(0xFF52), "#roadstr", types)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), dev.payload)
        assertEquals(-7.25, dev.snrDb, 0.0)
        assertEquals("2026-11-01T10:00:00.000Z", dev.receivedAt)
        assertEquals(2, dev.hops)
        assertEquals(0, MeshApiSession.datagram(event(0x0150, hops = "0"), "#roadstr", types)!!.hops)
        assertNull(MeshApiSession.datagram(event(0x0150, hops = "null"), "#roadstr", types)!!.hops)
    }

    @Test
    fun `other data types, channels and own sends are ignored`() {
        val types = listOf(0xFF52, 0x0150)
        assertNull(MeshApiSession.datagram(event(0xAE1C), "#roadstr", types))
        assertNull(MeshApiSession.datagram(event(0xFF52, channel = "Public"), "#roadstr", types))
        assertNull(MeshApiSession.datagram(event(0xFF52, outgoing = true), "#roadstr", types))
    }
}
