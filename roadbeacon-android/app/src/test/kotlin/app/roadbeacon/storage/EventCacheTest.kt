package app.roadbeacon.storage

import app.roadbeacon.model.*
import app.roadbeacon.nostr.RoadEventFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventCacheTest {

    private lateinit var cache: EventCache

    @Before
    fun setUp() {
        cache = EventCache()
    }

    @Test
    fun `addReport stores and retrieves event`() {
        val event = createReport("id1")
        cache.addReport(event)
        assertEquals(event, cache.getReport("id1"))
    }

    @Test
    fun `getReport returns null for unknown id`() {
        assertNull(cache.getReport("unknown"))
    }

    @Test
    fun `addConfirmation stores confirmation`() {
        val conf = createConfirmation("conf1", "event1", ConfirmationStatus.STILL_THERE)
        cache.addConfirmation(conf)
        val confs = cache.getConfirmations("event1")
        assertEquals(1, confs.size)
        assertEquals(conf, confs[0])
    }

    @Test
    fun `multiple confirmations for same event`() {
        cache.addConfirmation(createConfirmation("c1", "e1", ConfirmationStatus.STILL_THERE))
        cache.addConfirmation(createConfirmation("c2", "e1", ConfirmationStatus.NO_LONGER_THERE))
        val confs = cache.getConfirmations("e1")
        assertEquals(2, confs.size)
    }

    @Test
    fun `computeEffectiveExpiry without confirmations`() {
        val report = createReport("id1", createdAt = 1000L, type = EventType.POLICE)
        val expiry = cache.computeEffectiveExpiry(report, emptyList())
        assertEquals(1000L + EventType.POLICE.defaultTTL, expiry)
    }

    @Test
    fun `computeEffectiveExpiry with still_there extends`() {
        val report = createReport("id1", createdAt = 1000L, type = EventType.POLICE)
        val confs = listOf(
            createConfirmation("c1", "id1", ConfirmationStatus.STILL_THERE, createdAt = 5000L)
        )
        val expiry = cache.computeEffectiveExpiry(report, confs)
        // still_there at 5000 extends to 5000 + baseTTL
        val expected = 5000L + EventType.POLICE.defaultTTL
        assertEquals(expected, expiry)
    }

    @Test
    fun `computeEffectiveExpiry with no_longer_there shortens`() {
        val report = createReport("id1", createdAt = 1000L, type = EventType.POLICE)
        val confs = listOf(
            createConfirmation("c1", "id1", ConfirmationStatus.NO_LONGER_THERE, createdAt = 2000L)
        )
        val expiry = cache.computeEffectiveExpiry(report, confs)
        // no_longer_there at 2000 caps at 2000
        assertEquals(2000L, expiry)
    }

    @Test
    fun `computeEffectiveExpiry still_there after no_longer_there`() {
        val report = createReport("id1", createdAt = 1000L, type = EventType.POLICE)
        val confs = listOf(
            createConfirmation("c1", "id1", ConfirmationStatus.NO_LONGER_THERE, createdAt = 2000L),
            createConfirmation("c2", "id1", ConfirmationStatus.STILL_THERE, createdAt = 3000L)
        )
        val expiry = cache.computeEffectiveExpiry(report, confs)
        // no_longer_there at 2000 → minOf(baseTTL, 2000) = 2000
        // still_there at 3000 → maxOf(2000, 3000+baseTTL) = 3000+baseTTL
        // But since we min first then max, still_there dominates
        val expected = 3000L + EventType.POLICE.defaultTTL
        assertEquals(expected, expiry)
    }

    @Test
    fun `resolveConflicts returns ACTIVE when no recent confirmations`() {
        val status = cache.resolveConflicts(emptyList(), 10000L)
        assertEquals(EffectiveStatus.ACTIVE, status)
    }

    @Test
    fun `resolveConflicts returns ACTIVE when majority still_there`() {
        val now = 10000L
        val confs = listOf(
            createConfirmation("c1", "e1", ConfirmationStatus.STILL_THERE, createdAt = now - 100),
            createConfirmation("c2", "e1", ConfirmationStatus.STILL_THERE, createdAt = now - 50),
            createConfirmation("c3", "e1", ConfirmationStatus.NO_LONGER_THERE, createdAt = now - 200)
        )
        val status = cache.resolveConflicts(confs, now)
        assertEquals(EffectiveStatus.ACTIVE, status)
    }

    @Test
    fun `resolveConflicts returns EXPIRED when majority no_longer_there`() {
        val now = 10000L
        val confs = listOf(
            createConfirmation("c1", "e1", ConfirmationStatus.NO_LONGER_THERE, createdAt = now - 100),
            createConfirmation("c2", "e1", ConfirmationStatus.NO_LONGER_THERE, createdAt = now - 50),
            createConfirmation("c3", "e1", ConfirmationStatus.STILL_THERE, createdAt = now - 200)
        )
        val status = cache.resolveConflicts(confs, now)
        assertEquals(EffectiveStatus.EXPIRED, status)
    }

    @Test
    fun `resolveConflicts tie favors ACTIVE`() {
        val now = 10000L
        val confs = listOf(
            createConfirmation("c1", "e1", ConfirmationStatus.STILL_THERE, createdAt = now - 100),
            createConfirmation("c2", "e1", ConfirmationStatus.NO_LONGER_THERE, createdAt = now - 100)
        )
        val status = cache.resolveConflicts(confs, now)
        assertEquals(EffectiveStatus.ACTIVE, status)
    }

    @Test
    fun `resolveConflicts ignores old confirmations outside window`() {
        val now = 10000L
        val confs = listOf(
            createConfirmation("c1", "e1", ConfirmationStatus.NO_LONGER_THERE, createdAt = now - 600), // outside 5min window
            createConfirmation("c2", "e1", ConfirmationStatus.STILL_THERE, createdAt = now - 100)
        )
        val status = cache.resolveConflicts(confs, now)
        // Only one recent: still_there → ACTIVE
        assertEquals(EffectiveStatus.ACTIVE, status)
    }

    @Test
    fun `removeReport removes event and confirmations`() {
        cache.addReport(createReport("id1"))
        cache.addConfirmation(createConfirmation("c1", "id1", ConfirmationStatus.STILL_THERE))
        cache.removeReport("id1")
        assertNull(cache.getReport("id1"))
        assertTrue(cache.getConfirmations("id1").isEmpty())
    }

    @Test
    fun `getReportCount returns correct count`() {
        assertEquals(0, cache.getReportCount())
        cache.addReport(createReport("id1"))
        assertEquals(1, cache.getReportCount())
        cache.addReport(createReport("id2"))
        assertEquals(2, cache.getReportCount())
    }

    @Test
    fun `clear removes all data`() {
        cache.addReport(createReport("id1"))
        cache.addReport(createReport("id2"))
        cache.addConfirmation(createConfirmation("c1", "id1", ConfirmationStatus.STILL_THERE))
        cache.clear()
        assertEquals(0, cache.getReportCount())
        assertTrue(cache.getConfirmations("id1").isEmpty())
    }

    // Helper methods

    @Test
    fun `one key has one voice, its latest vote`() {
        val report = createReport("id1", createdAt = 1000L)
        val spam = (1..5).map {
            createConfirmation("d$it", "id1", ConfirmationStatus.NO_LONGER_THERE, 2000L + it, pubkey = "troll")
        }
        val votes = cache.admissible(report, spam + createConfirmation(
            "c1", "id1", ConfirmationStatus.STILL_THERE, 2001L, pubkey = "a"
        ), now = 2010L)
        assertEquals(2, votes.size)
        assertEquals(2005L, votes.first { it.pubkey == "troll" }.createdAt)
        // One denial against one confirmation: a tie, the report stays.
        assertEquals(EffectiveStatus.ACTIVE, cache.resolveConflicts(votes, now = 2010L))
    }

    @Test
    fun `votes from before the report or far in the future do not count`() {
        val report = createReport("id1", createdAt = 1000L)
        val votes = cache.admissible(report, listOf(
            createConfirmation("old", "id1", ConfirmationStatus.NO_LONGER_THERE, 999L, pubkey = "a"),
            createConfirmation("future", "id1", ConfirmationStatus.STILL_THERE, 5000L + 301, pubkey = "b"),
            createConfirmation("ok", "id1", ConfirmationStatus.STILL_THERE, 5000L + 300, pubkey = "c")
        ), now = 5000L)
        assertEquals(listOf("ok"), votes.map { it.id })
    }

    @Test
    fun `same-second denial and confirmation keep the report alive`() {
        val report = createReport("id1", createdAt = 1000L)
        val confs = listOf(
            createConfirmation("c", "id1", ConfirmationStatus.STILL_THERE, 3000L, pubkey = "a"),
            createConfirmation("d", "id1", ConfirmationStatus.NO_LONGER_THERE, 3000L, pubkey = "b")
        )
        assertEquals(3000L + EventType.POLICE.defaultTTL, cache.computeEffectiveExpiry(report, confs, now = 3000L))
    }

    @Test
    fun `a report never outlives its expiration tag`() {
        val report = createReport("id1", createdAt = 1000L, type = EventType.SPEED_CAMERA)
            .copy(expiration = 1000L + 3600L)
        assertEquals(1000L + 3600L, cache.computeEffectiveExpiry(report, emptyList()))
    }

    private fun createReport(
        id: String,
        createdAt: Long = System.currentTimeMillis() / 1000,
        type: EventType = EventType.POLICE
    ): RoadEvent {
        return RoadEvent(
            id = id,
            pubkey = "pubkey",
            type = type,
            lat = 48.8566,
            lon = 2.3522,
            createdAt = createdAt,
            expiration = RoadEventFormat.expirationFor(createdAt, type),
            content = ""
        )
    }

    private fun createConfirmation(
        id: String,
        eventId: String,
        status: ConfirmationStatus,
        createdAt: Long = System.currentTimeMillis() / 1000,
        pubkey: String = "pubkey"
    ): Confirmation {
        return Confirmation(
            id = id,
            pubkey = pubkey,
            eventId = eventId,
            status = status,
            createdAt = createdAt
        )
    }
}
