package app.roadbeacon.mesh

import app.roadbeacon.nostr.EventSigner
import app.roadbeacon.nostr.NostrEvent
import app.roadbeacon.nostr.NostrFilter
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** "May this bridge send the summary for [area] now?" Several bridges must not all say yes. */
fun interface BridgeClaims {
    suspend fun claim(area: String, hops: Int): Boolean
}

/**
 * One bridge per area, agreed over Nostr (SPECIFICATION.md 4.3, rule 7): publish an ephemeral
 * kind 21315 claim, listen 1.5 s, claim again (relays keep no ephemeral events, so a late
 * subscriber missed the first), listen 0.3 s more; the fewest hops, then the lowest
 * SHA-256(pubkey ‖ area), wins.
 *
 * The claim key is random, made once per bridge start and kept only in memory, so a claim links
 * neither the driver's identity nor a stable bridge identity to a place.
 */
class NostrBridgeClaims(
    private val events: Flow<NostrEvent>,
    private val subscribe: (NostrFilter) -> String,
    private val close: (String) -> Unit,
    private val publish: (NostrEvent) -> Unit,
    private val connectedRelays: () -> Int,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) : BridgeClaims {
    private val key: ByteArray = randomKey()

    override suspend fun claim(area: String, hops: Int): Boolean {
        if (connectedRelays() == 0) return false
        val now = nowSeconds()
        fun claimAt(at: Long) = EventSigner.sign(
            NostrEvent(
                createdAt = at,
                kind = KIND_CLAIM,
                tags = listOf(
                    listOf("g", area),
                    listOf("hops", hops.coerceIn(0, 255).toString()),
                    listOf("expiration", (at + EXPIRATION_SECONDS).toString())
                ),
                content = ""
            ),
            key
        )
        val own = claimAt(now)
        val heard = mutableListOf(own)
        val sub = subscribe(NostrFilter(kinds = listOf(KIND_CLAIM), gTags = listOf(area), since = now - WINDOW_BEFORE_SECONDS))
        try {
            coroutineScope {
                val listener = launch {
                    events.collect { event ->
                        if (event.kind == KIND_CLAIM && event.pubkey != own.pubkey && areaOf(event) == area) {
                            synchronized(heard) { heard += event }
                        }
                    }
                }
                publish(own)
                delay(LISTEN_MS)
                // Relays do not store ephemeral events: a bridge that subscribed after the first
                // claim never saw it. Saying it again lets both sides compare the same set.
                publish(claimAt(now + 1))
                delay(GRACE_MS)
                listener.cancel()
            }
        } finally {
            close(sub)
        }
        val winner = synchronized(heard) { winner(heard.toList(), area, now) }
        return winner?.pubkey == own.pubkey
    }

    companion object {
        const val KIND_CLAIM = 21315
        const val LISTEN_MS = 1_500L
        const val GRACE_MS = 300L
        const val WINDOW_BEFORE_SECONDS = 60L
        const val WINDOW_AFTER_SECONDS = 5L
        const val EXPIRATION_SECONDS = 120L

        private fun areaOf(event: NostrEvent): String? =
            event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)

        private fun hopsOf(event: NostrEvent): Int =
            event.tags.firstOrNull { it.size >= 2 && it[0] == "hops" }?.get(1)?.toIntOrNull()?.coerceIn(0, 255) ?: 255

        /** The winning claim among [claims] for [area] made around [ownAt]; events are already verified. */
        fun winner(claims: List<NostrEvent>, area: String, ownAt: Long): NostrEvent? = claims
            .filter { it.kind == KIND_CLAIM && areaOf(it) == area }
            .filter { it.createdAt in (ownAt - WINDOW_BEFORE_SECONDS)..(ownAt + WINDOW_AFTER_SECONDS) }
            .map { it to (hopsOf(it) to BridgeSummaryCodec.claimHash(it.pubkey, area)) }
            .sortedWith { a, b -> BridgeSummaryCodec.compareClaims(a.second, b.second) }
            .firstOrNull()?.first

        private fun randomKey(): ByteArray {
            val random = SecureRandom()
            val secp = Secp256k1.get()
            while (true) {
                val candidate = ByteArray(32).also(random::nextBytes)
                if (secp.secKeyVerify(candidate)) return candidate
            }
        }
    }
}
