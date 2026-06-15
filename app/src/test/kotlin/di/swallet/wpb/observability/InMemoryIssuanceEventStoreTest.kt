/**
 * Tests in memory issuance event store.
 */

package di.swallet.wpb.observability

import di.swallet.wpb.issuance.domain.IssuanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryIssuanceEventStoreTest {

    /**
     * Two issuance events are recorded for the same session with different types and attributes.
     * getEvents returns both in order, including the issuer attribute on the last entry.
     */
    @Test
    fun `records and retrieves events for a session`() {
        val store = InMemoryIssuanceEventStore()
        val sessionId = UUID.randomUUID()
        val correlationId = UUID.randomUUID().toString()
        store.record(
            IssuanceEvent(
                sessionId = sessionId,
                correlationId = correlationId,
                timestamp = Instant.now(),
                type = "offer.received",
                state = IssuanceState.OFFER_RECEIVED,
            ),
        )
        store.record(
            IssuanceEvent(
                sessionId = sessionId,
                correlationId = correlationId,
                timestamp = Instant.now(),
                type = "offer.resolved",
                state = IssuanceState.OFFER_RESOLVED,
                attributes = mapOf("issuer" to "https://example"),
            ),
        )

        val events = store.getEvents(sessionId)
        assertEquals(2, events.size)
        assertEquals("offer.received", events.first().type)
        assertEquals("https://example", events.last().attributes["issuer"])
    }

    /**
     * No events were recorded for the queried session id.
     * getEvents returns an empty list.
     */
    @Test
    fun `returns empty list for unknown session`() {
        val store = InMemoryIssuanceEventStore()
        assertTrue(store.getEvents(UUID.randomUUID()).isEmpty())
    }
}
