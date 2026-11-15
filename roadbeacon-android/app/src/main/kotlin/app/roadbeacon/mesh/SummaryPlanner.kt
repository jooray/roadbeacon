package app.roadbeacon.mesh

import app.roadbeacon.location.GeohashUtil

/**
 * Decides when a bridge may put a summary of relay reports on the radio and what goes in it,
 * SPECIFICATION.md section 4.3. Pure state, no I/O: the bridge feeds it what it heard and asks.
 *
 *  - demand: something heard on the radio from another node in the area in the last 2 hours;
 *  - one summary per area (geohash 4) per 30 minutes, 10 after a request, counting summaries of
 *    other bridges and claims they won;
 *  - at most 6 summaries and 0.1 % airtime (3.6 s) per rolling hour;
 *  - content: up to 5 live reports near the demand point, newest first, at least one of them not
 *    in a summary for this area in the last 2 hours.
 */
class SummaryPlanner {
    data class Demand(
        val area: String,
        val latE6: Int,
        val lonE6: Int,
        /** Flood hops at which it was heard; null when not known. */
        val hops: Int?,
        val atMs: Long,
        val request: SummaryRequest?,
        val requestAtMs: Long = 0L
    )

    /** A report the bridge could summarise: verified, from the relays, not heard on the radio. */
    data class Candidate(
        val id: String,
        val latE6: Int,
        val lonE6: Int,
        val category: Int,
        val createdAt: Long,
        /** When it stops being shown (votes included), epoch seconds. */
        val shownUntil: Long,
        val stillThere: Int,
        val gone: Int
    )

    data class Plan(val demand: Demand, val ids: List<String>, val entries: List<SummaryEntry>)

    private val demands = HashMap<String, Demand>()
    private val lastSummaryAt = HashMap<String, Long>()
    private val sentIds = HashMap<String, MutableMap<String, Long>>()
    private val sends = ArrayDeque<Pair<Long, Double>>()

    @Synchronized
    fun noteDemand(latE6: Int, lonE6: Int, hops: Int?, atMs: Long, request: SummaryRequest? = null): Demand {
        val area = areaOf(latE6, lonE6)
        val known = demands[area]
        // A replayed frame older than what is known adds nothing.
        if (known != null && known.atMs > atMs && request == null) return known
        // A request keeps its shorter interval for a while even when plain demand follows it.
        val keep = known?.takeIf { it.request != null && request == null && atMs - it.requestAtMs < AREA_INTERVAL_MS }
        val demand = Demand(
            area, latE6, lonE6, hops, maxOf(atMs, known?.atMs ?: 0L),
            request = request ?: keep?.request,
            requestAtMs = if (request != null) atMs else keep?.requestAtMs ?: 0L
        )
        demands[area] = demand
        return demand
    }

    /** Another bridge covered [area] (its summary was heard, or it won the claim). */
    @Synchronized
    fun noteServed(area: String, nowMs: Long) {
        lastSummaryAt[area] = maxOf(lastSummaryAt[area] ?: 0L, nowMs)
    }

    @Synchronized
    fun lastSummary(area: String): Long? = lastSummaryAt[area]

    /** The demand for [area] if a summary may be sent for it now, else null. */
    @Synchronized
    fun ready(area: String, nowMs: Long): Demand? {
        val demand = demands[area] ?: return null
        if (nowMs - demand.atMs > DEMAND_WINDOW_MS) {
            demands.remove(area)
            return null
        }
        val asked = demand.request != null && nowMs - demand.requestAtMs < AREA_INTERVAL_MS
        val gap = if (asked) REQUEST_INTERVAL_MS else AREA_INTERVAL_MS
        val last = lastSummaryAt[area]
        if (last != null && nowMs - last < gap) return null
        return demand
    }

    /** Areas with demand that is still fresh, for re-checking when the relays bring news. */
    @Synchronized
    fun demandedAreas(nowMs: Long): List<Demand> {
        demands.values.removeAll { nowMs - it.atMs > DEMAND_WINDOW_MS }
        return demands.values.toList()
    }

    /** What to send for [demand], or null when there is nothing new worth the airtime. */
    @Synchronized
    fun select(demand: Demand, candidates: List<Candidate>, nowMs: Long): Plan? {
        val now = nowMs / 1000
        val request = demand.request
        val radius = (request?.radiusKm ?: BridgeSummaryCodec.MAX_RADIUS_KM) * 1000.0
        val recent = sentIds[demand.area]?.also { ids -> ids.values.removeAll { nowMs - it > REPEAT_WINDOW_MS } }.orEmpty()
        val eligible = candidates.filter { c ->
            val lifetime = BridgeSummaryCodec.LIFETIME[c.category] ?: return@filter false
            val age = now - c.createdAt
            age in 0..lifetime &&
                c.shownUntil - now >= MIN_REMAINING_SECONDS &&
                (request?.sinceSeconds == null || age <= request.sinceSeconds) &&
                (request == null || request.categories.isEmpty() || c.category in request.categories) &&
                BridgeSummaryCodec.fits(demand.latE6, demand.lonE6, c.latE6, c.lonE6) &&
                MeshBridge.distanceMeters(demand.latE6 / 1e6 to demand.lonE6 / 1e6, c.latE6 / 1e6 to c.lonE6 / 1e6) <= radius
        }.sortedByDescending { it.createdAt }
        val fresh = eligible.filter { it.id !in recent }
        if (fresh.isEmpty()) return null
        val chosen = (fresh + eligible.filter { it.id in recent }).take(BridgeSummaryCodec.MAX_ENTRIES)
        val entries = chosen.map { c ->
            SummaryEntry(
                c.latE6, c.lonE6, c.category,
                ageSeconds = now - c.createdAt,
                remainingSeconds = minOf(c.shownUntil - now, MAX_REMAINING_SECONDS),
                stillThere = c.stillThere,
                gone = c.gone
            )
        }
        return Plan(demand, chosen.map { it.id }, entries)
    }

    @Synchronized
    fun budgetAllows(airtimeMs: Double, nowMs: Long): Boolean {
        sends.removeAll { nowMs - it.first >= HOUR_MS }
        return sends.size < MAX_PER_HOUR && sends.sumOf { it.second } + airtimeMs <= AIRTIME_BUDGET_MS
    }

    @Synchronized
    fun recordSent(plan: Plan, airtimeMs: Double, nowMs: Long) {
        sends.addLast(nowMs to airtimeMs)
        lastSummaryAt[plan.demand.area] = nowMs
        val ids = sentIds.getOrPut(plan.demand.area) { HashMap() }
        plan.ids.forEach { ids[it] = nowMs }
        if (sentIds.size > MAX_AREAS) sentIds.keys.firstOrNull()?.let { sentIds.remove(it) }
        if (lastSummaryAt.size > MAX_AREAS) lastSummaryAt.entries.minByOrNull { it.value }?.let { lastSummaryAt.remove(it.key) }
    }

    companion object {
        const val DEMAND_WINDOW_MS = 2 * 3_600_000L
        const val AREA_INTERVAL_MS = 30 * 60_000L
        const val REQUEST_INTERVAL_MS = 10 * 60_000L
        const val REPEAT_WINDOW_MS = 2 * 3_600_000L
        const val HOUR_MS = 3_600_000L
        const val MAX_PER_HOUR = 6
        /** 0.1 % of an hour. */
        const val AIRTIME_BUDGET_MS = 3_600.0
        const val MIN_REMAINING_SECONDS = 300L
        /** The longest `remaining` a duration code carries. */
        const val MAX_REMAINING_SECONDS = 30 * 86_400L
        const val DEMAND_RADIUS_METERS = 25_000.0
        private const val MAX_AREAS = 256

        fun areaOf(latE6: Int, lonE6: Int): String = GeohashUtil.encode(latE6 / 1e6, lonE6 / 1e6, 4)
    }
}
