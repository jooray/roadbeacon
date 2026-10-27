package app.roadbeacon.mesh

import android.util.Base64
import android.util.Log
import app.roadbeacon.storage.Settings
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

data class MeshDatagram(val payload: ByteArray, val snrDb: Double)

sealed interface MeshApiState {
    data object Disconnected : MeshApiState
    data object Connecting : MeshApiState
    /** The user has to approve this app in MeshCore Open. */
    data object WaitingForApproval : MeshApiState
    data class Connected(val deviceName: String?) : MeshApiState
    data class Failed(val message: String) : MeshApiState
}

/**
 * Client for MeshCore Open's local API (`documentation/LOCAL_API.md` there).
 *
 * Pairing: this app owns a P-256 key. The first time it connects, MeshCore Open
 * shows "Allow Roadbeacon …?"; the approved key is stored on both sides, so
 * later connections only prove possession of the key. No shared secret.
 */
class MeshCoreApiClient(
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val channelName: String = "#roadstr",
    private val dataType: Int = 0xFF52,
    private val clientName: String = "Roadbeacon",
    private val clientVersion: String = "0.4.0"
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _state = MutableStateFlow<MeshApiState>(MeshApiState.Disconnected)
    val state: StateFlow<MeshApiState> = _state.asStateFlow()

    private val _datagrams = MutableSharedFlow<MeshDatagram>(extraBufferCapacity = 64)
    val datagrams: SharedFlow<MeshDatagram> = _datagrams.asSharedFlow()

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var stopped = true
    private var attempts = 0
    private val requestIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, Channel<JsonObject>>()
    private val authenticated = Channel<Boolean>(Channel.CONFLATED)

    private var privateKey: java.security.PrivateKey? = null
    private var publicKeyRaw: ByteArray? = null

    fun start() {
        if (!stopped) return
        stopped = false
        loop = scope.launch { connectLoop() }
    }

    fun stop() {
        stopped = true
        loop?.cancel()
        loop = null
        socket?.close(1000, "app stopping")
        socket = null
        pending.values.forEach { it.close() }
        pending.clear()
        _state.value = MeshApiState.Disconnected
    }

    private suspend fun connectLoop() {
        while (!stopped) {
            try {
                _state.value = MeshApiState.Connecting
                connectOnce()
                // connectOnce returns when the socket closes
            } catch (e: Exception) {
                Log.w(TAG, "mesh API connection failed: ${e.message}")
                _state.value = MeshApiState.Failed(e.message ?: "connection failed")
            }
            if (stopped) break
            attempts++
            val wait = (5_000L * 2.0.pow(attempts.coerceAtMost(4))).toLong().coerceAtMost(60_000L)
            delay(wait)
        }
    }

    private suspend fun connectOnce() {
        ensureIdentity()
        val request = Request.Builder().url(settings.meshUrl).build()
        val closed = Channel<Unit>(Channel.CONFLATED)
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                webSocket.send(helloMessage())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "mesh API socket failure: ${t.message}")
                closed.trySend(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.trySend(Unit)
            }
        })
        socket = ws
        closed.receive()
        ws.cancel()
        socket = null
        _state.value = MeshApiState.Disconnected
    }

    private suspend fun handleMessage(ws: WebSocket, text: String) {
        val message = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "mesh API sent invalid JSON")
            return
        }
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "pairing.challenge" -> {
                _state.value = MeshApiState.WaitingForApproval
                val signed = message["message"]?.jsonPrimitive?.contentOrNull
                if (signed != null) {
                    ws.send(
                        buildJsonObject {
                            put("type", "pair")
                            put("signature", sign(signed))
                        }.toString()
                    )
                }
            }

            "hello" -> {
                val device = message["device"]?.jsonObject
                val name = device?.get("name")?.jsonPrimitive?.contentOrNull
                _state.value = MeshApiState.Connected(name)
                authenticated.trySend(true)
                ws.send(subscribeMessage())
            }

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

    private fun handleEvent(message: JsonObject) {
        val event = message["event"]?.jsonObject ?: return
        if (event["type"]?.jsonPrimitive?.contentOrNull != "channel.data") return
        if (event["channel"]?.jsonPrimitive?.contentOrNull != channelName) return
        if (event["dataType"]?.jsonPrimitive?.intOrNull != dataType) return
        val payload = event["payload"]?.jsonPrimitive?.contentOrNull ?: return
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: Exception) {
            return
        }
        val snr = event["snrDb"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        _datagrams.tryEmit(MeshDatagram(bytes, snr))
    }

    /** Sends one application datagram; false when the API is not connected. */
    suspend fun sendChannelData(payload: ByteArray): Boolean {
        if (_state.value !is MeshApiState.Connected) return false
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        val response = request(
            "channel.sendData",
            buildJsonObject {
                put("channel", channelName)
                put("dataType", dataType)
                put("payload", encoded)
            }
        ) ?: return false
        return response["ok"]?.jsonPrimitive?.booleanOrNull == true
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject? {
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
            withTimeout(15_000) { response.receive() }
        } catch (e: Exception) {
            null
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

    private fun subscribeMessage(): String = buildJsonObject {
        put("id", requestIds.incrementAndGet())
        put("method", "events.subscribe")
        putJsonObject("params") {
            put("events", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("channel.data"))
            })
            put("channel", channelName)
            put("dataTypes", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive(dataType))
            })
            // Ask for what was heard while this app was not connected.
            if (settings.meshSince > 0) {
                put("since", java.time.Instant.ofEpochMilli(settings.meshSince).toString())
            }
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

    private companion object {
        const val TAG = "MeshCoreApi"
    }
}
