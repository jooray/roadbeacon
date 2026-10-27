package app.roadbeacon.mesh

import android.util.Log
import app.roadbeacon.model.EventType
import app.roadbeacon.nostr.BinaryCodec
import app.roadbeacon.nostr.EventSigner
import app.roadbeacon.nostr.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

/** The radio as the bridge sees it; [MeshCoreApiClient] in the app, a fake in tests. */
interface MeshTransport {
    val datagrams: Flow<MeshDatagram>
    val connected: StateFlow<Boolean>
    fun start()
    fun stop()
    suspend fun send(frame: ByteArray): Boolean
}

/** The switches of the MeshCore settings section, read on every decision. */
interface MeshBridgeConfig {
    val meshEnabled: Boolean
    val meshSendOwn: Boolean
    val meshReceive: Boolean
    val meshBridgeToNostr: Boolean
    val meshBridgeToRadio: Boolean
    var meshCursor: String?
}

/**
 * Moves road events between the MeshCore radio (through MeshCore Open's local
 * API) and the Nostr relays, without ever becoming the author of an event.
 *
 * Rules, all enforced here:
 *  - a datagram is decoded and its signature verified before anything uses it;
 *  - nothing stale or dated in the future is shown or forwarded;
 *  - an event is sent to a transport only if that transport has not seen it and
 *    this bridge has not already sent it there (bounded ledger, by event ID);
 *  - relay -> radio only carries what is still shown, near the driver, and waits a
 *    random moment first so that, of several bridges in range, usually one sends;
 *  - every direction is rate-limited, own events separately from other people's;
 *  - nothing is forwarded when the corresponding switch is off;
 *  - the author, ID and signature never change: this class never signs.
 */
class MeshBridge(
    private val settings: MeshBridgeConfig,
    private val transport: MeshTransport,
    private val scope: CoroutineScope,
    private val onInbound: (NostrEvent) -> Unit,
    private val publishToRelays: (NostrEvent) -> Unit,
    /** A recent driver position, for the area check on relay -> radio; null without a fresh fix. */
    private val position: () -> Pair<Double, Double>? = { null },
    /** True when the report is already in the local cache. */
    private val reportExists: (String) -> Boolean = { false },
    /** Position of a cached report that is still shown (votes included), or null. */
    private val liveReportLocation: (String) -> Pair<Double, Double>? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
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

    /** Own events made while the radio was away, sent when it comes back. */
    private val ownBacklog = ArrayDeque<Pair<Long, NostrEvent>>()

    private val jobs = mutableListOf<Job>()
    private val radioIn = RateWindow(RADIO_IN_PER_MINUTE, 60_000)
    private val ownSends = RateWindow(OWN_PER_MINUTE, 60_000)
    private val relaySends = RateWindow(RELAY_PER_HOUR, 3_600_000)
    private val relaySendsByAuthor = HashMap<String, RateWindow>()
    private var lastRelaySendAt = 0L

    fun start() {
        transport.start()
        jobs += scope.launch {
            transport.datagrams.collect { onDatagram(it) }
        }
        jobs += scope.launch {
            transport.connected.collect { if (it) flushOwnBacklog() }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        transport.stop()
    }

    // ── radio -> app (and relays) ───────────────────────────────────────────────────────────────

    internal fun onDatagram(datagram: MeshDatagram) {
        if (!settings.meshReceive) return
        advanceCursor(datagram.receivedAt)
        if (!radioIn.allow(clock())) return
        val event = BinaryCodec.decode(datagram.payload) ?: return
        if (!EventSigner.verify(event)) {
            Log.w(TAG, "dropping radio event with an invalid signature")
            return
        }
        if (isStale(event)) return

        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            if (entry.seenRadio) return
            entry.seenRadio = true
        }

        // Relays do not need the report to accept a vote, so bridging never waits.
        if (settings.meshBridgeToNostr) {
            publishToRelays(event)
        }

        if (event.kind == 1316) {
            val target = event.tags[0][1]
            // A vote can arrive before its report; hold it and apply it when the report lands.
            if (!reportExists(target)) {
                park(target, event)
                return
            }
        }
        onInbound(event)
    }

    /**
     * Called whenever a report enters the cache, from the radio or from a relay: applies votes that
     * were heard before it.
     */
    fun onReportKnown(reportId: String) {
        takeParked(reportId).forEach(onInbound)
    }

    private fun advanceCursor(receivedAt: String?) {
        val next = receivedAt?.let(::parseInstant) ?: return
        val current = settings.meshCursor?.let(::parseInstant)
        if (current == null || next.isAfter(current)) settings.meshCursor = receivedAt
    }

    private fun park(reportId: String, vote: NostrEvent) {
        val now = clock()
        synchronized(lock) {
            dropStaleParkedVotes(now)
            if (parked.values.sumOf { it.size } >= MAX_PARKED_VOTES) return
            parked.getOrPut(reportId) { mutableListOf() }.add(ParkedVote(now, vote))
        }
    }

    private fun takeParked(reportId: String): List<NostrEvent> = synchronized(lock) {
        dropStaleParkedVotes(clock())
        parked.remove(reportId).orEmpty().map { it.event }
    }

    private fun dropStaleParkedVotes(now: Long) {
        val cutoff = now - PARKED_TTL_MS
        val iterator = parked.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { it.at < cutoff }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    // ── app -> radio ────────────────────────────────────────────────────────────────────────────

    /** Called after this device signed [event]; sent now, or when the radio comes back. */
    fun onOwnEvent(event: NostrEvent) {
        if (!settings.meshEnabled || !settings.meshSendOwn) return
        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            if (entry.sentRadio || entry.seenRadio) return
        }
        val frame = encode(event) ?: return
        if (!transport.connected.value) {
            backlog(event)
            return
        }
        if (!ownSends.allow(clock())) return
        scope.launch { send(event, frame, backlogOnFailure = true) }
    }

    private fun backlog(event: NostrEvent) = synchronized(ownBacklog) {
        ownBacklog.removeAll { it.second.id == event.id }
        ownBacklog.addLast(clock() to event)
        while (ownBacklog.size > MAX_OWN_BACKLOG) ownBacklog.removeFirst()
    }

    private suspend fun flushOwnBacklog() {
        val now = clock()
        val due = synchronized(ownBacklog) {
            val list = ownBacklog.filter { now - it.first <= OWN_BACKLOG_TTL_MS }.map { it.second }
            ownBacklog.clear()
            list
        }
        for (event in due) {
            if (!settings.meshEnabled || !settings.meshSendOwn) return
            if (isStale(event)) continue
            val frame = encode(event) ?: continue
            if (!ownSends.allow(clock())) {
                backlog(event)
                continue
            }
            send(event, frame, backlogOnFailure = false)
        }
    }

    /** Called for events learned from the relays (after they are in the cache), when "relay -> radio" is on. */
    fun onRelayEvent(event: NostrEvent) {
        if (!settings.meshEnabled || !settings.meshBridgeToRadio) return
        if (event.id.isEmpty()) return
        synchronized(lock) {
            val entry = ledger.getOrPut(event.id) { LedgerEntry() }
            entry.seenRelay = true
            if (entry.sentRadio || entry.seenRadio) return
        }
        if (isStale(event)) return
        val frame = encode(event) ?: return

        // Only what is still on the map: a report itself, or the report a vote is about.
        val reportId = if (event.kind == 1316) event.tags[0][1] else event.id
        val where = liveReportLocation(reportId) ?: return
        val here = position() ?: return
        if (distanceMeters(here, where) > RELAY_RADIUS_METERS) return

        val now = clock()
        synchronized(lock) {
            if (now - lastRelaySendAt < RELAY_GAP_MS) return
            val byAuthor = relaySendsByAuthor.getOrPut(event.pubkey) {
                RateWindow(RELAY_PER_AUTHOR_PER_HOUR, 3_600_000)
            }
            if (!byAuthor.wouldAllow(now) || !relaySends.allow(now)) return
            byAuthor.allow(now)
            if (relaySendsByAuthor.size > MAX_LEDGER) relaySendsByAuthor.clear()
            lastRelaySendAt = now
        }
        scope.launch {
            // Listen before talking: another bridge in range may put it on the air first.
            delay(RELAY_HOLD_MIN_MS + random.nextLong(RELAY_HOLD_SPREAD_MS))
            val heard = synchronized(lock) { ledger[event.id]?.let { it.seenRadio || it.sentRadio } == true }
            if (!heard) send(event, frame, backlogOnFailure = false)
        }
    }

    private suspend fun send(event: NostrEvent, frame: ByteArray, backlogOnFailure: Boolean) {
        if (transport.send(frame)) {
            synchronized(lock) { ledger.getOrPut(event.id) { LedgerEntry() }.sentRadio = true }
        } else if (backlogOnFailure) {
            backlog(event)
        }
    }

    private fun encode(event: NostrEvent): ByteArray? = when (event.kind) {
        1315 -> BinaryCodec.encodeReport(event)
        1316 -> BinaryCodec.encodeConfirmation(event)
        else -> null
    }

    /**
     * Dated in the future, past its `expiration`, past its category's lifetime (a report), or older
     * than any report can live (a vote). Votes on a report can extend it, so the lifetime check
     * applies to what the radio carries, not to what the cache shows.
     */
    private fun isStale(event: NostrEvent): Boolean {
        val now = clock() / 1000
        if (event.createdAt > now + MAX_FUTURE_SKEW_SECONDS) return true
        return when (event.kind) {
            1315 -> {
                val expiration = event.tags.firstOrNull { it.size == 2 && it[0] == "expiration" }
                    ?.get(1)?.toLongOrNull() ?: return true
                val type = event.tags.firstOrNull { it.size == 2 && it[0] == "t" }
                    ?.get(1)?.let(EventType::fromTypeName) ?: return true
                now >= expiration || now >= event.createdAt + type.defaultTTL
            }
            1316 -> now - event.createdAt > MAX_VOTE_AGE_SECONDS
            else -> true
        }
    }

    private class RateWindow(private val limit: Int, private val periodMs: Long) {
        private val times = ArrayDeque<Long>()

        @Synchronized
        fun wouldAllow(now: Long): Boolean {
            while (times.isNotEmpty() && now - times.first() > periodMs) times.removeFirst()
            return times.size < limit
        }

        @Synchronized
        fun allow(now: Long): Boolean {
            if (!wouldAllow(now)) return false
            times.addLast(now)
            return true
        }
    }

    companion object {
        private const val TAG = "MeshBridge"
        private const val MAX_LEDGER = 2048
        private const val RADIO_IN_PER_MINUTE = 60
        private const val OWN_PER_MINUTE = 6
        private const val MAX_OWN_BACKLOG = 20
        private const val OWN_BACKLOG_TTL_MS = 60 * 60 * 1000L
        private const val RELAY_PER_HOUR = 20
        private const val RELAY_PER_AUTHOR_PER_HOUR = 4
        private const val RELAY_GAP_MS = 20_000L
        private const val RELAY_HOLD_MIN_MS = 3_000L
        private const val RELAY_HOLD_SPREAD_MS = 12_000L
        private const val RELAY_RADIUS_METERS = 40_000.0
        private const val MAX_PARKED_VOTES = 64
        private const val MAX_PARKED_REPORTS = 32
        private const val PARKED_TTL_MS = 15 * 60 * 1000L
        private const val MAX_FUTURE_SKEW_SECONDS = 300L
        private const val MAX_VOTE_AGE_SECONDS = 30 * 86_400L

        private fun parseInstant(text: String): Instant? =
            try { Instant.parse(text) } catch (_: Exception) { null }

        internal fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val earth = 6_371_000.0
            val dLat = Math.toRadians(b.first - a.first)
            val dLon = Math.toRadians(b.second - a.second)
            val lat1 = Math.toRadians(a.first)
            val lat2 = Math.toRadians(b.first)
            val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * earth * Math.asin(Math.sqrt(h))
        }
    }
}
