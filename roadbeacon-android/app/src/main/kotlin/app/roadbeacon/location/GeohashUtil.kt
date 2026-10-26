package app.roadbeacon.location

object GeohashUtil {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        val hash = StringBuilder()
        var bit = 0
        var ch = 0
        var isLon = true

        while (hash.length < precision) {
            if (isLon) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonMin = mid
                } else {
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }

            isLon = !isLon
            bit++

            if (bit == 5) {
                hash.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }

        return hash.toString()
    }

    fun decode(hash: String): Pair<Double, Double> {
        val bbox = decodeBbox(hash)
        return Pair(bbox.lat, bbox.lon)
    }

    fun neighbors(hash: String): List<String> {
        val bbox = decodeBbox(hash)
        val precision = hash.length
        val result = mutableSetOf(hash)

        val offsets = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1),
            intArrayOf(0, -1), intArrayOf(0, 1),
            intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1)
        )

        for (offset in offsets) {
            val nLat = bbox.lat + offset[0] * bbox.latErr * 2
            val nLon = bbox.lon + offset[1] * bbox.lonErr * 2
            result.add(encode(nLat, nLon, precision))
        }

        return result.toList()
    }

    private data class BBox(
        val lat: Double,
        val lon: Double,
        val latErr: Double,
        val lonErr: Double
    )

    private fun decodeBbox(hash: String): BBox {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var isLon = true

        for (c in hash) {
            val idx = BASE32.indexOf(c)
            for (bit in 4 downTo 0) {
                if (isLon) {
                    val mid = (lonMin + lonMax) / 2
                    if (idx and (1 shl bit) != 0) {
                        lonMin = mid
                    } else {
                        lonMax = mid
                    }
                } else {
                    val mid = (latMin + latMax) / 2
                    if (idx and (1 shl bit) != 0) {
                        latMin = mid
                    } else {
                        latMax = mid
                    }
                }
                isLon = !isLon
            }
        }

        return BBox(
            lat = (latMin + latMax) / 2,
            lon = (lonMin + lonMax) / 2,
            latErr = (latMax - latMin) / 2,
            lonErr = (lonMax - lonMin) / 2
        )
    }
}
