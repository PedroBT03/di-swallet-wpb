package di.swallet.wpb.observability

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [IssuanceEventStore].
 *
 * Suitable for development and tests. A durable implementation
 * backed by JPA is out of scope for the Phase 2 MVP.
 */
@Component
class InMemoryIssuanceEventStore : IssuanceEventStore {
    private val events: MutableMap<UUID, MutableList<IssuanceEvent>> = ConcurrentHashMap()

    override fun record(event: IssuanceEvent) {
        events.computeIfAbsent(event.sessionId) { mutableListOf() }.add(event)
    }

    override fun getEvents(sessionId: UUID): List<IssuanceEvent> =
        events[sessionId]?.toList() ?: emptyList()
}
