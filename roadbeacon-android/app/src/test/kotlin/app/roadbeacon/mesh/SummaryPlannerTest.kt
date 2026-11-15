package app.roadbeacon.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPlannerTest {
    private val lat = 48_150_000
    private val lon = 17_110_000
    private val t0 = 1_761_552_000_000L

    private fun candidate(id: String, minutesOld: Long, category: Int = 0, dLat: Int = 0, shownFor: Long = 3_600) =
        SummaryPlanner.Candidate(id, lat + dLat, lon, category, t0 / 1000 - minutesOld * 60, t0 / 1000 + shownFor, 0, 0)

    @Test
    fun `demand expires after two hours`() {
        val p = SummaryPlanner()
        val d = p.noteDemand(lat, lon, 1, t0)
        assertNotNull(p.ready(d.area, t0 + 2 * 3_600_000L))
        assertNull(p.ready(d.area, t0 + 2 * 3_600_000L + 1))
    }

    @Test
    fun `newest first, at most five, at least one new, nothing about to vanish`() {
        val p = SummaryPlanner()
        val d = p.noteDemand(lat, lon, 0, t0)
        val all = (1..7).map { candidate("c$it", it * 10L) } + candidate("ending", 1, shownFor = 200) +
            candidate("far", 1, dLat = 400_000) + candidate("tooOld", 61, category = 2)
        val plan = p.select(d, all, t0)!!
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), plan.ids)
        p.recordSent(plan, 700.0, t0)
        assertNull("only repeats", p.select(d, all.take(5), t0 + 1))
        val next = p.select(d, all, t0 + 1)!!
        assertEquals(listOf("c6", "c7", "c1", "c2", "c3"), next.ids)
    }

    @Test
    fun `six per hour and 3_6 s of airtime`() {
        val p = SummaryPlanner()
        val d = p.noteDemand(lat, lon, 0, t0)
        val plan = p.select(d, listOf(candidate("a", 1)), t0)!!
        repeat(5) { p.recordSent(plan, 700.0, t0 + it) }
        assertFalse("airtime", p.budgetAllows(700.0, t0 + 10))
        assertTrue(p.budgetAllows(100.0, t0 + 10))
        p.recordSent(plan, 100.0, t0 + 11)
        assertFalse("count", p.budgetAllows(1.0, t0 + 12))
        assertTrue("rolling hour", p.budgetAllows(700.0, t0 + 3_600_020L))
    }

    @Test
    fun `requests filter by category, age and radius`() {
        val p = SummaryPlanner()
        val request = SummaryRequest(lat, lon, 5, 30 * 60L, listOf(3))
        val d = p.noteDemand(lat, lon, 0, t0, request)
        val plan = p.select(
            d,
            listOf(
                candidate("police", 1, category = 0), candidate("accident", 10, category = 3),
                candidate("oldAccident", 40, category = 3), candidate("farAccident", 1, category = 3, dLat = 60_000)
            ),
            t0
        )!!
        assertEquals(listOf("accident"), plan.ids)
    }

    @Test
    fun `a request shortens the interval to ten minutes for a while`() {
        val p = SummaryPlanner()
        val d = p.noteDemand(lat, lon, 0, t0)
        p.recordSent(p.select(d, listOf(candidate("a", 1)), t0)!!, 500.0, t0)
        assertNull(p.ready(d.area, t0 + 11 * 60_000L))
        p.noteDemand(lat, lon, 0, t0 + 11 * 60_000L, SummaryRequest(lat, lon, 25, null, emptyList()))
        assertNotNull(p.ready(d.area, t0 + 11 * 60_000L))
    }

    @Test
    fun `served by another bridge counts like an own summary`() {
        val p = SummaryPlanner()
        val d = p.noteDemand(lat, lon, 0, t0)
        p.noteServed(d.area, t0)
        assertNull(p.ready(d.area, t0 + 29 * 60_000L))
        assertNotNull(p.ready(d.area, t0 + 30 * 60_000L))
    }
}
