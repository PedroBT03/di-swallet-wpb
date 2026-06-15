/**
 * In-memory presentation event store for development and tests.
 */

package di.swallet.wpb.observability

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [SessionEventStore] suitable for development and tests.
 */
@Component
class InMemorySessionEventStore : SessionEventStore {
    private val store = ConcurrentHashMap<UUID, MutableList<SessionEvent>>()

    /** Appends the event to the in-memory list for its session. */
    override fun record(event: SessionEvent) {
        val list = store.computeIfAbsent(event.sessionId) { mutableListOf() }
        synchronized(list) { list.add(event) }
    }

    /** Returns a snapshot of events for the session, or an empty list when none exist. */
    override fun getEvents(sessionId: UUID): List<SessionEvent> {
        val list = store[sessionId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }
}
