/**
 * Event store port for OID4VCI issuance session lifecycle events.
 */

package di.swallet.wpb.observability

import di.swallet.wpb.issuance.domain.IssuanceState
import java.time.Instant
import java.util.UUID

/**
 * Structured lifecycle event for an OID4VCI issuance session.
 * Uses the same sessionId, correlationId, state, and attributes model as presentation events.
 */
data class IssuanceEvent(
    val sessionId: UUID,
    val correlationId: String,
    val timestamp: Instant,
    val type: String,
    val state: IssuanceState,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Records and retrieves issuance lifecycle events for debugging and audit.
 */
interface IssuanceEventStore {
    /** Appends an event to the session history. */
    fun record(event: IssuanceEvent)

    /** Returns all events recorded for the session, oldest first. */
    fun getEvents(sessionId: UUID): List<IssuanceEvent>
}
