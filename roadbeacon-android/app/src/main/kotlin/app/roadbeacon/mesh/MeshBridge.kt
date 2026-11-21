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

/**
 * One channel datagram; [receivedAt] is the host's receive time. [hops] is the flood hop count
 * (0 = heard from the sender's radio), null for a direct path or when the server does not say.
 */
data class MeshDatagram(val payload: ByteArray, val snrDb: Double, val receivedAt: String?, val hops: Int?)

/** The radio as the bridge sees it; [LocalApiTransport] in the app, a fake in tests. */
interface MeshTransport {
    val datagrams: Flow<MeshDatagram>
    val connected: StateFlow<Boolean>
    fun start()
    fun stop()
    suspend fun send(frame: ByteArray): Boolean

    /** The radio's modulation, for the airtime budget; null when not known. */
    val radio: RadioParams? get() = null

    /** The flood region a summary about this point may use; null = no scoped send possible. */
    suspend fun summaryRegion(lat: Double, lon: Double): String? = null

    /** Sends [frame] flooded only within [region]. */
    suspend fun sendScoped(frame: ByteArray, region: String): Boolean = false

    /** True when [sendZeroHop] works (MeshCore Open with `tx_path_data`). */
    val zeroHopCapable: Boolean get() = false

    /** Sends [frame] to direct neighbours only; nothing repeats it. */
    suspend fun sendZeroHop(frame: ByteArray): Boolean = false
}

/** LoRa modulation: spreading factor, bandwidth in Hz, coding rate denominator (5..8 for 4/5..4/8). */
data class RadioParams(val sf: Int, val bwHz: Int, val cr: Int) {
    companion object {
        /** What the airtime budget assumes when the radio did not say: slow, so conservative. */
        val ASSUMED = RadioParams(11, 250_000, 5)
    }
}

/** The switches of the MeshCore settings section, read on every decision. */
interface MeshBridgeConfig {
    val meshEnabled: Boolean
    val meshSendOwn: Boolean
    val meshReceive: Boolean
    val meshBridgeToNostr: Boolean
    val meshBridgeToRadio: Boolean
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
 *  - relay -> radio never carries a signed event or a vote: only a short unsigned summary
 *    ([BridgeSummaryCodec]), on demand, region-scoped, within the limits of [SummaryPlanner] and
 *    after winning the area's claim over Nostr ([BridgeClaims]), SPECIFICATION.md 4.2-4.3;
 *  - receiving never transmits: demand heard on the radio only schedules a later summary;
 *  - summaries heard on the radio are shown as unverified and go nowhere else;
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
    /** Live reports from the cache that a summary could carry (verified, signed, not unverified). */
    private val summaryCandidates: () -> List<SummaryPlanner.Candidate> = { emptyList() },
    /** An entry of a summary heard on the radio, with MeshCore Open's receive time (ms). */
    private val onUnverified: (SummaryEntry, Long) -> Unit = { _, _ -> },
    private val claims: BridgeClaims = BridgeClaims { _, _ -> false },
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
    private val planner = SummaryPlanner()
    /** Areas with a summary attempt waiting or running; one at a time per area. */
    private val attempts = HashSet<String>()

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
        if (!radioIn.allow(clock())) return
        val heardAt = datagram.receivedAt?.let(::parseInstant)?.toEpochMilli()?.coerceAtMost(clock()) ?: clock()
        when (BridgeSummaryCodec.family(datagram.payload)) {
            BridgeSummaryCodec.SUMMARY -> return onSummary(datagram.payload, heardAt)
            BridgeSummaryCodec.REQUEST -> return onRequest(datagram, heardAt)
            0x00 -> Unit
            else -> return
        }
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
        noteDemandFrom(event, datagram.hops, heardAt)

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

    /** Another bridge's summary: shown as unverified, and its area counts as served. */
    private fun onSummary(frame: ByteArray, heardAt: Long) {
        val entries = BridgeSummaryCodec.decodeSummary(frame) ?: return
        val refLat = (frame[1].toInt() shl 8 or (frame[2].toInt() and 0xFF)).toShort() * 10_000
        val refLon = (frame[3].toInt() shl 8 or (frame[4].toInt() and 0xFF)).toShort() * 10_000
        planner.noteServed(SummaryPlanner.areaOf(refLat, refLon), heardAt)
        entries.forEach { onUnverified(it, heardAt) }
    }

    /** A driver asked for a summary; it is demand only if the point is near this bridge. */
    private fun onRequest(datagram: MeshDatagram, heardAt: Long) {
        val request = BridgeSummaryCodec.decodeRequest(datagram.payload) ?: return
        val here = position() ?: return
        if (distanceMeters(here, request.latE6 / 1e6 to request.lonE6 / 1e6) > SummaryPlanner.DEMAND_RADIUS_METERS) return
        val demand = planner.noteDemand(request.latE6, request.lonE6, datagram.hops, heardAt, request)
        scheduleSummary(demand.area)
    }

    /** Someone else used the channel near here: a summary may be useful (sent later, if at all). */
    private fun noteDemandFrom(event: NostrEvent, hops: Int?, heardAt: Long) {
        val here = position() ?: return
        val where = if (event.kind == 1315) reportPosition(event) ?: return else here
        if (distanceMeters(here, where) > SummaryPlanner.DEMAND_RADIUS_METERS) return
        val demand = planner.noteDemand(toE6(where.first), toE6(where.second), hops, heardAt)
        scheduleSummary(demand.area)
    }

    /**
     * Called whenever a report enters the cache, from the radio or from a relay: applies votes that
     * were heard before it.
     */
    fun onReportKnown(reportId: String) {
        takeParked(reportId).forEach(onInbound)
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

    /**
     * Called for events learned from the relays (after they are in the cache). Never sends the event:
     * a new report near an area with demand only re-checks whether a summary is due there.
     */
    fun onRelayEvent(event: NostrEvent) {
        if (event.id.isEmpty()) return
        synchronized(lock) { ledger.getOrPut(event.id) { LedgerEntry() }.seenRelay = true }
        if (!summariesOn() || event.kind != 1315) return
        val where = reportPosition(event) ?: return
        for (demand in planner.demandedAreas(clock())) {
            val point = demand.latE6 / 1e6 to demand.lonE6 / 1e6
            if (distanceMeters(point, where) <= SummaryPlanner.DEMAND_RADIUS_METERS) scheduleSummary(demand.area)
        }
    }

    /** True when the bridge may consider a summary for the radio at all. */
    private fun summariesOn() = settings.meshEnabled && settings.meshBridgeToRadio

    /** Plans one summary attempt for [area] after the 2-10 s hold, unless one is already waiting. */
    private fun scheduleSummary(area: String) {
        if (!summariesOn()) return
        if (planner.ready(area, clock()) == null) return
        synchronized(lock) { if (!attempts.add(area)) return }
        scope.launch {
            try {
                delay(SUMMARY_HOLD_MIN_MS + random.nextLong(SUMMARY_HOLD_SPREAD_MS))
                trySummary(area)
            } finally {
                synchronized(lock) { attempts.remove(area) }
            }
        }
    }

    private suspend fun trySummary(area: String) {
        if (!summariesOn() || !transport.connected.value) return
        val started = clock()
        val demand = planner.ready(area, started) ?: return
        val here = position() ?: return
        val point = demand.latE6 / 1e6 to demand.lonE6 / 1e6
        if (distanceMeters(here, point) > SummaryPlanner.DEMAND_RADIUS_METERS) return
        val candidates = summaryCandidates().filter { c ->
            !c.id.startsWith("bridge:") &&
                synchronized(lock) { ledger[c.id]?.let { it.seenRadio || it.sentRadio } != true }
        }
        val plan = planner.select(demand, candidates, started) ?: return
        val frame = BridgeSummaryCodec.encodeSummary(demand.latE6, demand.lonE6, plan.entries) ?: return
        // Zero-hop when the asker was heard directly and the server can do it; else region-scoped;
        // else nothing. Never the radio's plain flood.
        val zeroHop = demand.hops == 0 && transport.zeroHopCapable
        val region = if (zeroHop) null else transport.summaryRegion(point.first, point.second) ?: return
        val radio = transport.radio ?: RadioParams.ASSUMED
        val airtime = BridgeSummaryCodec.airtimeMs(radio.sf, radio.bwHz, radio.cr, BridgeSummaryCodec.packetBytes(frame.size, region = region != null))
        if (!planner.budgetAllows(airtime, clock())) return
        if (!claims.claim(area, demand.hops ?: 255)) {
            planner.noteServed(area, clock())
            return
        }
        // Another bridge's summary for this area heard while waiting: it is covered.
        if ((planner.lastSummary(area) ?: 0L) >= started) return
        if (!summariesOn()) return
        val sent = if (region == null) transport.sendZeroHop(frame) else transport.sendScoped(frame, region)
        if (sent) planner.recordSent(plan, airtime, clock())
    }

    private suspend fun send(event: NostrEvent, frame: ByteArray, backlogOnFailure: Boolean) {
        if (transport.send(frame)) {
            synchronized(lock) { ledger.getOrPut(event.id) { LedgerEntry() }.sentRadio = true }
        } else if (backlogOnFailure) {
            backlog(event)
        }
    }

    private fun reportPosition(event: NostrEvent): Pair<Double, Double>? {
        val lat = event.tags.firstOrNull { it.size == 2 && it[0] == "lat" }?.get(1)?.toDoubleOrNull() ?: return null
        val lon = event.tags.firstOrNull { it.size == 2 && it[0] == "lon" }?.get(1)?.toDoubleOrNull() ?: return null
        return lat to lon
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
        private const val SUMMARY_HOLD_MIN_MS = 2_000L
        private const val SUMMARY_HOLD_SPREAD_MS = 8_000L
        private const val MAX_PARKED_VOTES = 64
        private const val MAX_PARKED_REPORTS = 32
        private const val PARKED_TTL_MS = 15 * 60 * 1000L
        private const val MAX_FUTURE_SKEW_SECONDS = 300L
        private const val MAX_VOTE_AGE_SECONDS = 30 * 86_400L

        internal fun toE6(degrees: Double): Int = Math.round(degrees * 1_000_000).toInt()

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
