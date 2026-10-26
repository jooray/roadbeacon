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

    /** Relay-side expiration: 14 days per NIP spec. All events use this constant. */
    private const val RELAY_TTL = ProtocolText.RELAY_TTL_SECONDS

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

    fun createReportEvent(
        type: EventType,
        lat: Double,
        lon: Double,
        privateKey: ByteArray,
        content: String = ""
    ): NostrEvent {
        val now = System.currentTimeMillis() / 1000
        val latitudeE7 = CanonicalCoordinates.latitudeE7(lat)
            ?: throw IllegalArgumentException("Latitude out of range: $lat")
        val longitudeE7 = CanonicalCoordinates.longitudeE7(lon)
            ?: throw IllegalArgumentException("Longitude out of range: $lon")

        // Geohashes and tag text are derived from the same rounded value a receiver of a frame
        // will reconstruct, so an event we publish is always representable as a frame.
        val latDeg = CanonicalCoordinates.degrees(latitudeE7)
        val lonDeg = CanonicalCoordinates.degrees(longitudeE7)
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(latDeg, lonDeg, it) }
        val expiration = now + RELAY_TTL

        val tags = listOf(
            listOf("t", type.typeName),
            listOf("g", geohashes[0]),
            listOf("g", geohashes[1]),
            listOf("g", geohashes[2]),
            listOf("lat", CanonicalCoordinates.format(latitudeE7)),
            listOf("lon", CanonicalCoordinates.format(longitudeE7)),
            listOf("expiration", expiration.toString()),
            listOf("alt", ProtocolText.reportAlt(type.typeName))
        )

        val unsigned = NostrEvent(
            createdAt = now,
            kind = 1315,
            tags = tags,
            content = content
        )

        return sign(unsigned, privateKey)
    }

    fun createConfirmationEvent(
        eventId: String,
        status: ConfirmationStatus,
        lat: Double,
        lon: Double,
        privateKey: ByteArray
    ): NostrEvent {
        val now = System.currentTimeMillis() / 1000
        val latitudeE7 = CanonicalCoordinates.latitudeE7(lat)
            ?: throw IllegalArgumentException("Latitude out of range: $lat")
        val longitudeE7 = CanonicalCoordinates.longitudeE7(lon)
            ?: throw IllegalArgumentException("Longitude out of range: $lon")

        val latDeg = CanonicalCoordinates.degrees(latitudeE7)
        val lonDeg = CanonicalCoordinates.degrees(longitudeE7)
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(latDeg, lonDeg, it) }
        val expiration = now + RELAY_TTL

        // Alt tag must be canonical per NIP spec for deterministic mesh encoding
        val altText = when (status) {
            ConfirmationStatus.STILL_THERE -> ProtocolText.CONFIRMED_ALT
            ConfirmationStatus.NO_LONGER_THERE -> ProtocolText.DENIED_ALT
        }

        val tags = listOf(
            listOf("e", eventId),
            listOf("g", geohashes[0]),
            listOf("g", geohashes[1]),
            listOf("g", geohashes[2]),
            listOf("status", status.value),
            listOf("lat", CanonicalCoordinates.format(latitudeE7)),
            listOf("lon", CanonicalCoordinates.format(longitudeE7)),
            listOf("expiration", expiration.toString()),
            listOf("alt", altText)
        )

        val unsigned = NostrEvent(
            createdAt = now,
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
