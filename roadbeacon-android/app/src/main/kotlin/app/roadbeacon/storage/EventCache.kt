package app.roadbeacon.storage

import app.roadbeacon.model.Confirmation
import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.model.RoadEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask

enum class EffectiveStatus { ACTIVE, EXPIRED }

class EventCache {

    private val reports = ConcurrentHashMap<String, RoadEvent>()
    private val confirmations = ConcurrentHashMap<String, MutableList<Confirmation>>()
    private var cleanupTimer: Timer? = null

    private val _eventsChanged = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 10)
    val eventsChanged: SharedFlow<Unit> = _eventsChanged

    var onEventsRemoved: ((List<String>) -> Unit)? = null

    fun start() {
        cleanupTimer = Timer("EventCacheCleanup", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    cleanupExpired()
                }
            }, 60_000L, 60_000L)
        }
    }

    fun stop() {
        cleanupTimer?.cancel()
        cleanupTimer = null
    }

    fun addReport(event: RoadEvent) {
        reports[event.id] = event
        _eventsChanged.tryEmit(Unit)
    }

    fun addConfirmation(confirmation: Confirmation) {
        val list = confirmations.getOrPut(confirmation.eventId) { mutableListOf() }
        if (list.none { it.id == confirmation.id }) {
            list.add(confirmation)
        }
    }

    fun getReport(id: String): RoadEvent? = reports[id]

    fun getConfirmations(eventId: String): List<Confirmation> =
        confirmations[eventId]?.toList() ?: emptyList()

    fun getAllActiveReports(): List<RoadEvent> {
        val now = System.currentTimeMillis() / 1000
        return reports.values.filter { report ->
            val effectiveExpiry = computeEffectiveExpiry(report, getConfirmations(report.id))
            val status = resolveConflicts(getConfirmations(report.id), now)
            now < effectiveExpiry && status == EffectiveStatus.ACTIVE
        }
    }

    fun removeReport(id: String) {
        reports.remove(id)
        confirmations.remove(id)
        _eventsChanged.tryEmit(Unit)
    }

    fun computeEffectiveExpiry(report: RoadEvent, confs: List<Confirmation>): Long {
        val baseTTL = report.type.defaultTTL
        var effectiveExpiry = report.createdAt + baseTTL

        val sorted = confs.sortedBy { it.createdAt }

        for (conf in sorted) {
            when (conf.status) {
                ConfirmationStatus.STILL_THERE -> {
                    val newExpiry = conf.createdAt + baseTTL
                    effectiveExpiry = maxOf(effectiveExpiry, newExpiry)
                }
                ConfirmationStatus.NO_LONGER_THERE -> {
                    effectiveExpiry = minOf(effectiveExpiry, conf.createdAt)
                }
            }
        }

        return effectiveExpiry
    }

    fun resolveConflicts(
        confs: List<Confirmation>,
        now: Long,
        conflictWindow: Long = 300L
    ): EffectiveStatus {
        val recent = confs.filter { now - it.createdAt < conflictWindow }
        if (recent.isEmpty()) return EffectiveStatus.ACTIVE

        val stillThere = recent.count { it.status == ConfirmationStatus.STILL_THERE }
        val noLonger = recent.count { it.status == ConfirmationStatus.NO_LONGER_THERE }

        return if (noLonger > stillThere) EffectiveStatus.EXPIRED else EffectiveStatus.ACTIVE
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis() / 1000
        val expired = reports.values.filter { report ->
            val effectiveExpiry = computeEffectiveExpiry(report, getConfirmations(report.id))
            now >= effectiveExpiry
        }
        expired.forEach { removeReport(it.id) }
        if (expired.isNotEmpty()) {
            onEventsRemoved?.invoke(expired.map { it.id })
        }
    }

    fun getReportCount(): Int = reports.size

    fun clear() {
        reports.clear()
        confirmations.clear()
    }
}
