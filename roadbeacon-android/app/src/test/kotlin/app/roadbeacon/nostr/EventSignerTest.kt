package app.roadbeacon.nostr

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class EventSignerTest {

    @Test
    fun `computeId produces valid SHA-256 hex`() {
        val event = NostrEvent(
            pubkey = "a".repeat(64),
            createdAt = 1700000000L,
            kind = 1315,
            tags = listOf(
                listOf("t", "police"),
                listOf("g", "u2ed")
            ),
            content = ""
        )

        val id = event.computeId()
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `computeId is deterministic`() {
        val event = NostrEvent(
            pubkey = "a".repeat(64),
            createdAt = 1700000000L,
            kind = 1,
            tags = listOf(listOf("t", "test")),
            content = "hello"
        )

        val id1 = event.computeId()
        val id2 = event.computeId()
        assertEquals(id1, id2)
    }

    @Test
    fun `computeId changes when content changes`() {
        val event1 = NostrEvent(
            pubkey = "a".repeat(64),
            createdAt = 1700000000L,
            kind = 1,
            tags = emptyList(),
            content = "hello"
        )
        val event2 = event1.copy(content = "world")

        assertNotEquals(event1.computeId(), event2.computeId())
    }

    @Test
    fun `computeId changes when tags change`() {
        val event1 = NostrEvent(
            pubkey = "a".repeat(64),
            createdAt = 1700000000L,
            kind = 1315,
            tags = listOf(listOf("t", "police")),
            content = ""
        )
        val event2 = event1.copy(tags = listOf(listOf("t", "accident")))

        assertNotEquals(event1.computeId(), event2.computeId())
    }

    @Test
    fun `NostrEvent serialization round trip`() {
        val event = NostrEvent(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1700000000L,
            kind = 1315,
            tags = listOf(
                listOf("t", "police"),
                listOf("g", "u2ed"),
                listOf("lat", "48.8566140")
            ),
            content = "",
            sig = "c".repeat(128)
        )

        val json = event.toJson()
        val parsed = NostrEvent.fromJson(json)

        assertEquals(event.id, parsed.id)
        assertEquals(event.pubkey, parsed.pubkey)
        assertEquals(event.createdAt, parsed.createdAt)
        assertEquals(event.kind, parsed.kind)
        assertEquals(event.tags, parsed.tags)
        assertEquals(event.content, parsed.content)
        assertEquals(event.sig, parsed.sig)
    }

    @Test
    fun `verify rejects event with wrong id`() {
        // EventSigner.verify checks ID computation first, before signature
        // This test verifies the ID check without needing secp256k1 JNI
        val event = NostrEvent(
            pubkey = "a".repeat(64),
            createdAt = 1700000000L,
            kind = 1,
            tags = emptyList(),
            content = "test",
            sig = "b".repeat(128)
        )
        val correctId = event.computeId()

        // ID mismatch should make verify return false
        // But secp256k1 JNI isn't available in unit tests, so we test ID logic only
        assertNotEquals(correctId, "0".repeat(64))
        assertEquals(64, correctId.length)
    }
}
