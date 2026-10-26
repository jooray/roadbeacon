package app.roadbeacon.mesh

import android.util.Log
import app.roadbeacon.model.RoadEvent
import app.roadbeacon.nostr.BinaryCodec
import app.roadbeacon.nostr.EventSigner
import app.roadbeacon.nostr.NostrEvent
import app.roadbeacon.storage.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Moves road events between the MeshCore radio (through MeshCore Open's local
 * API) and the Nostr relays, without ever becoming the author of an event.
 *
 * Rules, all enforced here:
 *  - a datagram is decoded and its signature verified before anything uses it;
 *  - an event is sent to a transport only if that transport has not seen it and
 *    this bridge has not already sent it there (bounded ledger);
 *  - own events are rate-limited separately from other people's;
 *  - nothing is forwarded when the corresponding switch is off;
 *  - the author, ID and signature never change: this class never signs.
 */
class MeshBridge(
    private val settings: Settings,
    private val api: MeshCoreApiClient,
    private val scope: CoroutineScope,
    private val onInbound: (NostrEvent) -> Unit,
    private val publishToRelays: (NostrEvent) -> Unit
) {
    private data class LedgerEntry(
        var seenRadio: Boolean = false,
        var sentRadio: Boolean = false,
        var seenRelay: Boolean = false
    )

    private val lock = Any()
    private val ledger = object : LinkedHashMap<String, LedgerEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LedgerEntry>?) =
            size > MAX_LEDGER
    }

    private var collectJob: Job? = null
    private val ownSends = ArrayDeque<Long>()
    private val relaySends = ArrayDeque<Long>()
    private var lastRelaySendAt = 0L

    fun start() {
        api.start()
        collectJob = scope.launch {
            api.datagrams.collect { onDatagram(it.payload) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        api.stop()
    }

    // ── radio -> app (and relays) ───────────────────────────────────────────────────────────────

    private fun onDatagram(payload: ByteArray) {
        if (!settings.meshReceive) return
        val event = BinaryCodec.decode(payload) ?: return
        if (!EventSigner.verify(event)) {
            Log.w(TAG, "dropping radio event with an invalid signature")
            return
        }
        if (isExpired(event)) return

        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            if (entry.seenRadio) return
            entry.seenRadio = true
        }

        onInbound(event)
        if (settings.meshBridgeToNostr) {
            publishToRelays(event)
        }
    }

    // ── app -> radio ────────────────────────────────────────────────────────────────────────────

    /** Called after this device published [event] to the relays. */
    fun onOwnEvent(event: NostrEvent) {
        if (!settings.meshEnabled || !settings.meshSendOwn) return
        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            if (entry.sentRadio || entry.seenRadio) return
        }
        val frame = encode(event) ?: return
        if (!allow(ownSends, OWN_PER_MINUTE, 60_000)) return
        scope.launch {
            if (api.sendChannelData(frame)) {
                synchronized(lock) { ledger[event.id]?.sentRadio = true }
            }
        }
    }

    /** Called for events learned from the relays, when "relay -> radio" is on. */
    fun onRelayEvent(event: NostrEvent) {
        if (!settings.meshEnabled || !settings.meshBridgeToRadio) return
        if (event.id.isEmpty()) return
        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            entry.seenRelay = true
            if (entry.sentRadio || entry.seenRadio) return
        }
        val frame = encode(event) ?: return
        val now = System.currentTimeMillis()
        if (now - lastRelaySendAt < RELAY_GAP_MS) return
        if (!allow(relaySends, RELAY_PER_HOUR, 3_600_000)) return
        lastRelaySendAt = now
        scope.launch {
            if (api.sendChannelData(frame)) {
                synchronized(lock) { ledger[event.id]?.sentRadio = true }
            }
        }
    }

    private fun encode(event: NostrEvent): ByteArray? = when (event.kind) {
        1315 -> BinaryCodec.encodeReport(event)
        1316 -> BinaryCodec.encodeConfirmation(event)
        else -> null
    }

    private fun isExpired(event: NostrEvent): Boolean {
        val expiration = event.tags.firstOrNull { it.size == 2 && it[0] == "expiration" }
            ?.get(1)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() / 1000 >= expiration
    }

    private fun allow(window: ArrayDeque<Long>, limit: Int, periodMs: Long): Boolean {
        val now = System.currentTimeMillis()
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() > periodMs) {
                window.removeFirst()
            }
            if (window.size >= limit) return false
            window.addLast(now)
        }
        return true
    }

    companion object {
        private const val TAG = "MeshBridge"
        private const val MAX_LEDGER = 2048
        private const val OWN_PER_MINUTE = 6
        private const val RELAY_PER_HOUR = 20
        private const val RELAY_GAP_MS = 20_000L
    }
}
