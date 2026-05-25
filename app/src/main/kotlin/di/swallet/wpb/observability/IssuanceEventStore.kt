package di.swallet.wpb.observability

import di.swallet.wpb.issuance.domain.IssuanceState
import java.time.Instant
import java.util.UUID

/**
 * Structured lifecycle event recorded for an OID4VCI issuance session.
 *
 * Mirrors [SessionEvent] from Phase 1 but is typed for the issuance
 * lifecycle. Reusing the conceptual model (sessionId + correlationId +
 * state + type + attributes) keeps the audit/event layer consistent
 * across the wallet's protocols.
 */
data class IssuanceEvent(
    val sessionId: UUID,
    val correlationId: String,
    val timestamp: Instant,
    val type: String,
    val state: IssuanceState,
    val attributes: Map<String, String> = emptyMap(),
)

interface IssuanceEventStore {
    fun record(event: IssuanceEvent)
    fun getEvents(sessionId: UUID): List<IssuanceEvent>
}
