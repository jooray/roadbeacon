package app.roadbeacon.mesh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.Base64

sealed interface MeshApiState {
    data object Disconnected : MeshApiState
    data object Connecting : MeshApiState
    /** The user has to approve this app in MeshCore Open. */
    data object WaitingForApproval : MeshApiState
    /** MeshCore Open refused or revoked this app; retried slowly. */
    data object NotApproved : MeshApiState
    /**
     * Subscribed. [unknownRegion] is the flood region MeshCore Open refused on the last send, if
     * any: receiving works, sending does not until the setting is fixed.
     */
    data class Connected(val deviceName: String?, val unknownRegion: String? = null) : MeshApiState
    /** MeshCore Open runs and this app is paired, but it is not connected to the radio. */
    data object NoRadio : MeshApiState
    /** `channels.join` is waiting for the user to answer MeshCore Open's prompt. */
    data object WaitingForChannel : MeshApiState
    /** The user declined to add the channel; not asked again for a while. */
    data object ChannelDenied : MeshApiState
    /** The radio has no such channel and asking did not add it; [code] is the join error, if any. */
    data class ChannelMissing(val code: String?) : MeshApiState
    /** `events.subscribe` failed for another reason; [code] is the API's error code, null without an answer. */
    data class Refused(val code: String?) : MeshApiState
    data class Failed(val message: String) : MeshApiState
}

/**
 * What one local API connection does after `hello`, as decisions on JSON messages and nothing
 * else: no socket, no coroutines, no Android. [MeshCoreApiClient] carries the decisions out.
 *
 * The flow (MeshCore Open's `documentation/LOCAL_API.md`, plus `device` and `channels.join`):
 *  - `hello.device.connected` false, or a `device` message saying so: no radio, keep the socket
 *    and wait for the next `device` message;
 *  - radio connected: subscribe; a `device` message with the channel listed subscribes again
 *    only when this connection holds no subscription (one survives the radio going away; a
 *    second would deliver every event twice). `device` is sent on changes after `hello`, so the
 *    initial state comes from `hello.device`;
 *  - `unknown_channel` while the radio is connected: ask once per connection with
 *    `channels.join`, never within [DENIED_PAUSE_MS] of a refusal; then subscribe;
 *  - `unknown_channel` while it is not: no radio (without one MeshCore Open knows no channels);
 *  - any other error: refused, with the code.
 *
 * A server that never sends `device` gets a slow retry instead ([retryDue]). The denial pause
 * outlives connections, not the instance: a new client (the user toggling the switch) asks again.
 */
internal class MeshApiSession(
    private val channelName: String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Action { None, Subscribe, Join }

    var state: MeshApiState = MeshApiState.Connecting
        private set

    private var authenticated = false
    private var deviceName: String? = null
    private var radioConnected = true
    private var sendsDeviceMessages = false
    private var subscribed = false
    private var subscriptionId: String? = null
    private var busy = false
    private var joinAsked = false
    private var unknownRegion: String? = null
    private var deniedUntil = 0L

    /** A new socket: everything but the denial pause starts over. */
    @Synchronized
    fun connectionStarted() {
        state = MeshApiState.Connecting
        authenticated = false
        deviceName = null
        radioConnected = true
        sendsDeviceMessages = false
        subscribed = false
        subscriptionId = null
        busy = false
        joinAsked = false
        unknownRegion = null
    }

    val isSubscribed: Boolean
        @Synchronized get() = subscribed

    @Synchronized
    fun onHello(message: JsonObject): Action {
        authenticated = true
        val device = message["device"] as? JsonObject
        deviceName = deviceName(device)
        // Older servers may leave the flag out; then only the subscription can tell.
        radioConnected = connectedFlag(device) ?: true
        if (!radioConnected) {
            state = MeshApiState.NoRadio
            return Action.None
        }
        return startSubscribe()
    }

    @Synchronized
    fun onDevice(message: JsonObject): Action {
        if (!authenticated) return Action.None
        sendsDeviceMessages = true
        val device = message["device"] as? JsonObject
        deviceName(device)?.let { deviceName = it }
        val connected = connectedFlag(device) ?: true
        val wasConnected = radioConnected
        radioConnected = connected
        if (!connected) {
            // A subscription stays in place across the radio going away and coming back.
            state = MeshApiState.NoRadio
            return Action.None
        }
        if (busy) return Action.None
        if (subscribed) {
            state = MeshApiState.Connected(deviceName, unknownRegion)
            return Action.None
        }
        return when {
            channelListed(message["channels"]) -> startSubscribe()
            // The radio is back: the subscription says whether the channel is there.
            !wasConnected || state == MeshApiState.NoRadio -> startSubscribe()
            else -> Action.None
        }
    }

    @Synchronized
    fun onSubscribeAnswer(response: JsonObject?): Action {
        busy = false
        if (isOk(response)) {
            subscribed = true
            subscriptionId = (response?.get("result") as? JsonObject)?.string("subscriptionId")
            state = MeshApiState.Connected(deviceName, unknownRegion)
            return Action.None
        }
        val code = errorCode(response)
        return when {
            code == "not_connected" -> {
                radioConnected = false
                state = MeshApiState.NoRadio
                Action.None
            }
            code != "unknown_channel" -> {
                state = MeshApiState.Refused(code)
                Action.None
            }
            !radioConnected -> {
                state = MeshApiState.NoRadio
                Action.None
            }
            clock() < deniedUntil -> {
                state = MeshApiState.ChannelDenied
                Action.None
            }
            joinAsked -> {
                if (state != MeshApiState.ChannelDenied && state !is MeshApiState.ChannelMissing) {
                    state = MeshApiState.ChannelMissing(null)
                }
                Action.None
            }
            else -> {
                joinAsked = true
                busy = true
                state = MeshApiState.WaitingForChannel
                Action.Join
            }
        }
    }

    @Synchronized
    fun onJoinAnswer(response: JsonObject?): Action {
        busy = false
        if (isOk(response)) return startSubscribe()
        when (val code = errorCode(response)) {
            "denied" -> {
                deniedUntil = clock() + DENIED_PAUSE_MS
                state = MeshApiState.ChannelDenied
            }
            "not_connected" -> {
                radioConnected = false
                state = MeshApiState.NoRadio
            }
            else -> state = MeshApiState.ChannelMissing(code)
        }
        return Action.None
    }

    /**
     * True when this connection is stuck without a subscription and the server does not send
     * `device` messages that would end it: the client then refreshes `device.info` and subscribes.
     */
    @Synchronized
    fun retryDue(): Boolean = authenticated && !sendsDeviceMessages && !subscribed && !busy

    /** The fallback retry: [deviceInfo] is the `device.info` answer, if there was one. */
    @Synchronized
    fun onRetry(deviceInfo: JsonObject?): Action {
        if (!retryDue()) return Action.None
        val result = deviceInfo?.takeIf(::isOk)?.get("result") as? JsonObject
        if (result != null) {
            deviceName(result)?.let { deviceName = it }
            connectedFlag(result)?.let { radioConnected = it }
        }
        if (!radioConnected) {
            state = MeshApiState.NoRadio
            return Action.None
        }
        return startSubscribe()
    }

    /** Records a `channel.sendData` answer; true when the datagram was queued. */
    @Synchronized
    fun onSendAnswer(response: JsonObject?, region: String?): Boolean {
        val ok = isOk(response)
        val code = errorCode(response)
        val refusedRegion = region != null && (code == "unknown_region" || code == "bad_request")
        val next = when {
            ok -> null
            refusedRegion -> region
            else -> unknownRegion
        }
        if (next != unknownRegion) {
            unknownRegion = next
            if (subscribed) state = MeshApiState.Connected(deviceName, unknownRegion)
        }
        return ok
    }

    private fun startSubscribe(): Action {
        busy = true
        return Action.Subscribe
    }

    private fun channelListed(channels: Any?): Boolean =
        (channels as? JsonArray)?.any { (it as? JsonObject)?.string("name") == channelName } == true

    companion object {
        /** After the user refused to add the channel, do not ask again for this long. */
        const val DENIED_PAUSE_MS = 10 * 60 * 1000L

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun connectedFlag(device: JsonObject?): Boolean? =
            (device?.get("connected") as? JsonPrimitive)?.booleanOrNull

        private fun deviceName(device: JsonObject?): String? =
            device?.string("name")?.filter { it.code >= 0x20 }?.take(64)?.ifEmpty { null }

        fun isOk(response: JsonObject?): Boolean =
            (response?.get("ok") as? JsonPrimitive)?.booleanOrNull == true

        fun errorCode(response: JsonObject?): String? =
            (response?.get("error") as? JsonObject)?.string("code")

        fun subscribeParams(channel: String, dataTypes: List<Int>, since: String?): JsonObject =
            buildJsonObject {
                put("events", buildJsonArray { add(JsonPrimitive("channel.data")) })
                put("channel", channel)
                put("dataTypes", buildJsonArray { dataTypes.forEach { add(JsonPrimitive(it)) } })
                // Ask for what was heard while this app was not connected.
                since?.let { put("since", it) }
            }

        /** Without a [region] MeshCore Open uses the channel's or the radio's own flood scope. */
        fun sendParams(channel: String, dataType: Int, payload: ByteArray, region: String?): JsonObject =
            buildJsonObject {
                put("channel", channel)
                put("dataType", dataType)
                put("payload", Base64.getEncoder().encodeToString(payload))
                region?.takeIf { it.isNotEmpty() }?.let { put("region", it) }
            }

        /** The datagram in a `channel.data` event, or null when it is not one this app reads. */
        fun datagram(event: JsonObject, channel: String, dataTypes: Collection<Int>): MeshDatagram? {
            if (event.string("type") != "channel.data") return null
            if (event.string("channel") != channel) return null
            val dataType = (event["dataType"] as? JsonPrimitive)?.intOrNull ?: return null
            if (dataType !in dataTypes) return null
            if ((event["outgoing"] as? JsonPrimitive)?.booleanOrNull == true) return null
            val payload = event.string("payload") ?: return null
            val bytes = try {
                Base64.getDecoder().decode(payload)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return MeshDatagram(
                payload = bytes,
                snrDb = (event["snrDb"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                receivedAt = event.string("receivedAt"),
                hops = (event["hops"] as? JsonPrimitive)?.intOrNull
            )
        }
    }
}
