/**
 * Event store port for OpenID4VP presentation session lifecycle events.
 */

package di.swallet.wpb.observability

import di.swallet.wpb.presentation.domain.PresentationState
import java.time.Instant
import java.util.UUID

/**
 * Structured lifecycle event for an OpenID4VP presentation session.
 * [correlationId] is shared with downstream systems such as the verifier emulator.
 */
data class SessionEvent(
    val sessionId: UUID,
    val correlationId: String,
    val timestamp: Instant,
    val type: String,
    val state: PresentationState,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Records and retrieves presentation lifecycle events for debugging and audit.
 */
interface SessionEventStore {
    /** Appends an event to the session history. */
    fun record(event: SessionEvent)

    /** Returns all events recorded for the session, oldest first. */
    fun getEvents(sessionId: UUID): List<SessionEvent>
}
