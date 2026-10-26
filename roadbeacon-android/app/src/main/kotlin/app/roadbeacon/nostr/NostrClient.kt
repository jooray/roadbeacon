package app.roadbeacon.nostr

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class NostrClient(private val scope: CoroutineScope) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val connectionState = ConcurrentHashMap<String, Boolean>()
    private val subscriptions = ConcurrentHashMap<String, NostrFilter>()
    private val pendingEvents = CopyOnWriteArrayList<Pair<NostrEvent, Long>>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val pendingOkCallbacks = ConcurrentHashMap<String, PendingPublish>()

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<NostrEvent> = _events

    private val _connectionCount = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    val connectionCount: SharedFlow<Int> = _connectionCount

    private var relayUrls: List<String> = emptyList()
    private val minConnectedRelays = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true  // Required: Nostr relays expect all fields including "content"
    }

    fun connect(urls: List<String>) {
        relayUrls = urls
        for (url in urls) {
            connectToRelay(url)
        }
    }

    fun disconnect() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        connections.values.forEach { it.close(1000, "Closing") }
        connections.clear()
        connectionState.clear()
        emitConnectionCount()
    }

    fun subscribe(filter: NostrFilter): String {
        val subId = UUID.randomUUID().toString().take(8)
        subscriptions[subId] = filter
        sendSubscription(subId, filter)
        return subId
    }

    fun closeSubscription(subId: String) {
        subscriptions.remove(subId)
        val msg = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subId))
        }
        broadcast(Json.encodeToString(JsonArray.serializer(), msg))
    }

    fun publish(event: NostrEvent, onResult: ((accepted: Int, total: Int) -> Unit)? = null) {
        val msg = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(json.encodeToJsonElement(NostrEvent.serializer(), event))
        }
        val jsonStr = Json.encodeToString(JsonArray.serializer(), msg)

        val connected = connections.entries.filter { connectionState[it.key] == true }
        if (connected.isEmpty()) {
            if (pendingEvents.size < MAX_PENDING_EVENTS) {
                pendingEvents.add(Pair(event, System.currentTimeMillis()))
                Log.d(TAG, "Event queued (${pendingEvents.size}/$MAX_PENDING_EVENTS)")
            } else {
                Log.w(TAG, "Pending queue full, dropping event: ${event.id.take(8)}")
            }
            onResult?.invoke(0, 0)
            return
        }

        if (onResult != null) {
            pendingOkCallbacks[event.id] = PendingPublish(
                total = connected.size,
                callback = onResult
            )
        }

        connected.forEach { (_, ws) ->
            ws.send(jsonStr)
        }
    }

    private data class PendingPublish(
        val total: Int,
        var accepted: Int = 0,
        var responded: Int = 0,
        val callback: (Int, Int) -> Unit
    )

    fun getConnectedCount(): Int = connectionState.values.count { it }

    private fun connectToRelay(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
                connectionState[url] = true
                emitConnectionCount()

                // Re-send all active subscriptions
                subscriptions.forEach { (subId, filter) ->
                    sendSubscriptionTo(webSocket, subId, filter)
                }

                // Flush pending events (expire stale ones)
                val now = System.currentTimeMillis()
                val pending = pendingEvents.filter { now - it.second < PENDING_TTL_MS }.map { it.first }
                pendingEvents.clear()
                for (event in pending) {
                    publish(event)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(url, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed for $url: ${t.message}")
                connectionState[url] = false
                connections.remove(url)
                emitConnectionCount()
                scheduleReconnect(url)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed $url: $reason")
                connectionState[url] = false
                connections.remove(url)
                emitConnectionCount()
                if (code != 1000) {
                    scheduleReconnect(url)
                }
            }
        })

        connections[url] = ws
    }

    private fun handleMessage(url: String, text: String) {
        try {
            val arr = json.decodeFromString(JsonArray.serializer(), text)
            if (arr.isEmpty()) return

            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    if (arr.size >= 3) {
                        val eventJson = arr[2]
                        val event = json.decodeFromJsonElement(NostrEvent.serializer(), eventJson)
                        Log.d(TAG, "EVENT from $url: id=${event.id.take(8)}, kind=${event.kind}")
                        if (EventSigner.verify(event)) {
                            scope.launch {
                                _events.emit(event)
                            }
                        } else {
                            Log.w(TAG, "Invalid signature for event ${event.id}")
                        }
                    }
                }
                "EOSE" -> {
                    Log.d(TAG, "EOSE from $url")
                }
                "OK" -> {
                    if (arr.size >= 3) {
                        val eventId = arr[1].jsonPrimitive.content
                        val success = arr[2].jsonPrimitive.boolean
                        val message = if (arr.size >= 4) arr[3].jsonPrimitive.contentOrNull else null
                        Log.d(TAG, "OK from $url: $eventId success=$success message=$message")
                        handleOk(eventId, success)
                    }
                }
                "NOTICE" -> {
                    if (arr.size >= 2) {
                        Log.w(TAG, "NOTICE from $url: ${arr[1].jsonPrimitive.content}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message from $url: ${e.message}")
        }
    }

    private fun handleOk(eventId: String, success: Boolean) {
        pendingOkCallbacks[eventId]?.let { pending ->
            if (success) pending.accepted++
            pending.responded++
            if (pending.responded >= pending.total) {
                pending.callback(pending.accepted, pending.total)
                pendingOkCallbacks.remove(eventId)
            }
        }
    }

    private fun sendSubscription(subId: String, filter: NostrFilter) {
        val msg = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter.toJson())
        }
        val jsonStr = Json.encodeToString(JsonArray.serializer(), msg)
        Log.d(TAG, "Sending REQ: $jsonStr")
        broadcast(jsonStr)
    }

    private fun sendSubscriptionTo(ws: WebSocket, subId: String, filter: NostrFilter) {
        val msg = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter.toJson())
        }
        ws.send(Json.encodeToString(JsonArray.serializer(), msg))
    }

    private fun broadcast(message: String) {
        connections.entries
            .filter { connectionState[it.key] == true }
            .forEach { (_, ws) -> ws.send(message) }
    }

    private fun scheduleReconnect(url: String) {
        reconnectJobs[url]?.cancel()
        reconnectJobs[url] = scope.launch {
            var delay = 1000L
            while (isActive && connectionState[url] != true) {
                delay(delay)
                if (connectionState[url] != true) {
                    Log.d(TAG, "Reconnecting to $url (delay=${delay}ms)")
                    connectToRelay(url)
                    if (getConnectedCount() < minConnectedRelays) {
                        // Below minimum: aggressive retry, cap at 5s
                        delay = (delay * 2).coerceAtMost(5_000L)
                    } else {
                        // Have enough connections: start at 60s, keep growing
                        delay = (delay * 2).coerceAtLeast(60_000L)
                    }
                }
            }
        }
    }

    private fun boostReconnectIfNeeded() {
        if (getConnectedCount() >= minConnectedRelays) return

        for (url in relayUrls) {
            if (connectionState[url] != true && reconnectJobs[url]?.isActive != true) {
                Log.d(TAG, "Boosting reconnect for $url (below minimum)")
                scheduleReconnect(url)
            }
        }
    }

    private fun emitConnectionCount() {
        val count = getConnectedCount()
        scope.launch {
            _connectionCount.emit(count)
        }
        if (count < minConnectedRelays) {
            boostReconnectIfNeeded()
        }
    }

    companion object {
        private const val TAG = "NostrClient"
        private const val MAX_PENDING_EVENTS = 50
        private const val PENDING_TTL_MS = 3_600_000L // 1 hour
    }
}
