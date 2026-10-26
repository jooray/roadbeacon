package app.roadbeacon.location

import org.junit.Assert.*
import org.junit.Test

class GeohashUtilTest {

    @Test
    fun `encode known coordinates`() {
        // Paris: 48.8566, 2.3522
        val hash = GeohashUtil.encode(48.8566, 2.3522, 6)
        assertTrue(hash.startsWith("u09"))
        assertEquals(6, hash.length)
    }

    @Test
    fun `encode London coordinates`() {
        // London: 51.5074, -0.1278
        val hash = GeohashUtil.encode(51.5074, -0.1278, 6)
        assertTrue("London hash should start with 'gc', got '$hash'", hash.startsWith("gc"))
        assertEquals(6, hash.length)
    }

    @Test
    fun `encode returns correct precision length`() {
        for (precision in 1..9) {
            val hash = GeohashUtil.encode(48.8566, 2.3522, precision)
            assertEquals(precision, hash.length)
        }
    }

    @Test
    fun `encode uses valid base32 characters`() {
        val validChars = "0123456789bcdefghjkmnpqrstuvwxyz"
        val hash = GeohashUtil.encode(48.8566, 2.3522, 9)
        assertTrue(hash.all { it in validChars })
    }

    @Test
    fun `decode returns center of geohash cell`() {
        // Encode Paris coordinates then decode should give close result
        val hash = GeohashUtil.encode(48.8566, 2.3522, 7)
        val (lat, lon) = GeohashUtil.decode(hash)
        assertEquals(48.8566, lat, 0.001)
        assertEquals(2.3522, lon, 0.001)
    }

    @Test
    fun `encode then decode round trip`() {
        val originalLat = 48.8566140
        val originalLon = 2.3522219

        val hash = GeohashUtil.encode(originalLat, originalLon, 9)
        val (decodedLat, decodedLon) = GeohashUtil.decode(hash)

        // 9-char geohash has ~5m precision
        assertEquals(originalLat, decodedLat, 0.0001)
        assertEquals(originalLon, decodedLon, 0.0001)
    }

    @Test
    fun `neighbors returns 9 cells`() {
        val hash = GeohashUtil.encode(48.8566, 2.3522, 5)
        val nbrs = GeohashUtil.neighbors(hash)
        assertEquals(9, nbrs.size)
        assertTrue(nbrs.contains(hash))
    }

    @Test
    fun `neighbors are all same precision`() {
        val hash = GeohashUtil.encode(48.8566, 2.3522, 5)
        val nbrs = GeohashUtil.neighbors(hash)
        assertTrue(nbrs.all { it.length == 5 })
    }

    @Test
    fun `neighbors at precision 4`() {
        val hash = GeohashUtil.encode(48.8566, 2.3522, 4)
        val nbrs = GeohashUtil.neighbors(hash)
        assertEquals(9, nbrs.size)
        assertTrue(nbrs.all { it.length == 4 })
    }

    @Test
    fun `neighbors contains unique values`() {
        val hash = GeohashUtil.encode(48.8566, 2.3522, 5)
        val nbrs = GeohashUtil.neighbors(hash)
        assertEquals(nbrs.size, nbrs.toSet().size)
    }

    @Test
    fun `encode handles negative coordinates`() {
        // Buenos Aires: -34.6037, -58.3816
        val hash = GeohashUtil.encode(-34.6037, -58.3816, 6)
        assertEquals(6, hash.length)
        // Verify round-trip works for negative coordinates
        val (lat, lon) = GeohashUtil.decode(hash)
        assertEquals(-34.6037, lat, 0.1)
        assertEquals(-58.3816, lon, 0.1)
    }

    @Test
    fun `encode handles equator and prime meridian`() {
        val hash = GeohashUtil.encode(0.0, 0.0, 6)
        assertEquals(6, hash.length)
        assertEquals("s00000", hash)
    }

    @Test
    fun `encode handles boundary values`() {
        // Max lat/lon
        val hash1 = GeohashUtil.encode(90.0, 180.0, 6)
        assertEquals(6, hash1.length)

        // Min lat/lon
        val hash2 = GeohashUtil.encode(-90.0, -180.0, 6)
        assertEquals(6, hash2.length)
    }

    @Test
    fun `shorter geohash is prefix of longer`() {
        val lat = 48.8566
        val lon = 2.3522
        val short = GeohashUtil.encode(lat, lon, 4)
        val long = GeohashUtil.encode(lat, lon, 6)
        assertTrue(long.startsWith(short))
    }
}
