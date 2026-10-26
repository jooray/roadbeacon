package app.roadbeacon.nostr

import android.util.Log
import app.roadbeacon.location.GeohashUtil
import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.model.EventType
import app.roadbeacon.storage.hexToBytes
import app.roadbeacon.storage.toHex
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

object EventSigner {

    private const val TAG = "EventSigner"
    private val secp256k1 = Secp256k1.get()

    fun sign(event: NostrEvent, privateKey: ByteArray): NostrEvent {
        val pubkey = derivePublicKey(privateKey).toHex()
        val withPubkey = event.copy(pubkey = pubkey)
        val id = withPubkey.computeId()
        val withId = withPubkey.copy(id = id)

        val aux = ByteArray(32)
        SecureRandom().nextBytes(aux)
        val sig = secp256k1.signSchnorr(id.hexToBytes(), privateKey, aux)

        return withId.copy(sig = sig.toHex())
    }

    fun verify(event: NostrEvent): Boolean {
        return try {
            val expectedId = event.computeId()
            if (expectedId != event.id) {
                Log.d(TAG, "ID mismatch for ${event.id.take(8)}: expected ${expectedId.take(8)}")
                return false
            }

            secp256k1.verifySchnorr(
                event.sig.hexToBytes(),
                event.id.hexToBytes(),
                event.pubkey.hexToBytes()
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid hex in event ${event.id.take(8)}: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Verify failed for ${event.id.take(8)}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Kind 1315 report in the shared layout:
     * `lat, lon, g4, g5, g6, t, expiration[, maxspeed]`, no `alt`, six-decimal coordinates.
     */
    fun createReportEvent(
        type: EventType,
        lat: Double,
        lon: Double,
        privateKey: ByteArray,
        content: String = "",
        speedLimitKmh: Int? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val latitudeE6 = CanonicalCoordinates.latitudeE6(lat)
            ?: throw IllegalArgumentException("Latitude out of range: $lat")
        val longitudeE6 = CanonicalCoordinates.longitudeE6(lon)
            ?: throw IllegalArgumentException("Longitude out of range: $lon")
        if (speedLimitKmh != null && speedLimitKmh !in 5..300) {
            throw IllegalArgumentException("Speed limit out of range: $speedLimitKmh")
        }

        // Geohashes and tag text are derived from the same rounded value a receiver of a frame
        // will reconstruct, so an event we publish is always representable as a frame.
        val latDeg = CanonicalCoordinates.degrees(latitudeE6)
        val lonDeg = CanonicalCoordinates.degrees(longitudeE6)
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(latDeg, lonDeg, it) }

        val tags = buildList {
            add(listOf("lat", CanonicalCoordinates.format(latitudeE6)))
            add(listOf("lon", CanonicalCoordinates.format(longitudeE6)))
            add(listOf("g", geohashes[0]))
            add(listOf("g", geohashes[1]))
            add(listOf("g", geohashes[2]))
            add(listOf("t", type.typeName))
            add(listOf("expiration", RoadEventFormat.expirationFor(createdAt, type).toString()))
            if (speedLimitKmh != null) add(listOf("maxspeed", speedLimitKmh.toString()))
        }

        val unsigned = NostrEvent(
            createdAt = createdAt,
            kind = 1315,
            tags = tags,
            content = content
        )

        return sign(unsigned, privateKey)
    }

    /**
     * Kind 1316 vote in the shared layout: `e, status` and nothing else. A vote carries no
     * position of its own, which is why a confirmation frame needs no lookup of its report.
     */
    fun createConfirmationEvent(
        eventId: String,
        status: ConfirmationStatus,
        privateKey: ByteArray,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val tags = listOf(
            listOf("e", eventId),
            listOf("status", status.value)
        )

        val unsigned = NostrEvent(
            createdAt = createdAt,
            kind = 1316,
            tags = tags,
            content = ""
        )

        return sign(unsigned, privateKey)
    }

    private fun derivePublicKey(privKey: ByteArray): ByteArray {
        val fullPubKey = secp256k1.pubkeyCreate(privKey)
        val compressed = secp256k1.pubKeyCompress(fullPubKey)
        return compressed.copyOfRange(1, 33)
    }
}
