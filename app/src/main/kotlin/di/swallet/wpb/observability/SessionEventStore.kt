package di.swallet.wpb.observability

import di.swallet.wpb.presentation.domain.PresentationState
import java.time.Instant
import java.util.UUID

/**
 * Structured lifecycle event recorded for an OpenID4VP session.
 *
 * Each event carries the session identifier, a correlation identifier
 * shared with downstream systems (e.g., verifier emulator), the lifecycle
 * state at the moment of the event, a short event type token and a
 * map of free-form attributes used for debugging.
 */
data class SessionEvent(
    val sessionId: UUID,
    val correlationId: String,
    val timestamp: Instant,
    val type: String,
    val state: PresentationState,
    val attributes: Map<String, String> = emptyMap(),
)

interface SessionEventStore {
    fun record(event: SessionEvent)
    fun getEvents(sessionId: UUID): List<SessionEvent>
}
