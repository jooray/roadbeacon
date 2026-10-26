package app.roadbeacon.util

import org.junit.Assert.*
import org.junit.Test

class UnitFormatterTest {

    @Test
    fun `metric distance in meters`() {
        assertEquals("500 m", UnitFormatter.formatDistanceMeters(500.0, UnitSystem.METRIC))
    }

    @Test
    fun `metric distance switches to km above 1000m`() {
        assertEquals("1.2 km", UnitFormatter.formatDistanceMeters(1200.0, UnitSystem.METRIC))
    }

    @Test
    fun `imperial short distance in feet`() {
        assertEquals("1640 ft", UnitFormatter.formatDistanceMeters(500.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `imperial long distance in miles`() {
        assertEquals("1.0 mi", UnitFormatter.formatDistanceMeters(1609.34, UnitSystem.IMPERIAL))
    }

    @Test
    fun `metric speed in kmh`() {
        assertEquals("80 km/h", UnitFormatter.formatSpeedKmh(80, UnitSystem.METRIC))
    }

    @Test
    fun `imperial speed in mph`() {
        assertEquals("50 mph", UnitFormatter.formatSpeedKmh(80, UnitSystem.IMPERIAL))
    }

    @Test
    fun `fromStored defaults to metric`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.fromStored(null))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromStored("metric"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.fromStored("imperial"))
    }
}
