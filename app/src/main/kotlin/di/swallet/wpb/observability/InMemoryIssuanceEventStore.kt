/**
 * In-memory issuance event store for development and tests.
 */

package di.swallet.wpb.observability

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [IssuanceEventStore] suitable for development and tests.
 */
@Component
class InMemoryIssuanceEventStore : IssuanceEventStore {
    private val events: MutableMap<UUID, MutableList<IssuanceEvent>> = ConcurrentHashMap()

    /** Appends the event to the in-memory list for its session. */
    override fun record(event: IssuanceEvent) {
        events.computeIfAbsent(event.sessionId) { mutableListOf() }.add(event)
    }

    /** Returns a snapshot of events for the session, or an empty list when none exist. */
    override fun getEvents(sessionId: UUID): List<IssuanceEvent> =
        events[sessionId]?.toList() ?: emptyList()
}
