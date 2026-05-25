package di.swallet.wpb.observability

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemorySessionEventStore : SessionEventStore {
    private val store = ConcurrentHashMap<UUID, MutableList<SessionEvent>>()

    override fun record(event: SessionEvent) {
        val list = store.computeIfAbsent(event.sessionId) { mutableListOf() }
        synchronized(list) { list.add(event) }
    }

    override fun getEvents(sessionId: UUID): List<SessionEvent> {
        val list = store[sessionId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }
}
