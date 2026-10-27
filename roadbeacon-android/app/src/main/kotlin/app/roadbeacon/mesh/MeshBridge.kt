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
    private val publishToRelays: (NostrEvent) -> Unit,
    /** Driver position, for the area check on relay -> radio. */
    private val position: () -> Pair<Double, Double>? = { null },
    /** True when the report is already in the local cache. */
    private val reportExists: (String) -> Boolean = { false }
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

    private data class ParkedVote(val at: Long, val event: NostrEvent)

    private val parked = object : LinkedHashMap<String, MutableList<ParkedVote>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ParkedVote>>?) =
            size > MAX_PARKED_REPORTS
    }
    private var parkedCount = 0

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
        dropStaleParkedVotes()

        // A vote can arrive before its report; hold it briefly and apply it when the report lands.
        if (event.kind == 1316) {
            val target = event.tags.firstOrNull { it.size == 2 && it[0] == "e" }?.getOrNull(1)
            if (target != null && !reportExists(target)) {
                park(target, event)
                return
            }
        }

        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            if (entry.seenRadio) return
            entry.seenRadio = true
        }

        settings.meshSince = maxOf(settings.meshSince, System.currentTimeMillis())
        onInbound(event)

        // If this was a report, apply votes that were waiting for it.
        if (event.kind == 1315) {
            takeParked(event.id).forEach { vote ->
                synchronized(lock) {
                    val entry = ledger.getOrPut(vote.id) { LedgerEntry() }
                    if (entry.seenRadio) return@forEach
                    entry.seenRadio = true
                }
                onInbound(vote)
            }
        }

        if (settings.meshBridgeToNostr) {
            publishToRelays(event)
        }
    }

    private fun park(reportId: String, vote: NostrEvent) {
        synchronized(lock) {
            if (parkedCount >= MAX_PARKED_VOTES) return
            val list = parked.getOrPut(reportId) { mutableListOf() }
            list.add(ParkedVote(System.currentTimeMillis(), vote))
            parkedCount++
        }
    }

    private fun takeParked(reportId: String): List<NostrEvent> = synchronized(lock) {
        val list = parked.remove(reportId).orEmpty()
        parkedCount -= list.size
        list.map { it.event }
    }

    private fun dropStaleParkedVotes() {
        val cutoff = System.currentTimeMillis() - PARKED_TTL_MS
        synchronized(lock) {
            val iterator = parked.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.value.removeAll { it.at < cutoff }
                if (entry.value.isEmpty()) iterator.remove()
            }
            parkedCount = parked.values.sumOf { it.size }
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
        // Relay -> radio is limited to what is near the driver; without a fix, skip.
        val here = position() ?: return
        val where = eventLocation(event) ?: return
        if (distanceMeters(here, where) > RELAY_RADIUS_METERS) return
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

    private fun eventLocation(event: NostrEvent): Pair<Double, Double>? {
        val lat = event.tags.firstOrNull { it.size == 2 && it[0] == "lat" }?.getOrNull(1)
            ?.toDoubleOrNull() ?: return null
        val lon = event.tags.firstOrNull { it.size == 2 && it[0] == "lon" }?.getOrNull(1)
            ?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * earth * Math.asin(Math.sqrt(h))
    }

    companion object {
        private const val TAG = "MeshBridge"
        private const val MAX_LEDGER = 2048
        private const val OWN_PER_MINUTE = 6
        private const val RELAY_PER_HOUR = 20
        private const val RELAY_GAP_MS = 20_000L
        private const val RELAY_RADIUS_METERS = 40_000.0
        private const val MAX_PARKED_VOTES = 64
        private const val MAX_PARKED_REPORTS = 32
        private const val PARKED_TTL_MS = 15 * 60 * 1000L
    }
}
