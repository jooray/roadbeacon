package app.roadbeacon.mesh

import android.util.Base64
import android.util.Log
import app.roadbeacon.BuildConfig
import app.roadbeacon.storage.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * One channel datagram; [receivedAt] is MeshCore Open's receive time, the replay cursor. [hops] is
 * the flood hop count (0 = heard from the sender's radio), null for a direct path or when the
 * server does not say.
 */
data class MeshDatagram(val payload: ByteArray, val snrDb: Double, val receivedAt: String?, val hops: Int?)

/**
 * Client for MeshCore Open's local API (`documentation/LOCAL_API.md` there).
 *
 * Pairing: this app owns a P-256 key. The first time it connects, MeshCore Open
 * shows "Allow Roadbeacon …?" with the key's [fingerprint]; the approved key is
 * stored on both sides, so later connections only prove possession of the key.
 * No shared secret. The client builds the string it signs from the nonce itself
 * and signs once per connection, so it is never a signing oracle for whatever
 * listens on the port.
 *
 * After `hello` the connection follows the radio ([MeshApiSession] decides): it subscribes while
 * MeshCore Open is connected to the radio, waits on a `device` message while it is not, and asks
 * once with `channels.join` when the radio has no [channelName] channel.
 */
class MeshCoreApiClient(
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val channelName: String = "#roadstr",
    private val clientName: String = "Roadbeacon",
    private val clientVersion: String = BuildConfig.VERSION_NAME
) : MeshTransport {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _state = MutableStateFlow<MeshApiState>(MeshApiState.Disconnected)
    val state: StateFlow<MeshApiState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // emit() suspends when full, so a replay burst slows the reader instead of being dropped.
    private val _datagrams = MutableSharedFlow<MeshDatagram>(extraBufferCapacity = 256)
    override val datagrams: SharedFlow<MeshDatagram> = _datagrams.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = true
    @Volatile private var generation = 0
    private var loop: Job? = null
    private var attempts = 0
    private val requestIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, Channel<JsonObject>>()
    private val session = MeshApiSession(channelName)
    @Volatile private var retry: Job? = null

    private var privateKey: java.security.PrivateKey? = null
    private var publicKeyRaw: ByteArray? = null

    override fun start() {
        if (!stopped) return
        stopped = false
        loop = scope.launch { connectLoop() }
    }

    override fun stop() {
        stopped = true
        generation++
        loop?.cancel()
        loop = null
        retry?.cancel()
        retry = null
        socket?.close(1000, "app stopping")
        socket = null
        pending.values.forEach { it.close() }
        pending.clear()
        setState(MeshApiState.Disconnected)
    }

    /** What MeshCore Open shows in its "Allow …?" dialog for this app, e.g. `AB12 CD34 EF56 7890`. */
    suspend fun fingerprint(): String {
        ensureIdentity()
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw!!)
        val hex = digest.copyOf(8).joinToString("") { "%02X".format(Locale.ROOT, it) }
        return hex.chunked(4).joinToString(" ")
    }

    private suspend fun connectLoop() {
        while (!stopped) {
            var refused = false
            try {
                setState(MeshApiState.Connecting)
                refused = connectOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mesh API connection failed: ${e.message}")
                if (!stopped) setState(MeshApiState.Failed(e.message ?: "connection failed"))
            }
            if (stopped) break
            val wait = if (refused) {
                setState(MeshApiState.NotApproved)
                REFUSED_RETRY_MS
            } else {
                attempts++
                (5_000L * 2.0.pow(attempts.coerceAtMost(4))).toLong().coerceAtMost(60_000L)
            }
            delay(wait)
        }
    }

    /** Runs one connection to its end. True when MeshCore Open refused this app (close 4003). */
    private suspend fun connectOnce(): Boolean {
        ensureIdentity()
        val connection = ++generation
        session.connectionStarted()
        val request = Request.Builder().url(settings.meshUrl).build()
        val inbox = Channel<String>(INBOX_CAPACITY)
        var closeCode = 0
        var signed = false
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(helloMessage())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (inbox.trySend(text).isFailure) {
                    // The API sends far less than this; a reader this far behind is broken.
                    webSocket.close(1008, "client overloaded")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "mesh API socket failure: ${t.message}")
                inbox.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                inbox.close()
            }
        })
        socket = ws
        try {
            // One reader, in arrival order: replayed frames keep their order and the cursor only
            // moves forward.
            for (text in inbox) {
                if (connection != generation) break
                val message = try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    Log.w(TAG, "mesh API sent invalid JSON")
                    continue
                }
                if (message["type"]?.jsonPrimitive?.contentOrNull == "pairing.challenge") {
                    if (!signed) {
                        signed = true
                        answerChallenge(ws, message)
                    }
                    continue
                }
                handleMessage(message, connection)
            }
        } finally {
            retry?.cancel()
            retry = null
            ws.cancel()
            if (socket === ws) socket = null
            pending.values.forEach { it.close() }
            pending.clear()
        }
        if (connection == generation && !stopped) setState(MeshApiState.Disconnected)
        return closeCode == CLOSE_REFUSED
    }

    private fun answerChallenge(ws: WebSocket, message: JsonObject) {
        val nonce = message["nonce"]?.jsonPrimitive?.contentOrNull
        if (nonce == null || !NONCE_SHAPE.matches(nonce)) {
            Log.w(TAG, "mesh API sent a malformed pairing challenge")
            ws.close(1002, "bad challenge")
            return
        }
        setState(MeshApiState.WaitingForApproval)
        ws.send(
            buildJsonObject {
                put("type", "pair")
                put("signature", sign("$PAIRING_PREFIX$nonce"))
            }.toString()
        )
    }

    private suspend fun handleMessage(message: JsonObject, connection: Int) {
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> {
                attempts = 0
                // Every authenticated connection subscribes again: a subscription does not outlive
                // its socket.
                perform(session.onHello(message), connection)
            }

            "device" -> perform(session.onDevice(message), connection)

            "event" -> handleEvent(message)

            "error" -> {
                val error = message["error"]?.jsonObject
                Log.w(TAG, "mesh API error: ${error?.get("code")?.jsonPrimitive?.contentOrNull} " +
                    "${error?.get("message")?.jsonPrimitive?.contentOrNull}")
            }
        }

        val id = message["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (id != null) {
            pending.remove(id)?.trySend(message)
        }
    }

    /** Carries out what [session] decided, then publishes its state. */
    private fun perform(action: MeshApiSession.Action, connection: Int) {
        if (connection != generation || stopped) return
        when (action) {
            MeshApiSession.Action.Subscribe -> scope.launch { subscribe(connection) }
            MeshApiSession.Action.Join -> scope.launch { join(connection) }
            MeshApiSession.Action.None -> Unit
        }
        setState(session.state)
        if (session.retryDue()) scheduleRetry(connection)
    }

    /**
     * Fallback for servers that never send `device`: a connection without a subscription refreshes
     * `device.info` and subscribes again after [RETRY_MS] instead of staying stuck.
     */
    private fun scheduleRetry(connection: Int) {
        retry?.cancel()
        retry = scope.launch {
            delay(RETRY_MS)
            if (connection != generation || !session.retryDue()) return@launch
            val info = request("device.info", buildJsonObject { })
            if (connection != generation) return@launch
            perform(session.onRetry(info), connection)
        }
    }

    private suspend fun subscribe(connection: Int) {
        val response = request(
            "events.subscribe",
            MeshApiSession.subscribeParams(channelName, RECEIVE_DATA_TYPES, settings.meshCursor)
        )
        if (connection != generation) return
        if (!MeshApiSession.isOk(response)) Log.w(TAG, "mesh API subscribe failed: $response")
        perform(session.onSubscribeAnswer(response), connection)
    }

    /** Asks MeshCore Open to add the channel; the user answers a prompt there, so this waits long. */
    private suspend fun join(connection: Int) {
        val response = request(
            "channels.join",
            buildJsonObject { put("name", channelName) },
            timeoutMs = JOIN_TIMEOUT_MS
        )
        if (connection != generation) return
        if (!MeshApiSession.isOk(response)) Log.w(TAG, "mesh API channels.join failed: $response")
        perform(session.onJoinAnswer(response), connection)
    }

    private suspend fun handleEvent(message: JsonObject) {
        val event = message["event"]?.jsonObject ?: return
        val datagram = MeshApiSession.datagram(event, channelName, RECEIVE_DATA_TYPES) ?: return
        _datagrams.emit(datagram)
    }

    private fun setState(value: MeshApiState) {
        _state.value = value
        _connected.value = value is MeshApiState.Connected
    }

    override suspend fun send(frame: ByteArray): Boolean = sendChannelData(frame)

    /** Sends one application datagram; false when the API is not connected or refused it. */
    suspend fun sendChannelData(payload: ByteArray): Boolean {
        if (_state.value !is MeshApiState.Connected) return false
        val connection = generation
        // Empty: MeshCore Open applies the channel's or the radio's own flood scope.
        val region = settings.meshRegion.trim().ifEmpty { null }
        val response = request(
            "channel.sendData",
            MeshApiSession.sendParams(channelName, SEND_DATA_TYPE, payload, region)
        ) ?: return false
        val ok = session.onSendAnswer(response, region)
        if (!ok) Log.w(TAG, "mesh API send refused: ${MeshApiSession.errorCode(response)}")
        if (connection == generation && !stopped && _state.value is MeshApiState.Connected) {
            setState(session.state)
        }
        return ok
    }

    private suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMs: Long = REQUEST_TIMEOUT_MS
    ): JsonObject? {
        val ws = socket ?: return null
        val id = requestIds.incrementAndGet()
        val response = Channel<JsonObject>(Channel.CONFLATED)
        pending[id] = response
        ws.send(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()
        )
        return try {
            withTimeoutOrNull(timeoutMs) { response.receiveCatching().getOrNull() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureIdentity() {
        if (privateKey != null && publicKeyRaw != null) return
        val stored = settings.meshIdentity
        if (stored != null) {
            try {
                val obj = json.parseToJsonElement(stored).jsonObject
                val priv = Base64.decode(obj["private"]!!.jsonPrimitive.content, Base64.DEFAULT)
                val pub = Base64.decode(obj["public"]!!.jsonPrimitive.content, Base64.DEFAULT)
                val keyFactory = KeyFactory.getInstance("EC")
                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(priv))
                publicKeyRaw = pub
                return
            } catch (e: Exception) {
                Log.w(TAG, "stored mesh identity unreadable, generating a new one")
            }
        }
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }
        val pair = generator.generateKeyPair()
        val priv = pair.private.encoded
        val point = (pair.public as java.security.interfaces.ECPublicKey).w
        val pub = encodePoint(point)
        privateKey = pair.private
        publicKeyRaw = pub
        settings.meshIdentity = buildJsonObject {
            put("private", Base64.encodeToString(priv, Base64.NO_WRAP))
            put("public", Base64.encodeToString(pub, Base64.NO_WRAP))
        }.toString()
    }

    private fun helloMessage(): String = buildJsonObject {
        put("type", "hello")
        putJsonObject("client") {
            put("name", clientName)
            put("version", clientVersion)
            put("publicKey", Base64.encodeToString(publicKeyRaw, Base64.NO_WRAP))
        }
    }.toString()

    private fun sign(message: String): String {
        val key = privateKey ?: error("no identity")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun encodePoint(point: ECPoint): ByteArray {
        val out = ByteArray(64)
        copyFixed(point.affineX.toByteArray(), out, 0)
        copyFixed(point.affineY.toByteArray(), out, 32)
        return out
    }

    private fun copyFixed(source: ByteArray, target: ByteArray, offset: Int) {
        val start = if (source.size > 32) source.size - 32 else 0
        val length = source.size - start
        System.arraycopy(source, start, target, offset + 32 - length, length)
    }

    internal companion object {
        const val TAG = "MeshCoreApi"
        const val PAIRING_PREFIX = "meshcore-open-pair-v1:"
        val NONCE_SHAPE = Regex("^[A-Za-z0-9_-]{16,128}={0,2}$")
        const val CLOSE_REFUSED = 4003
        const val REFUSED_RETRY_MS = 120_000L
        const val INBOX_CAPACITY = 1024
        const val REQUEST_TIMEOUT_MS = 15_000L
        /** MeshCore Open's prompt for `channels.join` stays up to 2 min; wait past that. */
        const val JOIN_TIMEOUT_MS = 150_000L
        const val RETRY_MS = 60_000L

        /**
         * "Road Events", allocated in MeshCore's registry (`docs/number_allocations.md`,
         * meshcore-dev/MeshCore#3750). The development value 0xFF52 used before is no longer
         * sent or accepted.
         */
        const val DATA_TYPE_ROAD_EVENTS = 0x0150
        /** The `dataType` this app sends with. */
        const val SEND_DATA_TYPE = DATA_TYPE_ROAD_EVENTS
        /** The `dataType`s this app subscribes to and accepts. */
        val RECEIVE_DATA_TYPES = listOf(DATA_TYPE_ROAD_EVENTS)
    }
}
